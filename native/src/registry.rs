use std::collections::HashMap;
use std::net::{Ipv4Addr, SocketAddrV4, UdpSocket};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use bytes::BytesMut;
use webrtc::data_channel::{
    DataChannel, DataChannelEvent, RTCDataChannelInit, RTCDataChannelState,
};
use webrtc::peer_connection::{
    PeerConnection as NativePeerConnection, PeerConnectionBuilder, PeerConnectionEventHandler,
    RTCConfigurationBuilder, RTCIceCandidateInit, RTCIceConnectionState, RTCIceGatheringState,
    RTCIceServer, RTCIceTransportPolicy, RTCPeerConnectionIceEvent, RTCPeerConnectionState,
    RTCSessionDescription,
};
use webrtc::runtime::TokioRuntime;

use crate::events::{
    self, DATA_CHANNEL, DATA_CHANNEL_BINARY, DATA_CHANNEL_CLOSED, DATA_CHANNEL_CLOSING,
    DATA_CHANNEL_ERROR, DATA_CHANNEL_OPEN, DATA_CHANNEL_TEXT, ICE_CONNECTION_STATE,
    ICE_GATHERING_STATE, LOCAL_CANDIDATE, NativeEvent, PEER_STATE,
};
use crate::runtime;

#[derive(Debug)]
pub struct PeerConfiguration {
    pub ice_servers: Vec<RTCIceServer>,
    pub min_port: u16,
    pub max_port: u16,
    pub relay_only: bool,
    pub data_channel_send_buffer_limit: usize,
    pub operation_timeout: Duration,
}

#[derive(Debug)]
pub struct DataChannelConfiguration {
    pub label: String,
    pub ordered: bool,
    pub max_packet_life_time: Option<u16>,
    pub max_retransmits: Option<u16>,
    pub protocol: String,
    pub negotiated_id: Option<u16>,
}

struct PeerEntry {
    peer: Arc<dyn NativePeerConnection>,
    operation_timeout: Duration,
}

struct ChannelEntry {
    channel: Arc<dyn DataChannel>,
    peer_handle: u64,
}

#[derive(Default)]
struct Registry {
    peers: HashMap<u64, PeerEntry>,
    channels: HashMap<u64, ChannelEntry>,
}

static REGISTRY: LazyLock<Mutex<Registry>> = LazyLock::new(|| Mutex::new(Registry::default()));
static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);

#[derive(Debug)]
struct EventHandler {
    peer_handle: u64,
}

#[async_trait]
impl PeerConnectionEventHandler for EventHandler {
    async fn on_ice_candidate(&self, event: RTCPeerConnectionIceEvent) {
        let Ok(candidate) = event.candidate.to_json() else {
            return;
        };
        events::send(NativeEvent {
            kind: LOCAL_CANDIDATE,
            peer_handle: self.peer_handle,
            channel_handle: 0,
            text: Some(candidate.candidate),
            secondary_text: candidate.sdp_mid,
            number: candidate.sdp_mline_index.map_or(-1, i32::from),
            data: None,
        });
    }

    async fn on_connection_state_change(&self, state: RTCPeerConnectionState) {
        events::send(NativeEvent::peer(
            PEER_STATE,
            self.peer_handle,
            peer_state_number(state),
        ));
    }

    async fn on_ice_connection_state_change(&self, state: RTCIceConnectionState) {
        events::send(NativeEvent::peer(
            ICE_CONNECTION_STATE,
            self.peer_handle,
            ice_connection_state_number(state),
        ));
    }

    async fn on_ice_gathering_state_change(&self, state: RTCIceGatheringState) {
        events::send(NativeEvent::peer(
            ICE_GATHERING_STATE,
            self.peer_handle,
            ice_gathering_state_number(state),
        ));
    }

    async fn on_data_channel(&self, channel: Arc<dyn DataChannel>) {
        let peer_handle = self.peer_handle;
        tokio::spawn(async move {
            let _ = register_channel(peer_handle, channel, true).await;
        });
    }
}

pub fn create_peer(configuration: PeerConfiguration) -> Result<u64, String> {
    let handle = next_handle();
    let timeout = configuration.operation_timeout;
    let udp_address = choose_udp_address(configuration.min_port, configuration.max_port)?;
    let ice_policy = if configuration.relay_only {
        RTCIceTransportPolicy::Relay
    } else {
        RTCIceTransportPolicy::All
    };
    let rtc_configuration = RTCConfigurationBuilder::new()
        .with_ice_servers(configuration.ice_servers)
        .with_ice_transport_policy(ice_policy)
        .build();

    let peer = runtime::block_on(timeout, async move {
        let peer = Box::pin(
            PeerConnectionBuilder::new()
                .with_configuration(rtc_configuration)
                .with_handler(Arc::new(EventHandler {
                    peer_handle: handle,
                }))
                .with_runtime(Arc::new(TokioRuntime))
                .with_udp_addrs(vec![udp_address])
                .with_data_channel_send_buffer_limit(configuration.data_channel_send_buffer_limit)
                .build(),
        )
        .await
        .map_err(|error| format!("Failed to create peer connection: {error}"))?;
        Ok(Arc::new(peer) as Arc<dyn NativePeerConnection>)
    })?;

    lock_registry()?.peers.insert(
        handle,
        PeerEntry {
            peer,
            operation_timeout: timeout,
        },
    );
    Ok(handle)
}

pub fn create_description(peer_handle: u64, answer: bool) -> Result<String, String> {
    let (peer, timeout) = get_peer(peer_handle)?;
    runtime::block_on(timeout, async move {
        let description = if answer {
            peer.create_answer(None).await
        } else {
            peer.create_offer(None).await
        }
        .map_err(|error| format!("Failed to create session description: {error}"))?;
        Ok(description.sdp)
    })
}

pub fn set_local_description(
    peer_handle: u64,
    description: RTCSessionDescription,
    timeout: Duration,
) -> Result<(), String> {
    let (peer, _) = get_peer(peer_handle)?;
    runtime::block_on(timeout, async move {
        peer.set_local_description(description)
            .await
            .map_err(|error| format!("Failed to set local description: {error}"))
    })
}

pub fn set_remote_description(
    peer_handle: u64,
    description: RTCSessionDescription,
    timeout: Duration,
) -> Result<(), String> {
    let (peer, _) = get_peer(peer_handle)?;
    runtime::block_on(timeout, async move {
        peer.set_remote_description(description)
            .await
            .map_err(|error| format!("Failed to set remote description: {error}"))
    })
}

pub fn add_ice_candidate(
    peer_handle: u64,
    candidate: RTCIceCandidateInit,
    timeout: Duration,
) -> Result<(), String> {
    let (peer, _) = get_peer(peer_handle)?;
    runtime::block_on(timeout, async move {
        peer.add_ice_candidate(candidate)
            .await
            .map_err(|error| format!("Failed to add ICE candidate: {error}"))
    })
}

pub fn create_data_channel(
    peer_handle: u64,
    configuration: DataChannelConfiguration,
    timeout: Duration,
) -> Result<u64, String> {
    let (peer, _) = get_peer(peer_handle)?;
    runtime::block_on(timeout, async move {
        let label = configuration.label;
        let options = RTCDataChannelInit {
            ordered: configuration.ordered,
            max_packet_life_time: configuration.max_packet_life_time,
            max_retransmits: configuration.max_retransmits,
            protocol: configuration.protocol,
            negotiated: configuration.negotiated_id,
        };
        let channel = peer
            .create_data_channel(&label, Some(options))
            .await
            .map_err(|error| format!("Failed to create DataChannel: {error}"))?;
        register_channel(peer_handle, channel, false).await
    })
}

pub fn send_text(channel_handle: u64, text: String) -> Result<(), String> {
    let (channel, peer_handle) = get_channel(channel_handle)?;
    let (_, timeout) = get_peer(peer_handle)?;
    runtime::block_on(timeout, async move {
        channel
            .send_text(&text)
            .await
            .map_err(|error| format!("Failed to send DataChannel text: {error}"))
    })
}

pub fn send_binary(channel_handle: u64, data: Vec<u8>) -> Result<(), String> {
    let (channel, peer_handle) = get_channel(channel_handle)?;
    let (_, timeout) = get_peer(peer_handle)?;
    runtime::block_on(timeout, async move {
        channel
            .send(BytesMut::from(data.as_slice()))
            .await
            .map_err(|error| format!("Failed to send DataChannel data: {error}"))
    })
}

pub fn close_data_channel(channel_handle: u64) -> Result<(), String> {
    let entry = lock_registry()?.channels.remove(&channel_handle);
    let Some(entry) = entry else {
        return Ok(());
    };
    let (_, timeout) = get_peer(entry.peer_handle)?;
    runtime::block_on(timeout, async move {
        entry
            .channel
            .close()
            .await
            .map_err(|error| format!("Failed to close DataChannel: {error}"))
    })
}

pub fn close_peer(peer_handle: u64, timeout: Duration) -> Result<(), String> {
    let peer = {
        let mut registry = lock_registry()?;
        registry
            .channels
            .retain(|_, channel| channel.peer_handle != peer_handle);
        registry.peers.remove(&peer_handle).map(|entry| entry.peer)
    };
    let Some(peer) = peer else {
        return Ok(());
    };
    runtime::block_on(timeout, async move {
        peer.close()
            .await
            .map_err(|error| format!("Failed to close peer connection: {error}"))
    })
}

pub fn shutdown_all(timeout: Duration) -> Result<(), String> {
    let peers = {
        let mut registry = lock_registry()?;
        registry.channels.clear();
        registry
            .peers
            .drain()
            .map(|(_, entry)| entry.peer)
            .collect::<Vec<_>>()
    };
    if peers.is_empty() {
        return Ok(());
    }
    runtime::block_on(timeout, async move {
        for peer in peers {
            let _ = peer.close().await;
        }
        Ok(())
    })
}

async fn register_channel(
    peer_handle: u64,
    channel: Arc<dyn DataChannel>,
    emit_created_event: bool,
) -> Result<u64, String> {
    let handle = next_handle();
    let label = channel.label().await.unwrap_or_default();
    let protocol = channel.protocol().await.unwrap_or_default();
    let ordered = channel.ordered().await.unwrap_or(true);
    let open = channel
        .ready_state()
        .await
        .is_ok_and(|state| state == RTCDataChannelState::Open);

    lock_registry()?.channels.insert(
        handle,
        ChannelEntry {
            channel: Arc::clone(&channel),
            peer_handle,
        },
    );

    if emit_created_event {
        events::send(NativeEvent {
            kind: DATA_CHANNEL,
            peer_handle,
            channel_handle: handle,
            text: Some(label),
            secondary_text: Some(protocol),
            number: i32::from(ordered) | (i32::from(open) << 1),
            data: None,
        });
    }

    tokio::spawn(poll_data_channel(peer_handle, handle, channel));
    Ok(handle)
}

async fn poll_data_channel(peer_handle: u64, channel_handle: u64, channel: Arc<dyn DataChannel>) {
    while let Some(event) = channel.poll().await {
        match event {
            DataChannelEvent::OnOpen => events::send(NativeEvent::channel(
                DATA_CHANNEL_OPEN,
                peer_handle,
                channel_handle,
            )),
            DataChannelEvent::OnError => events::send(NativeEvent {
                text: Some("The native DataChannel reported an error".to_owned()),
                ..NativeEvent::channel(DATA_CHANNEL_ERROR, peer_handle, channel_handle)
            }),
            DataChannelEvent::OnClosing => events::send(NativeEvent::channel(
                DATA_CHANNEL_CLOSING,
                peer_handle,
                channel_handle,
            )),
            DataChannelEvent::OnClose => {
                events::send(NativeEvent::channel(
                    DATA_CHANNEL_CLOSED,
                    peer_handle,
                    channel_handle,
                ));
                break;
            }
            DataChannelEvent::OnMessage(message) if message.is_string => {
                let text = String::from_utf8_lossy(&message.data).into_owned();
                events::send(NativeEvent {
                    text: Some(text),
                    ..NativeEvent::channel(DATA_CHANNEL_TEXT, peer_handle, channel_handle)
                });
            }
            DataChannelEvent::OnMessage(message) => events::send(NativeEvent {
                data: Some(message.data.to_vec()),
                ..NativeEvent::channel(DATA_CHANNEL_BINARY, peer_handle, channel_handle)
            }),
            DataChannelEvent::OnBufferedAmountLow | DataChannelEvent::OnBufferedAmountHigh => {}
        }
    }
    if let Ok(mut registry) = REGISTRY.lock() {
        registry.channels.remove(&channel_handle);
    }
}

fn get_peer(peer_handle: u64) -> Result<(Arc<dyn NativePeerConnection>, Duration), String> {
    let registry = lock_registry()?;
    let entry = registry
        .peers
        .get(&peer_handle)
        .ok_or_else(|| format!("Unknown peer connection handle: {peer_handle}"))?;
    Ok((Arc::clone(&entry.peer), entry.operation_timeout))
}

fn get_channel(channel_handle: u64) -> Result<(Arc<dyn DataChannel>, u64), String> {
    let registry = lock_registry()?;
    let entry = registry
        .channels
        .get(&channel_handle)
        .ok_or_else(|| format!("Unknown DataChannel handle: {channel_handle}"))?;
    Ok((Arc::clone(&entry.channel), entry.peer_handle))
}

fn lock_registry() -> Result<std::sync::MutexGuard<'static, Registry>, String> {
    REGISTRY
        .lock()
        .map_err(|_| "Alloy WebRTC handle registry is poisoned".to_owned())
}

fn next_handle() -> u64 {
    NEXT_HANDLE.fetch_add(1, Ordering::Relaxed)
}

fn choose_udp_address(min_port: u16, max_port: u16) -> Result<String, String> {
    if min_port == 0 && max_port == 0 {
        return Ok("0.0.0.0:0".to_owned());
    }
    for port in min_port..=max_port {
        let address = SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, port);
        if UdpSocket::bind(address).is_ok() {
            return Ok(address.to_string());
        }
    }
    Err(format!(
        "No UDP port is available in the configured range {min_port}-{max_port}"
    ))
}

fn peer_state_number(state: RTCPeerConnectionState) -> i32 {
    match state {
        RTCPeerConnectionState::Unspecified | RTCPeerConnectionState::New => 0,
        RTCPeerConnectionState::Connecting => 1,
        RTCPeerConnectionState::Connected => 2,
        RTCPeerConnectionState::Disconnected => 3,
        RTCPeerConnectionState::Failed => 4,
        RTCPeerConnectionState::Closed => 5,
    }
}

fn ice_connection_state_number(state: RTCIceConnectionState) -> i32 {
    match state {
        RTCIceConnectionState::Unspecified | RTCIceConnectionState::New => 0,
        RTCIceConnectionState::Checking => 1,
        RTCIceConnectionState::Connected => 2,
        RTCIceConnectionState::Completed => 3,
        RTCIceConnectionState::Disconnected => 4,
        RTCIceConnectionState::Failed => 5,
        RTCIceConnectionState::Closed => 6,
    }
}

fn ice_gathering_state_number(state: RTCIceGatheringState) -> i32 {
    match state {
        RTCIceGatheringState::Unspecified | RTCIceGatheringState::New => 0,
        RTCIceGatheringState::Gathering => 1,
        RTCIceGatheringState::Complete => 2,
    }
}

#[cfg(test)]
mod tests {
    use super::choose_udp_address;

    #[test]
    fn selects_an_ephemeral_udp_port_by_default() {
        assert_eq!(choose_udp_address(0, 0).unwrap(), "0.0.0.0:0");
    }
}

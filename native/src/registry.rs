use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, Weak};
use std::time::Duration;

use async_trait::async_trait;
use bytes::BytesMut;
use crossbeam_channel::Sender;
use ice::mdns::MulticastDnsMode;
use ice::network_type::NetworkType;
use rtc::peer_connection::configuration::setting_engine::SctpMaxMessageSize;
use tokio::sync::Mutex as AsyncMutex;
use tokio::task::JoinSet;
use webrtc::data_channel::{
    DataChannel, DataChannelEvent, RTCDataChannelInit, RTCDataChannelState,
};
use webrtc::peer_connection::{
    CipherSuiteId, PeerConnection as NativePeerConnection, PeerConnectionBuilder,
    PeerConnectionEventHandler, RTCCertificate, RTCConfigurationBuilder, RTCIceCandidateInit,
    RTCIceCandidateType, RTCIceConnectionState, RTCIceGatheringState, RTCIceServer,
    RTCIceTransportPolicy, RTCPeerConnectionIceEvent, RTCPeerConnectionState,
    RTCSessionDescription, SettingEngine,
};
use webrtc::runtime::TokioRuntime;

use crate::events::{
    DATA_CHANNEL, DATA_CHANNEL_BINARY, DATA_CHANNEL_CLOSED, DATA_CHANNEL_CLOSING,
    DATA_CHANNEL_ERROR, DATA_CHANNEL_OPEN, DATA_CHANNEL_TEXT, ICE_CONNECTION_STATE,
    ICE_GATHERING_STATE, LOCAL_CANDIDATE, NativeEvent, PEER_STATE,
};

#[derive(Debug)]
pub struct PeerConfiguration {
    pub ice_servers: Vec<RTCIceServer>,
    pub min_port: u16,
    pub max_port: u16,
    pub relay_only: bool,
    pub ice: IceConfiguration,
    pub sctp: SctpConfiguration,
}

#[derive(Debug)]
pub struct IceConfiguration {
    pub disconnected_timeout: Option<Duration>,
    pub failed_timeout: Option<Duration>,
    pub keep_alive_interval: Option<Duration>,
    pub check_interval: Option<Duration>,
    pub max_binding_requests: Option<u16>,
    pub host_acceptance_min_wait: Option<Duration>,
    pub server_reflexive_acceptance_min_wait: Option<Duration>,
    pub peer_reflexive_acceptance_min_wait: Option<Duration>,
    pub relay_acceptance_min_wait: Option<Duration>,
    pub network_types: Vec<NetworkType>,
    pub mdns_mode: MulticastDnsMode,
    pub mdns_query_timeout: Option<Duration>,
    pub lite: bool,
    pub nat_mapping: Option<(Vec<String>, RTCIceCandidateType)>,
    pub discard_local_candidates_on_restart: bool,
    pub candidate_pool_size: u8,
}

#[derive(Debug)]
pub struct SctpConfiguration {
    pub send_buffer_limit: usize,
    pub receive_buffer_size: u32,
    pub maximum_message_size: u32,
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
    operations: Arc<AsyncMutex<()>>,
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

pub struct RuntimeState {
    registry: Mutex<Registry>,
    events: Sender<NativeEvent>,
    next_handle: AtomicU64,
    certificate: RTCCertificate,
}

impl RuntimeState {
    pub fn new(events: Sender<NativeEvent>, certificate: RTCCertificate) -> Self {
        Self {
            registry: Mutex::new(Registry::default()),
            events,
            next_handle: AtomicU64::new(1),
            certificate,
        }
    }

    pub fn send_event(&self, event: NativeEvent) {
        let _ = self.events.send(event);
    }

    pub async fn create_peer(
        self: &Arc<Self>,
        configuration: PeerConfiguration,
    ) -> Result<u64, String> {
        let handle = self.next_handle();
        let ice_policy = if configuration.relay_only {
            RTCIceTransportPolicy::Relay
        } else {
            RTCIceTransportPolicy::All
        };
        let setting_engine = setting_engine(&configuration);

        let handler = Arc::new(EventHandler {
            peer_handle: handle,
            runtime: Arc::downgrade(self),
        });
        let ports = if configuration.min_port == 0 {
            vec![0]
        } else {
            (configuration.min_port..=configuration.max_port).collect()
        };
        let mut last_error = None;
        let mut peer = None;
        for port in ports {
            let (udp_addresses, tcp_addresses) =
                bind_addresses(&configuration.ice.network_types, port);
            let rtc_configuration = RTCConfigurationBuilder::new()
                .with_ice_servers(configuration.ice_servers.clone())
                .with_ice_transport_policy(ice_policy)
                .with_certificates(vec![self.certificate.clone()])
                .with_ice_candidate_pool_size(configuration.ice.candidate_pool_size)
                .build();
            match Box::pin(
                PeerConnectionBuilder::new()
                    .with_configuration(rtc_configuration)
                    .with_setting_engine(setting_engine.clone())
                    .with_handler(handler.clone())
                    .with_runtime(Arc::new(TokioRuntime))
                    .with_udp_addrs(udp_addresses)
                    .with_tcp_addrs(tcp_addresses)
                    .with_data_channel_send_buffer_limit(configuration.sctp.send_buffer_limit)
                    .build(),
            )
            .await
            {
                Ok(connection) => {
                    peer = Some(Arc::new(connection) as Arc<dyn NativePeerConnection>);
                    break;
                }
                Err(error) => {
                    let message = error.to_string();
                    if port == 0 || !message.contains("no udp_sockets or tcp_listeners available") {
                        return Err(format!("Failed to create peer connection: {message}"));
                    }
                    last_error = Some(message);
                }
            }
        }
        let peer = peer.ok_or_else(|| {
            if configuration.min_port == 0 {
                format!(
                    "Failed to bind peer connection transports: {}",
                    last_error.unwrap_or_else(|| "unknown bind error".to_owned())
                )
            } else {
                format!(
                    "No transport port is available in the configured range {}-{}: {}",
                    configuration.min_port,
                    configuration.max_port,
                    last_error.unwrap_or_else(|| "unknown bind error".to_owned())
                )
            }
        })?;

        self.lock_registry()?.peers.insert(
            handle,
            PeerEntry {
                peer,
                operations: Arc::new(AsyncMutex::new(())),
            },
        );
        Ok(handle)
    }

    pub async fn create_description(
        &self,
        peer_handle: u64,
        answer: bool,
    ) -> Result<String, String> {
        let peer = self.get_peer(peer_handle)?;
        let _operation = peer.operations.lock().await;
        let description = if answer {
            peer.peer.create_answer(None).await
        } else {
            peer.peer.create_offer(None).await
        }
        .map_err(|error| format!("Failed to create session description: {error}"))?;
        Ok(description.sdp)
    }

    pub async fn set_local_description(
        &self,
        peer_handle: u64,
        description: RTCSessionDescription,
    ) -> Result<(), String> {
        let peer = self.get_peer(peer_handle)?;
        let _operation = peer.operations.lock().await;
        peer.peer
            .set_local_description(description)
            .await
            .map_err(|error| format!("Failed to set local description: {error}"))
    }

    pub async fn set_remote_description(
        &self,
        peer_handle: u64,
        description: RTCSessionDescription,
    ) -> Result<(), String> {
        let peer = self.get_peer(peer_handle)?;
        let _operation = peer.operations.lock().await;
        peer.peer
            .set_remote_description(description)
            .await
            .map_err(|error| format!("Failed to set remote description: {error}"))
    }

    pub async fn add_ice_candidate(
        &self,
        peer_handle: u64,
        candidate: RTCIceCandidateInit,
    ) -> Result<(), String> {
        let peer = self.get_peer(peer_handle)?;
        let _operation = peer.operations.lock().await;
        peer.peer
            .add_ice_candidate(candidate)
            .await
            .map_err(|error| format!("Failed to add ICE candidate: {error}"))
    }

    pub async fn restart_ice(&self, peer_handle: u64) -> Result<(), String> {
        let peer = self.get_peer(peer_handle)?;
        let _operation = peer.operations.lock().await;
        peer.peer
            .restart_ice()
            .await
            .map_err(|error| format!("Failed to restart ICE: {error}"))
    }

    pub async fn set_configuration(
        &self,
        peer_handle: u64,
        mut ice_servers: Vec<RTCIceServer>,
        relay_only: bool,
    ) -> Result<(), String> {
        let peer = self.get_peer(peer_handle)?;
        let _operation = peer.operations.lock().await;
        let policy = if relay_only {
            RTCIceTransportPolicy::Relay
        } else {
            RTCIceTransportPolicy::All
        };
        if ice_servers.is_empty() {
            ice_servers.push(RTCIceServer::default());
        }
        peer.peer
            .set_configuration(
                RTCConfigurationBuilder::new()
                    .with_ice_servers(ice_servers)
                    .with_ice_transport_policy(policy)
                    .build(),
            )
            .await
            .map_err(|error| format!("Failed to update peer configuration: {error}"))
    }

    pub async fn create_data_channel(
        self: &Arc<Self>,
        peer_handle: u64,
        configuration: DataChannelConfiguration,
    ) -> Result<u64, String> {
        let peer = self.get_peer(peer_handle)?;
        let _operation = peer.operations.lock().await;
        let label = configuration.label;
        let options = RTCDataChannelInit {
            ordered: configuration.ordered,
            max_packet_life_time: configuration.max_packet_life_time,
            max_retransmits: configuration.max_retransmits,
            protocol: configuration.protocol,
            negotiated: configuration.negotiated_id,
        };
        let channel = peer
            .peer
            .create_data_channel(&label, Some(options))
            .await
            .map_err(|error| format!("Failed to create DataChannel: {error}"))?;
        self.register_channel(peer_handle, channel, false).await
    }

    pub async fn send_text(&self, channel_handle: u64, text: String) -> Result<(), String> {
        let (channel, peer_handle) = self.get_channel(channel_handle)?;
        let peer = self.get_peer(peer_handle)?;
        let _operation = peer.operations.lock().await;
        channel
            .send_text(&text)
            .await
            .map_err(|error| format!("Failed to send DataChannel text: {error}"))
    }

    pub async fn send_binary(&self, channel_handle: u64, data: Vec<u8>) -> Result<(), String> {
        let (channel, peer_handle) = self.get_channel(channel_handle)?;
        let peer = self.get_peer(peer_handle)?;
        let _operation = peer.operations.lock().await;
        channel
            .send(BytesMut::from(data.as_slice()))
            .await
            .map_err(|error| format!("Failed to send DataChannel data: {error}"))
    }

    pub async fn close_data_channel(&self, channel_handle: u64) -> Result<(), String> {
        let entry = self.lock_registry()?.channels.remove(&channel_handle);
        let Some(entry) = entry else {
            return Ok(());
        };
        let peer = self.get_peer(entry.peer_handle)?;
        let _operation = peer.operations.lock().await;
        entry
            .channel
            .close()
            .await
            .map_err(|error| format!("Failed to close DataChannel: {error}"))
    }

    pub async fn close_peer(&self, peer_handle: u64) -> Result<(), String> {
        let peer = {
            let mut registry = self.lock_registry()?;
            registry
                .channels
                .retain(|_, channel| channel.peer_handle != peer_handle);
            registry.peers.remove(&peer_handle)
        };
        let Some(peer) = peer else {
            return Ok(());
        };
        let _operation = peer.operations.lock().await;
        peer.peer
            .close()
            .await
            .map_err(|error| format!("Failed to close peer connection: {error}"))
    }

    pub async fn shutdown_all(&self) -> Result<(), String> {
        let peers = {
            let mut registry = self.lock_registry()?;
            registry.channels.clear();
            registry
                .peers
                .drain()
                .map(|(_, entry)| entry)
                .collect::<Vec<_>>()
        };
        let mut closes = JoinSet::new();
        for peer in peers {
            closes.spawn(async move {
                let _operation = peer.operations.lock().await;
                peer.peer.close().await
            });
        }
        while closes.join_next().await.is_some() {}
        Ok(())
    }

    async fn register_channel(
        self: &Arc<Self>,
        peer_handle: u64,
        channel: Arc<dyn DataChannel>,
        emit_created_event: bool,
    ) -> Result<u64, String> {
        let handle = self.next_handle();
        let label = channel.label().await.unwrap_or_default();
        let protocol = channel.protocol().await.unwrap_or_default();
        let ordered = channel.ordered().await.unwrap_or(true);
        let open = channel
            .ready_state()
            .await
            .is_ok_and(|state| state == RTCDataChannelState::Open);

        self.lock_registry()?.channels.insert(
            handle,
            ChannelEntry {
                channel: Arc::clone(&channel),
                peer_handle,
            },
        );

        if emit_created_event {
            self.send_event(NativeEvent {
                kind: DATA_CHANNEL,
                peer_handle,
                channel_handle: handle,
                operation_handle: 0,
                text: Some(label),
                secondary_text: Some(protocol),
                number: i32::from(ordered) | (i32::from(open) << 1),
                data: None,
            });
        }

        tokio::spawn(poll_data_channel(
            Arc::downgrade(self),
            peer_handle,
            handle,
            channel,
        ));
        Ok(handle)
    }

    fn get_peer(&self, peer_handle: u64) -> Result<PeerOperation, String> {
        let registry = self.lock_registry()?;
        let entry = registry
            .peers
            .get(&peer_handle)
            .ok_or_else(|| format!("Unknown peer connection handle: {peer_handle}"))?;
        Ok(PeerOperation {
            peer: Arc::clone(&entry.peer),
            operations: Arc::clone(&entry.operations),
        })
    }

    fn get_channel(&self, channel_handle: u64) -> Result<(Arc<dyn DataChannel>, u64), String> {
        let registry = self.lock_registry()?;
        let entry = registry
            .channels
            .get(&channel_handle)
            .ok_or_else(|| format!("Unknown DataChannel handle: {channel_handle}"))?;
        Ok((Arc::clone(&entry.channel), entry.peer_handle))
    }

    fn lock_registry(&self) -> Result<std::sync::MutexGuard<'_, Registry>, String> {
        self.registry
            .lock()
            .map_err(|_| "Kestara WebRTC handle registry is poisoned".to_owned())
    }

    fn next_handle(&self) -> u64 {
        self.next_handle.fetch_add(1, Ordering::Relaxed)
    }
}

struct PeerOperation {
    peer: Arc<dyn NativePeerConnection>,
    operations: Arc<AsyncMutex<()>>,
}

struct EventHandler {
    peer_handle: u64,
    runtime: Weak<RuntimeState>,
}

#[async_trait]
impl PeerConnectionEventHandler for EventHandler {
    async fn on_ice_candidate(&self, event: RTCPeerConnectionIceEvent) {
        let Some(runtime) = self.runtime.upgrade() else {
            return;
        };
        let Ok(candidate) = event.candidate.to_json() else {
            return;
        };
        runtime.send_event(NativeEvent {
            kind: LOCAL_CANDIDATE,
            peer_handle: self.peer_handle,
            channel_handle: 0,
            operation_handle: 0,
            text: Some(candidate.candidate),
            secondary_text: candidate.sdp_mid,
            number: candidate.sdp_mline_index.map_or(-1, i32::from),
            data: None,
        });
    }

    async fn on_connection_state_change(&self, state: RTCPeerConnectionState) {
        if let Some(runtime) = self.runtime.upgrade() {
            runtime.send_event(NativeEvent::peer(
                PEER_STATE,
                self.peer_handle,
                peer_state_number(state),
            ));
        }
    }

    async fn on_ice_connection_state_change(&self, state: RTCIceConnectionState) {
        if let Some(runtime) = self.runtime.upgrade() {
            runtime.send_event(NativeEvent::peer(
                ICE_CONNECTION_STATE,
                self.peer_handle,
                ice_connection_state_number(state),
            ));
        }
    }

    async fn on_ice_gathering_state_change(&self, state: RTCIceGatheringState) {
        if let Some(runtime) = self.runtime.upgrade() {
            runtime.send_event(NativeEvent::peer(
                ICE_GATHERING_STATE,
                self.peer_handle,
                ice_gathering_state_number(state),
            ));
        }
    }

    async fn on_data_channel(&self, channel: Arc<dyn DataChannel>) {
        let Some(runtime) = self.runtime.upgrade() else {
            return;
        };
        let peer_handle = self.peer_handle;
        tokio::spawn(async move {
            let _ = runtime.register_channel(peer_handle, channel, true).await;
        });
    }
}

async fn poll_data_channel(
    runtime: Weak<RuntimeState>,
    peer_handle: u64,
    channel_handle: u64,
    channel: Arc<dyn DataChannel>,
) {
    while let Some(event) = channel.poll().await {
        let Some(runtime) = runtime.upgrade() else {
            return;
        };
        match event {
            DataChannelEvent::OnOpen => runtime.send_event(NativeEvent::channel(
                DATA_CHANNEL_OPEN,
                peer_handle,
                channel_handle,
            )),
            DataChannelEvent::OnError => runtime.send_event(NativeEvent {
                text: Some("The native DataChannel reported an error".to_owned()),
                ..NativeEvent::channel(DATA_CHANNEL_ERROR, peer_handle, channel_handle)
            }),
            DataChannelEvent::OnClosing => runtime.send_event(NativeEvent::channel(
                DATA_CHANNEL_CLOSING,
                peer_handle,
                channel_handle,
            )),
            DataChannelEvent::OnClose => {
                runtime.send_event(NativeEvent::channel(
                    DATA_CHANNEL_CLOSED,
                    peer_handle,
                    channel_handle,
                ));
                break;
            }
            DataChannelEvent::OnMessage(message) if message.is_string => {
                let text = String::from_utf8_lossy(&message.data).into_owned();
                runtime.send_event(NativeEvent {
                    text: Some(text),
                    ..NativeEvent::channel(DATA_CHANNEL_TEXT, peer_handle, channel_handle)
                });
            }
            DataChannelEvent::OnMessage(message) => runtime.send_event(NativeEvent {
                data: Some(message.data.to_vec()),
                ..NativeEvent::channel(DATA_CHANNEL_BINARY, peer_handle, channel_handle)
            }),
            DataChannelEvent::OnBufferedAmountLow | DataChannelEvent::OnBufferedAmountHigh => {}
        }
    }
    if let Some(runtime) = runtime.upgrade()
        && let Ok(mut registry) = runtime.registry.lock()
    {
        registry.channels.remove(&channel_handle);
    }
}

fn bind_addresses(network_types: &[NetworkType], port: u16) -> (Vec<String>, Vec<String>) {
    let mut udp = Vec::new();
    let mut tcp = Vec::new();
    for network_type in network_types {
        let (target, address) = match network_type {
            NetworkType::Unspecified => continue,
            NetworkType::Udp4 => (&mut udp, format!("0.0.0.0:{port}")),
            NetworkType::Udp6 => (&mut udp, format!("[::]:{port}")),
            NetworkType::Tcp4 => (&mut tcp, format!("0.0.0.0:{port}")),
            NetworkType::Tcp6 => (&mut tcp, format!("[::]:{port}")),
        };
        if !target.contains(&address) {
            target.push(address);
        }
    }
    (udp, tcp)
}

fn setting_engine(configuration: &PeerConfiguration) -> SettingEngine {
    let mut engine = SettingEngine::default();
    engine.set_dtls_cipher_suites(vec![
        CipherSuiteId::Tls_Ecdhe_Ecdsa_With_Aes_128_Gcm_Sha256,
        CipherSuiteId::Tls_Ecdhe_Ecdsa_With_ChaCha20_Poly1305_Sha256,
        CipherSuiteId::Tls_Ecdhe_Ecdsa_With_Aes_256_Cbc_Sha,
    ]);
    engine.set_ice_timeouts(
        configuration.ice.disconnected_timeout,
        configuration.ice.failed_timeout,
        configuration.ice.keep_alive_interval,
    );
    engine.set_ice_connection_attempts(
        configuration.ice.check_interval,
        configuration.ice.max_binding_requests,
    );
    engine.set_host_acceptance_min_wait(configuration.ice.host_acceptance_min_wait);
    engine.set_srflx_acceptance_min_wait(configuration.ice.server_reflexive_acceptance_min_wait);
    engine.set_prflx_acceptance_min_wait(configuration.ice.peer_reflexive_acceptance_min_wait);
    engine.set_relay_acceptance_min_wait(configuration.ice.relay_acceptance_min_wait);
    engine.set_network_types(configuration.ice.network_types.clone());
    engine.set_multicast_dns_mode(configuration.ice.mdns_mode);
    if configuration.ice.mdns_query_timeout.is_some() {
        engine.set_multicast_dns_timeout(configuration.ice.mdns_query_timeout);
    }
    engine.set_lite(configuration.ice.lite);
    engine.set_discard_local_candidates_during_ice_restart(
        configuration.ice.discard_local_candidates_on_restart,
    );
    if let Some((addresses, candidate_type)) = &configuration.ice.nat_mapping {
        engine.set_nat_1to1_ips(addresses.clone(), *candidate_type);
    }
    engine.set_sctp_max_message_size(SctpMaxMessageSize::Bounded(
        configuration.sctp.maximum_message_size,
    ));
    engine.set_sctp_max_receive_buffer_size(configuration.sctp.receive_buffer_size);
    engine
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
    use ice::network_type::NetworkType;

    #[test]
    fn creates_bind_addresses_for_selected_network_types() {
        let (udp, tcp) = super::bind_addresses(
            &[NetworkType::Udp4, NetworkType::Udp6, NetworkType::Tcp4],
            10_000,
        );
        assert_eq!(udp, ["0.0.0.0:10000", "[::]:10000"]);
        assert_eq!(tcp, ["0.0.0.0:10000"]);
    }
}

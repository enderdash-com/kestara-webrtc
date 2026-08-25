use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, Weak};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use async_trait::async_trait;
use bytes::BytesMut;
use crossbeam_channel::Sender;
use ice::mdns::MulticastDnsMode;
use ice::network_type::NetworkType;
use rtc::peer_connection::configuration::setting_engine::SctpMaxMessageSize;
use rtc::peer_connection::transport::RTCDtlsRole;
use rtc::statistics::stats::ice_candidate::RTCIceCandidateStats;
use tokio::sync::Mutex as AsyncMutex;
use tokio::task::JoinSet;
use webrtc::data_channel::{
    DataChannel, DataChannelEvent, RTCDataChannelInit, RTCDataChannelState,
};
use webrtc::error::Error;
use webrtc::peer_connection::{
    CipherSuiteId, PeerConnection as NativePeerConnection, PeerConnectionBuilder,
    PeerConnectionEventHandler, RTCCertificate, RTCConfigurationBuilder, RTCIceCandidateInit,
    RTCIceCandidateType, RTCIceConnectionState, RTCIceGatheringState, RTCIceServer,
    RTCIceTransportPolicy, RTCPeerConnectionIceEvent, RTCPeerConnectionState,
    RTCSessionDescription, RTCStatsReport, RTCStatsReportEntry, SettingEngine, SharedSocketMux,
    StatsSelector,
};
use webrtc::runtime::{Runtime, TokioRuntime};

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
    pub dtls: DtlsConfiguration,
    pub transport: TransportConfiguration,
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
    pub include_loopback_candidate: bool,
    pub mdns_local_name: Option<String>,
    pub mdns_local_address: Option<IpAddr>,
    pub credentials: Option<(String, String)>,
}

#[derive(Debug)]
pub struct DtlsConfiguration {
    pub answering_role: Option<RTCDtlsRole>,
    pub media_level_fingerprints: bool,
    pub replay_protection_window: usize,
    pub cipher_suites: Vec<CipherSuiteId>,
}

#[derive(Debug)]
pub struct TransportConfiguration {
    pub udp_bind_addresses: Vec<String>,
    pub tcp_bind_addresses: Vec<String>,
    pub receive_mtu: usize,
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
    pub(crate) certificate: Arc<Mutex<RTCCertificate>>,
    runtime: Arc<TokioRuntime>,
    socket_mux: Option<Arc<SharedSocketMux>>,
}

impl RuntimeState {
    pub fn new(
        events: Sender<NativeEvent>,
        certificate: Arc<Mutex<RTCCertificate>>,
        runtime: Arc<TokioRuntime>,
        socket_mux: Option<Arc<SharedSocketMux>>,
    ) -> Self {
        Self {
            registry: Mutex::new(Registry::default()),
            events,
            next_handle: AtomicU64::new(1),
            certificate,
            runtime,
            socket_mux,
        }
    }

    pub fn send_event(&self, event: NativeEvent) {
        let _ = self.events.send(event);
    }

    fn lock_certificate(&self) -> Result<std::sync::MutexGuard<'_, RTCCertificate>, String> {
        self.certificate
            .lock()
            .map_err(|_| "The runtime DTLS certificate lock is poisoned".to_owned())
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
            let (udp_addresses, tcp_addresses) = bind_addresses(
                &configuration.transport,
                &configuration.ice.network_types,
                port,
            );
            let rtc_configuration = RTCConfigurationBuilder::new()
                .with_ice_servers(configuration.ice_servers.clone())
                .with_ice_transport_policy(ice_policy)
                .with_certificates(vec![self.lock_certificate()?.clone()])
                .with_ice_candidate_pool_size(configuration.ice.candidate_pool_size)
                .build();
            let mut builder = PeerConnectionBuilder::new()
                .with_configuration(rtc_configuration)
                .with_setting_engine(setting_engine.clone())
                .with_handler(handler.clone())
                .with_runtime(Arc::clone(&self.runtime) as Arc<dyn Runtime>)
                .with_udp_addrs(udp_addresses)
                .with_tcp_addrs(tcp_addresses)
                .with_dedicated_reactor_thread(true)
                .with_data_channel_send_buffer_limit(configuration.sctp.send_buffer_limit);
            if let Some(socket_mux) = &self.socket_mux {
                builder = builder.with_socket_mux(Arc::clone(socket_mux));
            }
            match Box::pin(builder.build()).await {
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

    pub async fn try_send_text(&self, channel_handle: u64, text: String) -> Result<bool, String> {
        let (channel, _) = self.get_channel(channel_handle)?;
        match channel.try_send_text(&text).await {
            Ok(()) => Ok(true),
            Err(Error::ErrSendBufferFull) => Ok(false),
            Err(error) => Err(format!("Failed to send DataChannel text: {error}")),
        }
    }

    pub async fn try_send_binary(
        &self,
        channel_handle: u64,
        data: Vec<u8>,
    ) -> Result<bool, String> {
        let (channel, _) = self.get_channel(channel_handle)?;
        match channel.try_send(BytesMut::from(data.as_slice())).await {
            Ok(()) => Ok(true),
            Err(Error::ErrSendBufferFull) => Ok(false),
            Err(error) => Err(format!("Failed to send DataChannel data: {error}")),
        }
    }

    pub async fn data_channel_writable(&self, channel_handle: u64) -> Result<(), String> {
        let (channel, _) = self.get_channel(channel_handle)?;
        channel
            .writable()
            .await
            .map_err(|error| format!("DataChannel cannot become writable: {error}"))
    }

    pub async fn data_channel_outstanding_bytes(&self, channel_handle: u64) -> Result<u64, String> {
        let (channel, _) = self.get_channel(channel_handle)?;
        channel
            .outstanding_bytes()
            .await
            .map(|value| u64::try_from(value).unwrap_or(u64::MAX))
            .map_err(|error| format!("Failed to read DataChannel outstanding bytes: {error}"))
    }

    pub async fn set_data_channel_thresholds(
        &self,
        channel_handle: u64,
        low: u32,
        high: u32,
    ) -> Result<(), String> {
        let (channel, _) = self.get_channel(channel_handle)?;
        channel
            .set_buffered_amount_low_threshold(low)
            .await
            .map_err(|error| format!("Failed to set DataChannel low threshold: {error}"))?;
        channel
            .set_buffered_amount_high_threshold(high)
            .await
            .map_err(|error| format!("Failed to set DataChannel high threshold: {error}"))?;
        Ok(())
    }

    pub async fn get_stats(&self, peer_handle: u64) -> Result<Vec<u8>, String> {
        let peer = self.get_peer(peer_handle)?;
        let _operation = peer.operations.lock().await;
        let report = peer
            .peer
            .get_stats(Instant::now(), StatsSelector::None)
            .await;
        encode_stats(&report)
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
            DataChannelEvent::OnBufferedAmountLow => runtime.send_event(NativeEvent::channel(
                crate::events::DATA_CHANNEL_BUFFERED_AMOUNT_LOW,
                peer_handle,
                channel_handle,
            )),
            DataChannelEvent::OnBufferedAmountHigh => runtime.send_event(NativeEvent::channel(
                crate::events::DATA_CHANNEL_BUFFERED_AMOUNT_HIGH,
                peer_handle,
                channel_handle,
            )),
        }
    }
    if let Some(runtime) = runtime.upgrade()
        && let Ok(mut registry) = runtime.registry.lock()
    {
        registry.channels.remove(&channel_handle);
    }
}

fn bind_addresses(
    configuration: &TransportConfiguration,
    network_types: &[NetworkType],
    port: u16,
) -> (Vec<String>, Vec<String>) {
    if configuration.udp_bind_addresses.is_empty() && configuration.tcp_bind_addresses.is_empty() {
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
        return (udp, tcp);
    }
    let append_port = |address: &String| match address.parse::<IpAddr>() {
        Ok(IpAddr::V4(_)) => format!("{address}:{port}"),
        Ok(IpAddr::V6(_)) => format!("[{address}]:{port}"),
        Err(_) => address.clone(),
    };
    (
        configuration
            .udp_bind_addresses
            .iter()
            .map(append_port)
            .collect(),
        configuration
            .tcp_bind_addresses
            .iter()
            .map(append_port)
            .collect(),
    )
}

fn setting_engine(configuration: &PeerConfiguration) -> SettingEngine {
    let mut engine = SettingEngine::default();
    engine.set_dtls_cipher_suites(configuration.dtls.cipher_suites.clone());
    if let Some(role) = configuration.dtls.answering_role {
        let _ = engine.set_answering_dtls_role(role);
    }
    engine.set_sdp_media_level_fingerprints(configuration.dtls.media_level_fingerprints);
    engine.set_dtls_replay_protection_window(configuration.dtls.replay_protection_window);
    engine.set_receive_mtu(configuration.transport.receive_mtu);
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
    engine.set_include_loopback_candidate(configuration.ice.include_loopback_candidate);
    if let Some(name) = &configuration.ice.mdns_local_name {
        engine.set_multicast_dns_local_name(name.clone());
    }
    engine.set_multicast_dns_local_ip(configuration.ice.mdns_local_address);
    if let Some((username_fragment, password)) = &configuration.ice.credentials {
        engine.set_ice_credentials(username_fragment.clone(), password.clone());
    }
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

fn encode_stats(report: &RTCStatsReport) -> Result<Vec<u8>, String> {
    let peer = report
        .peer_connection()
        .ok_or_else(|| "The native stats report has no peer connection entry".to_owned())?;
    let transport = report
        .transport()
        .ok_or_else(|| "The native stats report has no transport entry".to_owned())?;
    let mut output = Vec::with_capacity(512);
    write_u32(&mut output, 1);
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| format!("The system clock is before the Unix epoch: {error}"))?
        .as_millis();
    write_u64(&mut output, u64::try_from(timestamp).unwrap_or(u64::MAX));
    write_u64(&mut output, u64::from(peer.data_channels_opened));
    write_u64(&mut output, u64::from(peer.data_channels_closed));
    write_u64(&mut output, transport.packets_sent);
    write_u64(&mut output, transport.packets_received);
    write_u64(&mut output, transport.bytes_sent);
    write_u64(&mut output, transport.bytes_received);
    write_string(&mut output, &format!("{:?}", transport.ice_role))?;
    write_string(&mut output, &format!("{:?}", transport.ice_state))?;
    write_string(&mut output, &format!("{:?}", transport.dtls_role))?;
    write_string(&mut output, &format!("{:?}", transport.dtls_state))?;
    write_string(&mut output, &transport.tls_version)?;
    write_string(&mut output, &transport.dtls_cipher)?;
    write_u32(&mut output, transport.selected_candidate_pair_changes);

    if let Some(RTCStatsReportEntry::IceCandidatePair(pair)) =
        report.get(&transport.selected_candidate_pair_id)
    {
        let local = candidate(report, &pair.local_candidate_id)?;
        let remote = candidate(report, &pair.remote_candidate_id)?;
        output.push(1);
        write_string(&mut output, &pair.stats.id)?;
        write_candidate(&mut output, local)?;
        write_candidate(&mut output, remote)?;
        write_u64(&mut output, pair.packets_sent);
        write_u64(&mut output, pair.packets_received);
        write_u64(&mut output, pair.bytes_sent);
        write_u64(&mut output, pair.bytes_received);
        write_f64(&mut output, pair.current_round_trip_time);
        write_f64(&mut output, pair.total_round_trip_time);
        write_u64(&mut output, pair.requests_sent);
        write_u64(&mut output, pair.requests_received);
        write_u64(&mut output, pair.responses_sent);
        write_u64(&mut output, pair.responses_received);
        write_string(&mut output, &format!("{:?}", pair.state))?;
        output.push(u8::from(pair.nominated));
    } else {
        output.push(0);
    }

    let channels = report
        .iter()
        .filter_map(|entry| match entry {
            RTCStatsReportEntry::DataChannel(channel) => Some(channel),
            _ => None,
        })
        .collect::<Vec<_>>();
    write_u32(
        &mut output,
        u32::try_from(channels.len())
            .map_err(|_| "Too many DataChannel stats entries".to_owned())?,
    );
    for channel in channels {
        output.extend_from_slice(&channel.data_channel_identifier.to_be_bytes());
        write_string(&mut output, &channel.label)?;
        write_string(&mut output, &channel.protocol)?;
        write_string(&mut output, &format!("{:?}", channel.state))?;
        write_u32(&mut output, channel.messages_sent);
        write_u64(&mut output, channel.bytes_sent);
        write_u32(&mut output, channel.messages_received);
        write_u64(&mut output, channel.bytes_received);
    }
    Ok(output)
}

fn candidate<'a>(report: &'a RTCStatsReport, id: &str) -> Result<&'a RTCIceCandidateStats, String> {
    if let Some(
        RTCStatsReportEntry::LocalCandidate(candidate)
        | RTCStatsReportEntry::RemoteCandidate(candidate),
    ) = report.get(id)
    {
        return Ok(candidate);
    }
    report
        .iter()
        .find_map(|entry| match entry {
            RTCStatsReportEntry::LocalCandidate(candidate)
            | RTCStatsReportEntry::RemoteCandidate(candidate)
                if candidate.stats.id.ends_with(id) =>
            {
                Some(candidate)
            }
            _ => None,
        })
        .ok_or_else(|| format!("The native stats report is missing ICE candidate {id}"))
}

fn write_candidate(output: &mut Vec<u8>, candidate: &RTCIceCandidateStats) -> Result<(), String> {
    write_string(output, &candidate.stats.id)?;
    write_string(output, candidate.address.as_deref().unwrap_or_default())?;
    output.extend_from_slice(&candidate.port.to_be_bytes());
    write_string(output, &candidate.protocol)?;
    write_string(output, &format!("{:?}", candidate.candidate_type))?;
    write_u32(output, u32::from(candidate.priority));
    write_string(output, &candidate.url)?;
    write_string(output, &format!("{:?}", candidate.relay_protocol))?;
    write_string(output, &candidate.foundation)?;
    write_string(output, &candidate.related_address)?;
    output.extend_from_slice(&candidate.related_port.to_be_bytes());
    write_string(output, &candidate.username_fragment)?;
    write_string(output, &format!("{:?}", candidate.tcp_type))
}

fn write_string(output: &mut Vec<u8>, value: &str) -> Result<(), String> {
    let size =
        u32::try_from(value.len()).map_err(|_| "Native stats string is too large".to_owned())?;
    write_u32(output, size);
    output.extend_from_slice(value.as_bytes());
    Ok(())
}

fn write_u32(output: &mut Vec<u8>, value: u32) {
    output.extend_from_slice(&value.to_be_bytes());
}

fn write_u64(output: &mut Vec<u8>, value: u64) {
    output.extend_from_slice(&value.to_be_bytes());
}

fn write_f64(output: &mut Vec<u8>, value: f64) {
    output.extend_from_slice(&value.to_be_bytes());
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
        let transport = super::TransportConfiguration {
            udp_bind_addresses: Vec::new(),
            tcp_bind_addresses: Vec::new(),
            receive_mtu: 0,
        };
        let (udp, tcp) = super::bind_addresses(
            &transport,
            &[NetworkType::Udp4, NetworkType::Udp6, NetworkType::Tcp4],
            10_000,
        );
        assert_eq!(udp, ["0.0.0.0:10000", "[::]:10000"]);
        assert_eq!(tcp, ["0.0.0.0:10000"]);
    }
}

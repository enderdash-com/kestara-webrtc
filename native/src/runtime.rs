use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, Mutex};
use std::thread::{self, JoinHandle as ThreadJoinHandle};
use std::time::Duration;

use crossbeam_channel::{Receiver, Sender, bounded, unbounded};
use rcgen::{KeyPair, PKCS_ECDSA_P256_SHA256};
use tokio::runtime::{Builder, Runtime};
use tokio::sync::OwnedSemaphorePermit;
use tokio::task::JoinHandle;
use webrtc::peer_connection::{
    RTCCertificate, RTCIceCandidateInit, RTCIceServer, RTCSessionDescription, SharedSocketMux,
};
use webrtc::runtime::{Runtime as WebRtcRuntime, TokioRuntime};

use crate::events::{self, NativeEvent, OperationValue};
use crate::registry::{DataChannelConfiguration, PeerConfiguration, RuntimeState};

pub enum Command {
    CreatePeer {
        operation_handle: u64,
        timeout: Duration,
        configuration: PeerConfiguration,
    },
    CreateDescription {
        operation_handle: u64,
        timeout: Duration,
        peer_handle: u64,
        answer: bool,
    },
    SetLocalDescription {
        operation_handle: u64,
        timeout: Duration,
        peer_handle: u64,
        description: RTCSessionDescription,
    },
    SetRemoteDescription {
        operation_handle: u64,
        timeout: Duration,
        peer_handle: u64,
        description: RTCSessionDescription,
    },
    AddIceCandidate {
        operation_handle: u64,
        timeout: Duration,
        peer_handle: u64,
        candidate: RTCIceCandidateInit,
    },
    RestartIce {
        operation_handle: u64,
        timeout: Duration,
        peer_handle: u64,
    },
    SetConfiguration {
        operation_handle: u64,
        timeout: Duration,
        peer_handle: u64,
        ice_servers: Vec<RTCIceServer>,
        relay_only: bool,
    },
    CreateDataChannel {
        operation_handle: u64,
        timeout: Duration,
        peer_handle: u64,
        configuration: DataChannelConfiguration,
    },
    SendText {
        operation_handle: u64,
        timeout: Duration,
        channel_handle: u64,
        text: String,
    },
    SendBinary {
        operation_handle: u64,
        timeout: Duration,
        channel_handle: u64,
        data: Vec<u8>,
    },
    TrySendText {
        operation_handle: u64,
        timeout: Duration,
        channel_handle: u64,
        text: String,
    },
    TrySendBinary {
        operation_handle: u64,
        timeout: Duration,
        channel_handle: u64,
        data: Vec<u8>,
    },
    DataChannelWritable {
        operation_handle: u64,
        timeout: Duration,
        channel_handle: u64,
    },
    DataChannelOutstandingBytes {
        operation_handle: u64,
        timeout: Duration,
        channel_handle: u64,
    },
    SetDataChannelThresholds {
        operation_handle: u64,
        timeout: Duration,
        channel_handle: u64,
        low: u32,
        high: u32,
    },
    GetStats {
        operation_handle: u64,
        timeout: Duration,
        peer_handle: u64,
    },
    CloseDataChannel {
        operation_handle: u64,
        timeout: Duration,
        channel_handle: u64,
    },
    ClosePeer {
        operation_handle: u64,
        timeout: Duration,
        peer_handle: u64,
    },
    RotateCertificate {
        operation_handle: u64,
        timeout: Duration,
        pem: Option<String>,
    },
    Shutdown {
        operation_handle: u64,
        timeout: Duration,
    },
}

impl Command {
    fn operation_handle(&self) -> u64 {
        match self {
            Self::CreatePeer {
                operation_handle, ..
            }
            | Self::CreateDescription {
                operation_handle, ..
            }
            | Self::SetLocalDescription {
                operation_handle, ..
            }
            | Self::SetRemoteDescription {
                operation_handle, ..
            }
            | Self::AddIceCandidate {
                operation_handle, ..
            }
            | Self::RestartIce {
                operation_handle, ..
            }
            | Self::SetConfiguration {
                operation_handle, ..
            }
            | Self::CreateDataChannel {
                operation_handle, ..
            }
            | Self::SendText {
                operation_handle, ..
            }
            | Self::SendBinary {
                operation_handle, ..
            }
            | Self::TrySendText {
                operation_handle, ..
            }
            | Self::TrySendBinary {
                operation_handle, ..
            }
            | Self::DataChannelWritable {
                operation_handle, ..
            }
            | Self::DataChannelOutstandingBytes {
                operation_handle, ..
            }
            | Self::SetDataChannelThresholds {
                operation_handle, ..
            }
            | Self::GetStats {
                operation_handle, ..
            }
            | Self::CloseDataChannel {
                operation_handle, ..
            }
            | Self::ClosePeer {
                operation_handle, ..
            }
            | Self::RotateCertificate {
                operation_handle, ..
            }
            | Self::Shutdown {
                operation_handle, ..
            } => *operation_handle,
        }
    }

    fn timeout(&self) -> Duration {
        match self {
            Self::CreatePeer { timeout, .. }
            | Self::CreateDescription { timeout, .. }
            | Self::SetLocalDescription { timeout, .. }
            | Self::SetRemoteDescription { timeout, .. }
            | Self::AddIceCandidate { timeout, .. }
            | Self::RestartIce { timeout, .. }
            | Self::SetConfiguration { timeout, .. }
            | Self::CreateDataChannel { timeout, .. }
            | Self::SendText { timeout, .. }
            | Self::SendBinary { timeout, .. }
            | Self::TrySendText { timeout, .. }
            | Self::TrySendBinary { timeout, .. }
            | Self::DataChannelWritable { timeout, .. }
            | Self::DataChannelOutstandingBytes { timeout, .. }
            | Self::SetDataChannelThresholds { timeout, .. }
            | Self::GetStats { timeout, .. }
            | Self::CloseDataChannel { timeout, .. }
            | Self::ClosePeer { timeout, .. }
            | Self::RotateCertificate { timeout, .. }
            | Self::Shutdown { timeout, .. } => *timeout,
        }
    }
}

struct RuntimeController {
    commands: Sender<Command>,
    events: Receiver<NativeEvent>,
    event_sender: Sender<NativeEvent>,
    thread: Mutex<Option<ThreadJoinHandle<()>>>,
    certificate: Arc<Mutex<RTCCertificate>>,
    buffers: Mutex<HashMap<u64, NativeBufferEntry>>,
}

struct NativeBufferEntry {
    data: Vec<u8>,
    _delivery_permit: Option<OwnedSemaphorePermit>,
}

#[derive(Default)]
pub struct NativeBufferView {
    pub handle: u64,
    pub address: *mut u8,
    pub length: usize,
}

static RUNTIMES: LazyLock<Mutex<HashMap<u64, Arc<RuntimeController>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));
static NEXT_RUNTIME_HANDLE: AtomicU64 = AtomicU64::new(1);
static NEXT_BUFFER_HANDLE: AtomicU64 = AtomicU64::new(1);

pub struct RuntimeConfiguration {
    pub worker_threads: usize,
    pub reactor_threads: usize,
    pub certificate_pem: Option<String>,
    pub shared_udp_addresses: Vec<String>,
    pub shared_tcp_addresses: Vec<String>,
    pub shared_min_port: u16,
    pub shared_max_port: u16,
}

struct RuntimeThreadConfiguration {
    worker_threads: usize,
    reactor_threads: usize,
    shared_udp_addresses: Vec<String>,
    shared_tcp_addresses: Vec<String>,
    shared_min_port: u16,
    shared_max_port: u16,
}

pub fn create(configuration: RuntimeConfiguration) -> Result<u64, String> {
    let RuntimeConfiguration {
        worker_threads,
        reactor_threads,
        certificate_pem,
        shared_udp_addresses,
        shared_tcp_addresses,
        shared_min_port,
        shared_max_port,
    } = configuration;
    if worker_threads == 0 {
        return Err("The WebRTC runtime must have at least one worker thread".to_owned());
    }
    let certificate = Arc::new(Mutex::new(match certificate_pem {
        Some(pem) => import_certificate(&pem)?,
        None => generate_certificate()?,
    }));
    let runtime_handle = NEXT_RUNTIME_HANDLE.fetch_add(1, Ordering::Relaxed);
    let (command_sender, command_receiver) = unbounded();
    let (event_sender, event_receiver) = unbounded();
    let (ready_sender, ready_receiver) = bounded(1);
    let runtime_event_sender = event_sender.clone();
    let runtime_certificate = Arc::clone(&certificate);
    let thread_configuration = RuntimeThreadConfiguration {
        worker_threads,
        reactor_threads,
        shared_udp_addresses,
        shared_tcp_addresses,
        shared_min_port,
        shared_max_port,
    };
    let thread = thread::Builder::new()
        .name(format!("kestara-webrtc-control-{runtime_handle}"))
        .spawn(move || {
            run_runtime(
                runtime_handle,
                &command_receiver,
                runtime_event_sender,
                &ready_sender,
                runtime_certificate,
                &thread_configuration,
            );
        })
        .map_err(|error| format!("Failed to start the WebRTC runtime controller: {error}"))?;

    match ready_receiver.recv() {
        Ok(Ok(())) => {}
        Ok(Err(error)) => {
            let _ = thread.join();
            return Err(error);
        }
        Err(error) => {
            let _ = thread.join();
            return Err(format!(
                "The WebRTC runtime controller stopped during startup: {error}"
            ));
        }
    }

    let controller = Arc::new(RuntimeController {
        commands: command_sender,
        events: event_receiver,
        event_sender,
        thread: Mutex::new(Some(thread)),
        certificate,
        buffers: Mutex::new(HashMap::new()),
    });
    lock_runtimes()?.insert(runtime_handle, controller);
    Ok(runtime_handle)
}

pub fn certificate_fingerprint(runtime_handle: u64) -> Result<String, String> {
    let controller = get_runtime(runtime_handle)?;
    let certificate = lock_certificate(&controller.certificate)?;
    certificate_fingerprint_value(&certificate)
}

pub fn certificate_pem(runtime_handle: u64) -> Result<String, String> {
    let controller = get_runtime(runtime_handle)?;
    let certificate = lock_certificate(&controller.certificate)?;
    Ok(certificate.serialize_pem())
}

pub fn allocate_buffer(runtime_handle: u64, capacity: usize) -> Result<NativeBufferView, String> {
    register_buffer(runtime_handle, vec![0; capacity], None)
}

pub fn register_delivery_buffer(
    runtime_handle: u64,
    data: Option<Vec<u8>>,
    permit: OwnedSemaphorePermit,
) -> Result<NativeBufferView, String> {
    register_buffer(runtime_handle, data.unwrap_or_default(), Some(permit))
}

pub fn release_buffer(runtime_handle: u64, buffer_handle: u64) -> Result<(), String> {
    let controller = get_runtime(runtime_handle)?;
    controller
        .buffers
        .lock()
        .map_err(|_| "The native buffer registry is poisoned".to_owned())?
        .remove(&buffer_handle);
    Ok(())
}

pub fn take_buffer(
    runtime_handle: u64,
    buffer_handle: u64,
    offset: usize,
    length: usize,
) -> Result<Vec<u8>, String> {
    let controller = get_runtime(runtime_handle)?;
    let mut entry = controller
        .buffers
        .lock()
        .map_err(|_| "The native buffer registry is poisoned".to_owned())?
        .remove(&buffer_handle)
        .ok_or_else(|| format!("Unknown or consumed native buffer: {buffer_handle}"))?;
    let end = offset
        .checked_add(length)
        .filter(|end| *end <= entry.data.len())
        .ok_or_else(|| "Native buffer slice is outside its allocation".to_owned())?;
    if offset != 0 && length != 0 {
        entry.data.copy_within(offset..end, 0);
    }
    entry.data.truncate(length);
    Ok(entry.data)
}

fn register_buffer(
    runtime_handle: u64,
    mut data: Vec<u8>,
    delivery_permit: Option<OwnedSemaphorePermit>,
) -> Result<NativeBufferView, String> {
    let controller = get_runtime(runtime_handle)?;
    let handle = NEXT_BUFFER_HANDLE.fetch_add(1, Ordering::Relaxed);
    let address = data.as_mut_ptr();
    let length = data.len();
    controller
        .buffers
        .lock()
        .map_err(|_| "The native buffer registry is poisoned".to_owned())?
        .insert(
            handle,
            NativeBufferEntry {
                data,
                _delivery_permit: delivery_permit,
            },
        );
    Ok(NativeBufferView {
        handle,
        address,
        length,
    })
}

pub fn submit(runtime_handle: u64, command: Command) -> Result<(), String> {
    let controller = get_runtime(runtime_handle)?;
    controller
        .commands
        .send(command)
        .map_err(|_| "The WebRTC runtime is shutting down".to_owned())
}

pub fn complete_error(
    runtime_handle: u64,
    operation_handle: u64,
    error: String,
) -> Result<(), String> {
    let controller = get_runtime(runtime_handle)?;
    controller
        .event_sender
        .send(NativeEvent::operation(operation_handle, Err(error)))
        .map_err(|_| "The WebRTC runtime event queue is closed".to_owned())
}

pub fn poll(runtime_handle: u64, timeout: Duration) -> Result<Option<NativeEvent>, String> {
    let controller = get_runtime(runtime_handle)?;
    Ok(events::poll(&controller.events, timeout))
}

pub fn wake(runtime_handle: u64) -> Result<(), String> {
    let controller = get_runtime(runtime_handle)?;
    events::wake(&controller.event_sender);
    Ok(())
}

pub fn release(runtime_handle: u64) -> Result<(), String> {
    let controller = lock_runtimes()?.remove(&runtime_handle);
    let Some(controller) = controller else {
        return Ok(());
    };
    let _ = controller.commands.send(Command::Shutdown {
        operation_handle: 0,
        timeout: Duration::from_secs(2),
    });
    let thread = controller
        .thread
        .lock()
        .map_err(|_| "The WebRTC runtime thread lock is poisoned".to_owned())?
        .take();
    if let Some(thread) = thread {
        thread
            .join()
            .map_err(|_| "The WebRTC runtime controller panicked during shutdown".to_owned())?;
    }
    Ok(())
}

fn get_runtime(runtime_handle: u64) -> Result<Arc<RuntimeController>, String> {
    lock_runtimes()?
        .get(&runtime_handle)
        .cloned()
        .ok_or_else(|| format!("Unknown WebRTC runtime handle: {runtime_handle}"))
}

fn lock_runtimes()
-> Result<std::sync::MutexGuard<'static, HashMap<u64, Arc<RuntimeController>>>, String> {
    RUNTIMES
        .lock()
        .map_err(|_| "The WebRTC runtime registry is poisoned".to_owned())
}

fn run_runtime(
    runtime_handle: u64,
    commands: &Receiver<Command>,
    events: Sender<NativeEvent>,
    ready: &Sender<Result<(), String>>,
    certificate: Arc<Mutex<RTCCertificate>>,
    configuration: &RuntimeThreadConfiguration,
) {
    let runtime = match build_runtime(runtime_handle, configuration.worker_threads) {
        Ok(runtime) => runtime,
        Err(error) => {
            let _ = ready.send(Err(error));
            return;
        }
    };
    let webrtc_runtime = Arc::new(TokioRuntime::with_reactor_pool_size(
        configuration.reactor_threads,
    ));
    let socket_mux = match runtime.block_on(async {
        create_socket_mux(
            &webrtc_runtime,
            &configuration.shared_udp_addresses,
            &configuration.shared_tcp_addresses,
            configuration.shared_min_port,
            configuration.shared_max_port,
        )
    }) {
        Ok(socket_mux) => socket_mux,
        Err(error) => {
            let _ = ready.send(Err(error));
            return;
        }
    };
    let state = Arc::new(RuntimeState::new(
        events,
        certificate,
        webrtc_runtime,
        socket_mux,
    ));
    let _ = ready.send(Ok(()));
    let mut operations = Vec::new();

    while let Ok(command) = commands.recv() {
        operations.retain(|operation: &JoinHandle<()>| !operation.is_finished());
        if let Command::Shutdown {
            operation_handle,
            timeout,
        } = command
        {
            let graceful_timeout = timeout / 2;
            let result = runtime.block_on(async {
                tokio::time::timeout(graceful_timeout, async {
                    for operation in &mut operations {
                        let _ = operation.await;
                    }
                    state.shutdown_all().await
                })
                .await
                .map_err(|_| "WebRTC runtime shutdown timed out".to_owned())?
            });
            if result.is_err() {
                for operation in operations {
                    operation.abort();
                }
            }
            runtime.shutdown_timeout(timeout.saturating_sub(graceful_timeout));
            state.send_event(NativeEvent::operation(
                operation_handle,
                result.map(|()| OperationValue::default()),
            ));
            return;
        }
        operations.push(dispatch(&runtime, Arc::clone(&state), command));
    }

    let timeout = Duration::from_secs(2);
    let _ = runtime.block_on(async { tokio::time::timeout(timeout, state.shutdown_all()).await });
    runtime.shutdown_timeout(timeout);
}

fn build_runtime(runtime_handle: u64, worker_threads: usize) -> Result<Runtime, String> {
    Builder::new_multi_thread()
        .worker_threads(worker_threads)
        .thread_name(format!("kestara-webrtc-{runtime_handle}"))
        .enable_all()
        .build()
        .map_err(|error| format!("Failed to start the WebRTC runtime: {error}"))
}

fn generate_certificate() -> Result<RTCCertificate, String> {
    let key_pair = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256)
        .map_err(|error| format!("Failed to generate the runtime DTLS key: {error}"))?;
    RTCCertificate::from_key_pair(key_pair)
        .map_err(|error| format!("Failed to generate the runtime DTLS certificate: {error}"))
}

fn import_certificate(pem: &str) -> Result<RTCCertificate, String> {
    RTCCertificate::from_pem(pem)
        .map_err(|error| format!("Failed to import the runtime DTLS certificate: {error}"))
}

fn certificate_fingerprint_value(certificate: &RTCCertificate) -> Result<String, String> {
    certificate
        .get_fingerprints()
        .into_iter()
        .next()
        .map(|fingerprint| fingerprint.value)
        .ok_or_else(|| "The runtime DTLS certificate has no fingerprint".to_owned())
}

fn lock_certificate(
    certificate: &Mutex<RTCCertificate>,
) -> Result<std::sync::MutexGuard<'_, RTCCertificate>, String> {
    certificate
        .lock()
        .map_err(|_| "The runtime DTLS certificate lock is poisoned".to_owned())
}

fn create_socket_mux(
    runtime: &Arc<TokioRuntime>,
    udp_hosts: &[String],
    tcp_hosts: &[String],
    min_port: u16,
    max_port: u16,
) -> Result<Option<Arc<SharedSocketMux>>, String> {
    if udp_hosts.is_empty() && tcp_hosts.is_empty() {
        return Ok(None);
    }
    let ports: Vec<u16> = if min_port == 0 {
        vec![0]
    } else {
        (min_port..=max_port).collect()
    };
    let mut last_error = None;
    for port in ports {
        let udp = socket_addresses(udp_hosts, port)?;
        let tcp = socket_addresses(tcp_hosts, port)?;
        match SharedSocketMux::bind(Arc::clone(runtime) as Arc<dyn WebRtcRuntime>, &udp, &tcp) {
            Ok(mux) => return Ok(Some(mux)),
            Err(error) => last_error = Some(error.to_string()),
        }
    }
    Err(format!(
        "No shared transport port is available: {}",
        last_error.unwrap_or_else(|| "unknown bind error".to_owned())
    ))
}

fn socket_addresses(hosts: &[String], port: u16) -> Result<Vec<SocketAddr>, String> {
    hosts
        .iter()
        .map(|host| {
            host.parse::<IpAddr>()
                .map(|ip| SocketAddr::new(ip, port))
                .map_err(|error| format!("Invalid shared bind address {host}: {error}"))
        })
        .collect()
}

fn dispatch(runtime: &Runtime, state: Arc<RuntimeState>, command: Command) -> JoinHandle<()> {
    let operation_handle = command.operation_handle();
    let timeout = command.timeout();
    runtime.spawn(async move {
        let result = Box::pin(tokio::time::timeout(
            timeout,
            execute(Arc::clone(&state), command),
        ))
        .await
        .map_err(|_| "WebRTC operation timed out".to_owned())
        .and_then(|result| result);
        state.send_event(NativeEvent::operation(operation_handle, result));
    })
}

#[allow(clippy::too_many_lines)]
async fn execute(state: Arc<RuntimeState>, command: Command) -> Result<OperationValue, String> {
    match command {
        Command::CreatePeer { configuration, .. } => {
            let peer_handle = Box::pin(state.create_peer(configuration)).await?;
            Ok(OperationValue {
                peer_handle,
                ..OperationValue::default()
            })
        }
        Command::CreateDescription {
            peer_handle,
            answer,
            ..
        } => {
            let text = state.create_description(peer_handle, answer).await?;
            Ok(OperationValue {
                text: Some(text),
                ..OperationValue::default()
            })
        }
        Command::SetLocalDescription {
            peer_handle,
            description,
            ..
        } => {
            state
                .set_local_description(peer_handle, description)
                .await?;
            Ok(OperationValue::default())
        }
        Command::SetRemoteDescription {
            peer_handle,
            description,
            ..
        } => {
            state
                .set_remote_description(peer_handle, description)
                .await?;
            Ok(OperationValue::default())
        }
        Command::AddIceCandidate {
            peer_handle,
            candidate,
            ..
        } => {
            state.add_ice_candidate(peer_handle, candidate).await?;
            Ok(OperationValue::default())
        }
        Command::RestartIce { peer_handle, .. } => {
            state.restart_ice(peer_handle).await?;
            Ok(OperationValue::default())
        }
        Command::SetConfiguration {
            peer_handle,
            ice_servers,
            relay_only,
            ..
        } => {
            state
                .set_configuration(peer_handle, ice_servers, relay_only)
                .await?;
            Ok(OperationValue::default())
        }
        Command::CreateDataChannel {
            peer_handle,
            configuration,
            ..
        } => {
            let (channel_handle, channel_id) = state
                .create_data_channel(peer_handle, configuration)
                .await?;
            Ok(OperationValue {
                channel_handle,
                text: Some(channel_id.to_string()),
                ..OperationValue::default()
            })
        }
        Command::SendText {
            channel_handle,
            text,
            ..
        } => {
            state.send_text(channel_handle, text).await?;
            Ok(OperationValue::default())
        }
        Command::SendBinary {
            channel_handle,
            data,
            ..
        } => {
            state.send_binary(channel_handle, data).await?;
            Ok(OperationValue::default())
        }
        Command::TrySendText {
            channel_handle,
            text,
            ..
        } => Ok(OperationValue {
            text: Some(state.try_send_text(channel_handle, text).await?.to_string()),
            ..OperationValue::default()
        }),
        Command::TrySendBinary {
            channel_handle,
            data,
            ..
        } => Ok(OperationValue {
            text: Some(
                state
                    .try_send_binary(channel_handle, data)
                    .await?
                    .to_string(),
            ),
            ..OperationValue::default()
        }),
        Command::DataChannelWritable { channel_handle, .. } => {
            state.data_channel_writable(channel_handle).await?;
            Ok(OperationValue::default())
        }
        Command::DataChannelOutstandingBytes { channel_handle, .. } => Ok(OperationValue {
            text: Some(
                state
                    .data_channel_outstanding_bytes(channel_handle)
                    .await?
                    .to_string(),
            ),
            ..OperationValue::default()
        }),
        Command::SetDataChannelThresholds {
            channel_handle,
            low,
            high,
            ..
        } => {
            state
                .set_data_channel_thresholds(channel_handle, low, high)
                .await?;
            Ok(OperationValue::default())
        }
        Command::GetStats { peer_handle, .. } => Ok(OperationValue {
            data: Some(state.get_stats(peer_handle).await?),
            ..OperationValue::default()
        }),
        Command::CloseDataChannel { channel_handle, .. } => {
            state.close_data_channel(channel_handle).await?;
            Ok(OperationValue::default())
        }
        Command::ClosePeer { peer_handle, .. } => {
            state.close_peer(peer_handle).await?;
            Ok(OperationValue::default())
        }
        Command::RotateCertificate { pem, .. } => {
            let certificate = match pem {
                Some(pem) => import_certificate(&pem)?,
                None => generate_certificate()?,
            };
            let fingerprint = certificate_fingerprint_value(&certificate)?;
            *state
                .certificate
                .lock()
                .map_err(|_| "The runtime DTLS certificate lock is poisoned".to_owned())? =
                certificate;
            Ok(OperationValue {
                text: Some(fingerprint),
                ..OperationValue::default()
            })
        }
        Command::Shutdown { .. } => Err("Invalid asynchronous shutdown command".to_owned()),
    }
}

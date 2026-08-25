use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, Mutex};
use std::thread::{self, JoinHandle as ThreadJoinHandle};
use std::time::Duration;

use crossbeam_channel::{Receiver, Sender, bounded, unbounded};
use tokio::runtime::{Builder, Runtime};
use tokio::task::JoinHandle;
use webrtc::peer_connection::{RTCIceCandidateInit, RTCSessionDescription};

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
            | Self::CreateDataChannel {
                operation_handle, ..
            }
            | Self::SendText {
                operation_handle, ..
            }
            | Self::SendBinary {
                operation_handle, ..
            }
            | Self::CloseDataChannel {
                operation_handle, ..
            }
            | Self::ClosePeer {
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
            | Self::CreateDataChannel { timeout, .. }
            | Self::SendText { timeout, .. }
            | Self::SendBinary { timeout, .. }
            | Self::CloseDataChannel { timeout, .. }
            | Self::ClosePeer { timeout, .. }
            | Self::Shutdown { timeout, .. } => *timeout,
        }
    }
}

struct RuntimeController {
    commands: Sender<Command>,
    events: Receiver<NativeEvent>,
    event_sender: Sender<NativeEvent>,
    thread: Mutex<Option<ThreadJoinHandle<()>>>,
}

static RUNTIMES: LazyLock<Mutex<HashMap<u64, Arc<RuntimeController>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));
static NEXT_RUNTIME_HANDLE: AtomicU64 = AtomicU64::new(1);

pub fn create(worker_threads: usize) -> Result<u64, String> {
    if worker_threads == 0 {
        return Err("The WebRTC runtime must have at least one worker thread".to_owned());
    }
    let runtime_handle = NEXT_RUNTIME_HANDLE.fetch_add(1, Ordering::Relaxed);
    let (command_sender, command_receiver) = unbounded();
    let (event_sender, event_receiver) = unbounded();
    let (ready_sender, ready_receiver) = bounded(1);
    let runtime_event_sender = event_sender.clone();
    let thread = thread::Builder::new()
        .name(format!("kestara-webrtc-control-{runtime_handle}"))
        .spawn(move || {
            run_runtime(
                runtime_handle,
                worker_threads,
                &command_receiver,
                runtime_event_sender,
                &ready_sender,
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
    });
    lock_runtimes()?.insert(runtime_handle, controller);
    Ok(runtime_handle)
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
    worker_threads: usize,
    commands: &Receiver<Command>,
    events: Sender<NativeEvent>,
    ready: &Sender<Result<(), String>>,
) {
    let runtime = match build_runtime(runtime_handle, worker_threads) {
        Ok(runtime) => {
            let _ = ready.send(Ok(()));
            runtime
        }
        Err(error) => {
            let _ = ready.send(Err(error));
            return;
        }
    };
    let state = Arc::new(RuntimeState::new(events));
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

fn dispatch(runtime: &Runtime, state: Arc<RuntimeState>, command: Command) -> JoinHandle<()> {
    let operation_handle = command.operation_handle();
    let timeout = command.timeout();
    runtime.spawn(async move {
        let result = tokio::time::timeout(timeout, execute(Arc::clone(&state), command))
            .await
            .map_err(|_| "WebRTC operation timed out".to_owned())
            .and_then(|result| result);
        state.send_event(NativeEvent::operation(operation_handle, result));
    })
}

async fn execute(state: Arc<RuntimeState>, command: Command) -> Result<OperationValue, String> {
    match command {
        Command::CreatePeer { configuration, .. } => {
            let peer_handle = state.create_peer(configuration).await?;
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
        Command::CreateDataChannel {
            peer_handle,
            configuration,
            ..
        } => {
            let channel_handle = state
                .create_data_channel(peer_handle, configuration)
                .await?;
            Ok(OperationValue {
                channel_handle,
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
        Command::CloseDataChannel { channel_handle, .. } => {
            state.close_data_channel(channel_handle).await?;
            Ok(OperationValue::default())
        }
        Command::ClosePeer { peer_handle, .. } => {
            state.close_peer(peer_handle).await?;
            Ok(OperationValue::default())
        }
        Command::Shutdown { .. } => Err("Invalid asynchronous shutdown command".to_owned()),
    }
}

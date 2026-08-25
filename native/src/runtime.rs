use std::future::Future;
use std::sync::{LazyLock, RwLock};
use std::time::Duration;

use tokio::runtime::{Builder, Handle, Runtime};

static RUNTIME: LazyLock<RwLock<Option<Runtime>>> = LazyLock::new(|| RwLock::new(None));

pub fn block_on<T, F>(timeout: Duration, future: F) -> Result<T, String>
where
    F: Future<Output = Result<T, String>>,
{
    ensure_runtime()?;
    let handle = runtime_handle()?;
    handle.block_on(async move {
        tokio::time::timeout(timeout, future)
            .await
            .map_err(|_| "WebRTC operation timed out".to_owned())?
    })
}

fn runtime_handle() -> Result<Handle, String> {
    let guard = RUNTIME
        .read()
        .map_err(|_| "Kestara WebRTC runtime lock is poisoned".to_owned())?;
    guard
        .as_ref()
        .map(|runtime| runtime.handle().clone())
        .ok_or_else(|| "Kestara WebRTC runtime is not available".to_owned())
}

pub fn shutdown(timeout: Duration) -> Result<(), String> {
    let runtime = RUNTIME
        .write()
        .map_err(|_| "Kestara WebRTC runtime lock is poisoned".to_owned())?
        .take();
    if let Some(runtime) = runtime {
        runtime.shutdown_timeout(timeout);
    }
    Ok(())
}

fn ensure_runtime() -> Result<(), String> {
    {
        let guard = RUNTIME
            .read()
            .map_err(|_| "Kestara WebRTC runtime lock is poisoned".to_owned())?;
        if guard.is_some() {
            return Ok(());
        }
    }

    let mut guard = RUNTIME
        .write()
        .map_err(|_| "Kestara WebRTC runtime lock is poisoned".to_owned())?;
    if guard.is_none() {
        let runtime = Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("kestara-webrtc")
            .enable_all()
            .build()
            .map_err(|error| format!("Failed to start Kestara WebRTC runtime: {error}"))?;
        *guard = Some(runtime);
    }
    Ok(())
}

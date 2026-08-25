use std::sync::LazyLock;
use std::time::Duration;

use crossbeam_channel::{Receiver, RecvTimeoutError, Sender, unbounded};

pub const WAKE: i32 = 0;
pub const LOCAL_CANDIDATE: i32 = 1;
pub const PEER_STATE: i32 = 2;
pub const ICE_CONNECTION_STATE: i32 = 3;
pub const ICE_GATHERING_STATE: i32 = 4;
pub const DATA_CHANNEL: i32 = 5;
pub const DATA_CHANNEL_OPEN: i32 = 6;
pub const DATA_CHANNEL_CLOSING: i32 = 7;
pub const DATA_CHANNEL_CLOSED: i32 = 8;
pub const DATA_CHANNEL_ERROR: i32 = 9;
pub const DATA_CHANNEL_TEXT: i32 = 10;
pub const DATA_CHANNEL_BINARY: i32 = 11;

#[derive(Debug)]
pub struct NativeEvent {
    pub kind: i32,
    pub peer_handle: u64,
    pub channel_handle: u64,
    pub text: Option<String>,
    pub secondary_text: Option<String>,
    pub number: i32,
    pub data: Option<Vec<u8>>,
}

impl NativeEvent {
    pub fn peer(kind: i32, peer_handle: u64, number: i32) -> Self {
        Self {
            kind,
            peer_handle,
            channel_handle: 0,
            text: None,
            secondary_text: None,
            number,
            data: None,
        }
    }

    pub fn channel(kind: i32, peer_handle: u64, channel_handle: u64) -> Self {
        Self {
            kind,
            peer_handle,
            channel_handle,
            text: None,
            secondary_text: None,
            number: 0,
            data: None,
        }
    }
}

static EVENTS: LazyLock<(Sender<NativeEvent>, Receiver<NativeEvent>)> = LazyLock::new(unbounded);

pub fn send(event: NativeEvent) {
    let _ = EVENTS.0.send(event);
}

pub fn poll(timeout: Duration) -> Option<NativeEvent> {
    match EVENTS.1.recv_timeout(timeout) {
        Ok(event) if event.kind != WAKE => Some(event),
        Ok(_) | Err(RecvTimeoutError::Timeout | RecvTimeoutError::Disconnected) => None,
    }
}

pub fn wake() {
    send(NativeEvent::peer(WAKE, 0, 0));
}

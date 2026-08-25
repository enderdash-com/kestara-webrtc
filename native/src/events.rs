use std::time::Duration;

use crossbeam_channel::{Receiver, RecvTimeoutError, Sender};
use tokio::sync::OwnedSemaphorePermit;

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
pub const OPERATION_COMPLETE: i32 = 12;
pub const DATA_CHANNEL_BUFFERED_AMOUNT_LOW: i32 = 13;
pub const DATA_CHANNEL_BUFFERED_AMOUNT_HIGH: i32 = 14;
pub const NEGOTIATION_NEEDED: i32 = 15;
pub const SIGNALING_STATE: i32 = 16;

#[derive(Debug)]
pub struct NativeEvent {
    pub kind: i32,
    pub peer_handle: u64,
    pub channel_handle: u64,
    pub operation_handle: u64,
    pub text: Option<String>,
    pub secondary_text: Option<String>,
    pub number: i32,
    pub data: Option<Vec<u8>>,
    pub delivery_permit: Option<OwnedSemaphorePermit>,
}

impl NativeEvent {
    pub fn peer(kind: i32, peer_handle: u64, number: i32) -> Self {
        Self {
            kind,
            peer_handle,
            channel_handle: 0,
            operation_handle: 0,
            text: None,
            secondary_text: None,
            number,
            data: None,
            delivery_permit: None,
        }
    }

    pub fn channel(kind: i32, peer_handle: u64, channel_handle: u64) -> Self {
        Self {
            kind,
            peer_handle,
            channel_handle,
            operation_handle: 0,
            text: None,
            secondary_text: None,
            number: 0,
            data: None,
            delivery_permit: None,
        }
    }

    pub fn operation(operation_handle: u64, result: Result<OperationValue, String>) -> Self {
        match result {
            Ok(value) => Self {
                kind: OPERATION_COMPLETE,
                peer_handle: value.peer_handle,
                channel_handle: value.channel_handle,
                operation_handle,
                text: value.text,
                secondary_text: None,
                number: 0,
                data: value.data,
                delivery_permit: None,
            },
            Err(error) => Self {
                kind: OPERATION_COMPLETE,
                peer_handle: 0,
                channel_handle: 0,
                operation_handle,
                text: None,
                secondary_text: Some(error),
                number: 1,
                data: None,
                delivery_permit: None,
            },
        }
    }
}

#[derive(Debug, Default)]
pub struct OperationValue {
    pub peer_handle: u64,
    pub channel_handle: u64,
    pub text: Option<String>,
    pub data: Option<Vec<u8>>,
}

pub fn poll(receiver: &Receiver<NativeEvent>, timeout: Duration) -> Option<NativeEvent> {
    match receiver.recv_timeout(timeout) {
        Ok(event) if event.kind != WAKE => Some(event),
        Ok(_) | Err(RecvTimeoutError::Timeout | RecvTimeoutError::Disconnected) => None,
    }
}

pub fn wake(sender: &Sender<NativeEvent>) {
    let _ = sender.send(NativeEvent::peer(WAKE, 0, 0));
}

use std::net::IpAddr;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::slice;

use ice::mdns::MulticastDnsMode;
use rtc::peer_connection::transport::RTCDtlsRole;
use serde::Deserialize;
use webrtc::peer_connection::{RTCIceCandidateInit, RTCIceCandidateType, RTCIceServer};

use crate::bridge::{
    cipher_suites, network_types, optional_duration, optional_u16, parse_description, port,
    positive_u32, timeout,
};
use crate::events::NativeEvent;
use crate::registry::{
    DataChannelConfiguration, DtlsConfiguration, IceConfiguration, PeerConfiguration,
    SctpConfiguration, TransportConfiguration,
};
use crate::runtime::{self, Command, RuntimeConfiguration};
use crate::{LIBRARY_VERSION, NATIVE_ABI_VERSION};

const CREATE_PEER: i32 = 1;
const RESTART_ICE: i32 = 2;
const SET_CONFIGURATION: i32 = 3;
const CREATE_DESCRIPTION: i32 = 4;
const SET_LOCAL_DESCRIPTION: i32 = 5;
const SET_REMOTE_DESCRIPTION: i32 = 6;
const ADD_ICE_CANDIDATE: i32 = 7;
const CREATE_DATA_CHANNEL: i32 = 8;
const SEND_TEXT: i32 = 9;
const SEND_BINARY: i32 = 10;
const TRY_SEND_TEXT: i32 = 11;
const TRY_SEND_BINARY: i32 = 12;
const DATA_CHANNEL_WRITABLE: i32 = 13;
const DATA_CHANNEL_OUTSTANDING_BYTES: i32 = 14;
const SET_DATA_CHANNEL_THRESHOLDS: i32 = 15;
const GET_STATS: i32 = 16;
const ROTATE_CERTIFICATE: i32 = 17;
const CLOSE_DATA_CHANNEL: i32 = 18;
const CLOSE_PEER: i32 = 19;
const CLOSE_RUNTIME: i32 = 20;

#[repr(C)]
pub struct KestaraBytes {
    data: *mut u8,
    length: usize,
}

impl KestaraBytes {
    const EMPTY: Self = Self {
        data: ptr::null_mut(),
        length: 0,
    };

    fn owned(data: Vec<u8>) -> Self {
        if data.is_empty() {
            return Self::EMPTY;
        }
        let mut data = data.into_boxed_slice();
        let result = Self {
            data: data.as_mut_ptr(),
            length: data.len(),
        };
        Box::leak(data);
        result
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RuntimeDto {
    worker_threads: usize,
    reactor_threads: usize,
    certificate_pem: Option<String>,
    shared_udp_addresses: Vec<String>,
    shared_tcp_addresses: Vec<String>,
    shared_min_port: i32,
    shared_max_port: i32,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct IceServerDto {
    urls: Vec<String>,
    username: String,
    credential: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct NatMappingDto {
    addresses: Vec<String>,
    mapping_type: i32,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CredentialsDto {
    username_fragment: String,
    password: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
#[allow(clippy::struct_excessive_bools)]
struct PeerDto {
    ice_servers: Vec<IceServerDto>,
    min_port: i32,
    max_port: i32,
    relay_only: bool,
    disconnected_timeout_millis: i64,
    failed_timeout_millis: i64,
    keep_alive_interval_millis: i64,
    check_interval_millis: i64,
    max_binding_requests: i32,
    host_acceptance_min_wait_millis: i64,
    server_reflexive_acceptance_min_wait_millis: i64,
    peer_reflexive_acceptance_min_wait_millis: i64,
    relay_acceptance_min_wait_millis: i64,
    network_type_mask: i32,
    mdns_mode: i32,
    mdns_query_timeout_millis: i64,
    ice_lite: bool,
    nat_mapping: Option<NatMappingDto>,
    discard_local_candidates_on_restart: bool,
    candidate_pool_size: u8,
    include_loopback_candidate: bool,
    mdns_local_name: Option<String>,
    mdns_local_address: Option<String>,
    credentials: Option<CredentialsDto>,
    sctp_send_buffer_limit: usize,
    sctp_receive_buffer_size: i32,
    sctp_maximum_message_size: i32,
    receive_queue_capacity: usize,
    dtls_answering_role: i32,
    media_level_fingerprints: bool,
    dtls_replay_protection_window: usize,
    dtls_cipher_suite_mask: i32,
    udp_bind_addresses: Vec<String>,
    tcp_bind_addresses: Vec<String>,
    receive_mtu: usize,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct DataChannelDto {
    ordered: bool,
    max_packet_life_time: i32,
    max_retransmits: i32,
    protocol: String,
    negotiated_id: i32,
}

#[unsafe(no_mangle)]
pub extern "C" fn kestara_abi_version() -> i32 {
    NATIVE_ABI_VERSION
}

#[unsafe(no_mangle)]
pub extern "C" fn kestara_library_version() -> KestaraBytes {
    KestaraBytes::owned(LIBRARY_VERSION.as_bytes().to_vec())
}

#[unsafe(no_mangle)]
pub extern "C" fn kestara_runtime_create(
    configuration: *const u8,
    configuration_length: usize,
    runtime_out: *mut u64,
    error_out: *mut KestaraBytes,
) -> i32 {
    boundary(error_out, || {
        if runtime_out.is_null() {
            return Err("The runtime output pointer is null".to_owned());
        }
        let configuration: RuntimeDto = decode_json(configuration, configuration_length)?;
        let min_port = port(configuration.shared_min_port, "shared minimum")?;
        let max_port = port(configuration.shared_max_port, "shared maximum")?;
        if (min_port == 0) != (max_port == 0) || min_port > max_port {
            return Err("Invalid shared socket port range".to_owned());
        }
        let runtime = runtime::create(RuntimeConfiguration {
            worker_threads: configuration.worker_threads,
            reactor_threads: configuration.reactor_threads,
            certificate_pem: configuration.certificate_pem,
            shared_udp_addresses: configuration.shared_udp_addresses,
            shared_tcp_addresses: configuration.shared_tcp_addresses,
            shared_min_port: min_port,
            shared_max_port: max_port,
        })?;
        unsafe { runtime_out.write(runtime) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn kestara_runtime_certificate_fingerprint(
    runtime_handle: u64,
    value_out: *mut KestaraBytes,
    error_out: *mut KestaraBytes,
) -> i32 {
    output_string(error_out, value_out, || {
        runtime::certificate_fingerprint(runtime_handle)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn kestara_runtime_certificate_pem(
    runtime_handle: u64,
    value_out: *mut KestaraBytes,
    error_out: *mut KestaraBytes,
) -> i32 {
    output_string(error_out, value_out, || {
        runtime::certificate_pem(runtime_handle)
    })
}

#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments, clippy::too_many_lines)]
pub extern "C" fn kestara_runtime_submit(
    runtime_handle: u64,
    operation_handle: u64,
    command_kind: i32,
    target_handle: u64,
    timeout_millis: i64,
    text: *const u8,
    text_length: usize,
    secondary_text: *const u8,
    secondary_text_length: usize,
    number: i64,
    secondary_number: i64,
    data: *const u8,
    data_length: usize,
    configuration: *const u8,
    configuration_length: usize,
    error_out: *mut KestaraBytes,
) -> i32 {
    boundary(error_out, || {
        let timeout = timeout(timeout_millis)?;
        let text = optional_string(text, text_length)?;
        let secondary_text = optional_string(secondary_text, secondary_text_length)?;
        let data = input(data, data_length)?.to_vec();
        let command = match command_kind {
            CREATE_PEER => Command::CreatePeer {
                operation_handle,
                timeout,
                configuration: peer_configuration(decode_json(
                    configuration,
                    configuration_length,
                )?)?,
            },
            RESTART_ICE => Command::RestartIce {
                operation_handle,
                timeout,
                peer_handle: target_handle,
            },
            SET_CONFIGURATION => {
                let configuration: PeerDto = decode_json(configuration, configuration_length)?;
                Command::SetConfiguration {
                    operation_handle,
                    timeout,
                    peer_handle: target_handle,
                    ice_servers: ice_servers(configuration.ice_servers),
                    relay_only: configuration.relay_only,
                }
            }
            CREATE_DESCRIPTION => Command::CreateDescription {
                operation_handle,
                timeout,
                peer_handle: target_handle,
                answer: match number {
                    0 => false,
                    1 => true,
                    _ => return Err("Only offer and answer descriptions can be created".to_owned()),
                },
            },
            SET_LOCAL_DESCRIPTION | SET_REMOTE_DESCRIPTION => {
                let description = parse_description(
                    required(text, "session description")?,
                    i32::try_from(number)
                        .map_err(|_| "Invalid session description type".to_owned())?,
                );
                let description = match description {
                    Ok(description) => description,
                    Err(error) => {
                        return runtime::complete_error(runtime_handle, operation_handle, error);
                    }
                };
                if command_kind == SET_LOCAL_DESCRIPTION {
                    Command::SetLocalDescription {
                        operation_handle,
                        timeout,
                        peer_handle: target_handle,
                        description,
                    }
                } else {
                    Command::SetRemoteDescription {
                        operation_handle,
                        timeout,
                        peer_handle: target_handle,
                        description,
                    }
                }
            }
            ADD_ICE_CANDIDATE => Command::AddIceCandidate {
                operation_handle,
                timeout,
                peer_handle: target_handle,
                candidate: RTCIceCandidateInit {
                    candidate: required(text, "ICE candidate")?,
                    sdp_mid: secondary_text,
                    sdp_mline_index: optional_u16(
                        i32::try_from(number).map_err(|_| "Invalid SDP m-line index".to_owned())?,
                        "SDP m-line index",
                    )?,
                    username_fragment: None,
                    url: None,
                },
            },
            CREATE_DATA_CHANNEL => {
                let configuration: DataChannelDto =
                    decode_json(configuration, configuration_length)?;
                Command::CreateDataChannel {
                    operation_handle,
                    timeout,
                    peer_handle: target_handle,
                    configuration: DataChannelConfiguration {
                        label: required(text, "DataChannel label")?,
                        ordered: configuration.ordered,
                        max_packet_life_time: optional_u16(
                            configuration.max_packet_life_time,
                            "maximum packet lifetime",
                        )?,
                        max_retransmits: optional_u16(
                            configuration.max_retransmits,
                            "maximum retransmissions",
                        )?,
                        protocol: configuration.protocol,
                        negotiated_id: optional_u16(
                            configuration.negotiated_id,
                            "negotiated DataChannel ID",
                        )?,
                    },
                }
            }
            SEND_TEXT => Command::SendText {
                operation_handle,
                timeout,
                channel_handle: target_handle,
                text: required(text, "DataChannel text")?,
            },
            SEND_BINARY => Command::SendBinary {
                operation_handle,
                timeout,
                channel_handle: target_handle,
                data,
            },
            TRY_SEND_TEXT => Command::TrySendText {
                operation_handle,
                timeout,
                channel_handle: target_handle,
                text: required(text, "DataChannel text")?,
            },
            TRY_SEND_BINARY => Command::TrySendBinary {
                operation_handle,
                timeout,
                channel_handle: target_handle,
                data,
            },
            DATA_CHANNEL_WRITABLE => Command::DataChannelWritable {
                operation_handle,
                timeout,
                channel_handle: target_handle,
            },
            DATA_CHANNEL_OUTSTANDING_BYTES => Command::DataChannelOutstandingBytes {
                operation_handle,
                timeout,
                channel_handle: target_handle,
            },
            SET_DATA_CHANNEL_THRESHOLDS => Command::SetDataChannelThresholds {
                operation_handle,
                timeout,
                channel_handle: target_handle,
                low: u32::try_from(number)
                    .map_err(|_| "Invalid low buffered amount threshold".to_owned())?,
                high: u32::try_from(secondary_number)
                    .map_err(|_| "Invalid high buffered amount threshold".to_owned())?,
            },
            GET_STATS => Command::GetStats {
                operation_handle,
                timeout,
                peer_handle: target_handle,
            },
            ROTATE_CERTIFICATE => Command::RotateCertificate {
                operation_handle,
                timeout,
                pem: text,
            },
            CLOSE_DATA_CHANNEL => Command::CloseDataChannel {
                operation_handle,
                timeout,
                channel_handle: target_handle,
            },
            CLOSE_PEER => Command::ClosePeer {
                operation_handle,
                timeout,
                peer_handle: target_handle,
            },
            CLOSE_RUNTIME => Command::Shutdown {
                operation_handle,
                timeout,
            },
            _ => return Err(format!("Unknown native command: {command_kind}")),
        };
        runtime::submit(runtime_handle, command)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn kestara_runtime_poll(
    runtime_handle: u64,
    timeout_millis: i64,
    event_out: *mut KestaraBytes,
    error_out: *mut KestaraBytes,
) -> i32 {
    boundary(error_out, || {
        if event_out.is_null() {
            return Err("The event output pointer is null".to_owned());
        }
        let event = runtime::poll(runtime_handle, timeout(timeout_millis)?)?;
        let bytes = event.map_or(KestaraBytes::EMPTY, |event| {
            KestaraBytes::owned(encode_event(event))
        });
        unsafe { event_out.write(bytes) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn kestara_runtime_wake(runtime_handle: u64, error_out: *mut KestaraBytes) -> i32 {
    boundary(error_out, || runtime::wake(runtime_handle))
}

#[unsafe(no_mangle)]
pub extern "C" fn kestara_runtime_release(
    runtime_handle: u64,
    error_out: *mut KestaraBytes,
) -> i32 {
    boundary(error_out, || runtime::release(runtime_handle))
}

#[unsafe(no_mangle)]
pub extern "C" fn kestara_bytes_free(bytes: KestaraBytes) {
    if bytes.data.is_null() {
        return;
    }
    let pointer = ptr::slice_from_raw_parts_mut(bytes.data, bytes.length);
    unsafe { drop(Box::from_raw(pointer)) };
}

#[allow(clippy::too_many_lines)]
fn peer_configuration(value: PeerDto) -> Result<PeerConfiguration, String> {
    let min_port = port(value.min_port, "minimum")?;
    let max_port = port(value.max_port, "maximum")?;
    if (min_port == 0) != (max_port == 0) || min_port > max_port {
        return Err("Invalid UDP port range".to_owned());
    }
    let mdns_mode = match value.mdns_mode {
        0 => MulticastDnsMode::Disabled,
        1 => MulticastDnsMode::QueryOnly,
        2 => MulticastDnsMode::QueryAndGather,
        _ => return Err("Invalid ICE mDNS mode".to_owned()),
    };
    let nat_mapping = match value.nat_mapping {
        None => None,
        Some(mapping) if mapping.mapping_type == 0 && !mapping.addresses.is_empty() => {
            Some((mapping.addresses, RTCIceCandidateType::Host))
        }
        Some(mapping) if mapping.mapping_type == 1 && !mapping.addresses.is_empty() => {
            Some((mapping.addresses, RTCIceCandidateType::Srflx))
        }
        Some(_) => return Err("Invalid ICE NAT mapping".to_owned()),
    };
    let mdns_local_address = value
        .mdns_local_address
        .map(|address| {
            address
                .parse::<IpAddr>()
                .map_err(|error| format!("Invalid mDNS local address: {error}"))
        })
        .transpose()?;
    Ok(PeerConfiguration {
        ice_servers: ice_servers(value.ice_servers),
        min_port,
        max_port,
        relay_only: value.relay_only,
        ice: IceConfiguration {
            disconnected_timeout: optional_duration(
                value.disconnected_timeout_millis,
                "ICE disconnected timeout",
            )?,
            failed_timeout: optional_duration(value.failed_timeout_millis, "ICE failed timeout")?,
            keep_alive_interval: optional_duration(
                value.keep_alive_interval_millis,
                "ICE keep-alive interval",
            )?,
            check_interval: optional_duration(value.check_interval_millis, "ICE check interval")?,
            max_binding_requests: optional_u16(
                value.max_binding_requests,
                "maximum ICE binding requests",
            )?,
            host_acceptance_min_wait: optional_duration(
                value.host_acceptance_min_wait_millis,
                "host candidate acceptance wait",
            )?,
            server_reflexive_acceptance_min_wait: optional_duration(
                value.server_reflexive_acceptance_min_wait_millis,
                "server-reflexive candidate acceptance wait",
            )?,
            peer_reflexive_acceptance_min_wait: optional_duration(
                value.peer_reflexive_acceptance_min_wait_millis,
                "peer-reflexive candidate acceptance wait",
            )?,
            relay_acceptance_min_wait: optional_duration(
                value.relay_acceptance_min_wait_millis,
                "relay candidate acceptance wait",
            )?,
            network_types: network_types(value.network_type_mask)?,
            mdns_mode,
            mdns_query_timeout: optional_duration(
                value.mdns_query_timeout_millis,
                "mDNS query timeout",
            )?,
            lite: value.ice_lite,
            nat_mapping,
            discard_local_candidates_on_restart: value.discard_local_candidates_on_restart,
            candidate_pool_size: value.candidate_pool_size,
            include_loopback_candidate: value.include_loopback_candidate,
            mdns_local_name: value.mdns_local_name,
            mdns_local_address,
            credentials: value
                .credentials
                .map(|credentials| (credentials.username_fragment, credentials.password)),
        },
        sctp: SctpConfiguration {
            send_buffer_limit: value.sctp_send_buffer_limit,
            receive_buffer_size: positive_u32(
                value.sctp_receive_buffer_size,
                "SCTP receive buffer size",
            )?,
            maximum_message_size: positive_u32(
                value.sctp_maximum_message_size,
                "SCTP maximum message size",
            )?,
            receive_queue_capacity: value.receive_queue_capacity,
        },
        dtls: DtlsConfiguration {
            answering_role: match value.dtls_answering_role {
                0 => None,
                1 => Some(RTCDtlsRole::Client),
                2 => Some(RTCDtlsRole::Server),
                _ => return Err("Invalid DTLS answering role".to_owned()),
            },
            media_level_fingerprints: value.media_level_fingerprints,
            replay_protection_window: value.dtls_replay_protection_window,
            cipher_suites: cipher_suites(value.dtls_cipher_suite_mask)?,
        },
        transport: TransportConfiguration {
            udp_bind_addresses: value.udp_bind_addresses,
            tcp_bind_addresses: value.tcp_bind_addresses,
            receive_mtu: value.receive_mtu,
        },
    })
}

fn ice_servers(servers: Vec<IceServerDto>) -> Vec<RTCIceServer> {
    servers
        .into_iter()
        .map(|server| RTCIceServer {
            urls: server.urls,
            username: server.username,
            credential: server.credential,
        })
        .collect()
}

fn encode_event(mut event: NativeEvent) -> Vec<u8> {
    let mut output = Vec::new();
    output.push(1);
    output.extend_from_slice(&event.kind.to_le_bytes());
    output.extend_from_slice(&event.peer_handle.to_le_bytes());
    output.extend_from_slice(&event.channel_handle.to_le_bytes());
    output.extend_from_slice(&event.operation_handle.to_le_bytes());
    output.extend_from_slice(&event.number.to_le_bytes());
    encode_optional(&mut output, event.text.take().map(String::into_bytes));
    encode_optional(
        &mut output,
        event.secondary_text.take().map(String::into_bytes),
    );
    encode_optional(&mut output, event.data.take());
    output
}

fn encode_optional(output: &mut Vec<u8>, value: Option<Vec<u8>>) {
    match value {
        Some(value) => {
            let length = i32::try_from(value.len()).unwrap_or(i32::MAX);
            output.extend_from_slice(&length.to_le_bytes());
            output.extend_from_slice(&value[..usize::try_from(length).unwrap_or(0)]);
        }
        None => output.extend_from_slice(&(-1_i32).to_le_bytes()),
    }
}

fn output_string(
    error_out: *mut KestaraBytes,
    value_out: *mut KestaraBytes,
    operation: impl FnOnce() -> Result<String, String>,
) -> i32 {
    boundary(error_out, || {
        if value_out.is_null() {
            return Err("The value output pointer is null".to_owned());
        }
        let value = KestaraBytes::owned(operation()?.into_bytes());
        unsafe { value_out.write(value) };
        Ok(())
    })
}

fn boundary(error_out: *mut KestaraBytes, operation: impl FnOnce() -> Result<(), String>) -> i32 {
    match catch_unwind(AssertUnwindSafe(operation)) {
        Ok(Ok(())) => 0,
        Ok(Err(error)) => {
            write_error(error_out, error);
            1
        }
        Err(_) => {
            write_error(error_out, "The native WebRTC boundary panicked".to_owned());
            2
        }
    }
}

fn write_error(error_out: *mut KestaraBytes, error: String) {
    if !error_out.is_null() {
        unsafe { error_out.write(KestaraBytes::owned(error.into_bytes())) };
    }
}

fn decode_json<T: for<'de> Deserialize<'de>>(value: *const u8, length: usize) -> Result<T, String> {
    serde_json::from_slice(input(value, length)?).map_err(|error| error.to_string())
}

fn optional_string(value: *const u8, length: usize) -> Result<Option<String>, String> {
    if value.is_null() {
        Ok(None)
    } else {
        String::from_utf8(input(value, length)?.to_vec())
            .map(Some)
            .map_err(|error| error.to_string())
    }
}

fn required(value: Option<String>, name: &str) -> Result<String, String> {
    value.ok_or_else(|| format!("The {name} is missing"))
}

fn input<'a>(value: *const u8, length: usize) -> Result<&'a [u8], String> {
    if length == 0 {
        return Ok(&[]);
    }
    if value.is_null() {
        return Err("A native input pointer is null".to_owned());
    }
    Ok(unsafe { slice::from_raw_parts(value, length) })
}

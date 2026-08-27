use std::net::IpAddr;
use std::time::Duration;

use ice::mdns::MulticastDnsMode;
use ice::network_type::NetworkType;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JClass, JObject, JObjectArray, JString, JValue};
use jni::strings::JNIString;
use jni::sys::{jboolean, jint, jlong, jobject, jstring};
use jni::{Env, EnvUnowned, jni_sig, jni_str};
use rtc::peer_connection::transport::RTCDtlsRole;
use webrtc::peer_connection::{
    CipherSuiteId, RTCIceCandidateInit, RTCIceCandidateType, RTCIceServer, RTCSessionDescription,
};

use crate::registry::{
    DataChannelConfiguration, DtlsConfiguration, IceConfiguration, PeerConfiguration,
    SctpConfiguration, TransportConfiguration,
};
use crate::runtime::{self, Command, RuntimeConfiguration};
use crate::{LIBRARY_VERSION, NATIVE_ABI_VERSION};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeAbiVersion(
    _env: EnvUnowned<'_>,
    _class: JClass<'_>,
) -> jint {
    NATIVE_ABI_VERSION
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeRuntimeCertificateFingerprint(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let result = handle_from_jlong(runtime_handle)
                .and_then(runtime::certificate_fingerprint)
                .and_then(|fingerprint| {
                    env.new_string(fingerprint)
                        .map(JString::into_raw)
                        .map_err(|error| error.to_string())
                });
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeRuntimeCertificatePem(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let result = handle_from_jlong(runtime_handle)
                .and_then(runtime::certificate_pem)
                .and_then(|pem| {
                    env.new_string(pem)
                        .map(JString::into_raw)
                        .map_err(|error| error.to_string())
                });
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeLibraryVersion(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            Ok(env.new_string(LIBRARY_VERSION)?.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeCreateRuntime(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    worker_threads: jint,
    reactor_threads: jint,
    certificate_pem: JString<'_>,
    shared_udp_addresses: JObjectArray<'_, JString<'_>>,
    shared_tcp_addresses: JObjectArray<'_, JString<'_>>,
    shared_min_port: jint,
    shared_max_port: jint,
) -> jlong {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jlong> {
            let certificate_pem = read_string(env, &certificate_pem)?;
            let shared_udp_addresses = read_string_array(env, &shared_udp_addresses)?;
            let shared_tcp_addresses = read_string_array(env, &shared_tcp_addresses)?;
            let result = (|| {
                let worker_threads = usize::try_from(worker_threads)
                    .map_err(|_| "The worker thread count must not be negative".to_owned())?;
                let reactor_threads = usize::try_from(reactor_threads)
                    .map_err(|_| "The reactor thread count must not be negative".to_owned())?;
                let shared_min_port = port(shared_min_port, "shared minimum")?;
                let shared_max_port = port(shared_max_port, "shared maximum")?;
                if (shared_min_port == 0) != (shared_max_port == 0)
                    || shared_min_port > shared_max_port
                {
                    return Err("Invalid shared socket port range".to_owned());
                }
                runtime::create(RuntimeConfiguration {
                    worker_threads,
                    reactor_threads,
                    certificate_pem: (!certificate_pem.is_empty()).then_some(certificate_pem),
                    shared_udp_addresses,
                    shared_tcp_addresses,
                    shared_min_port,
                    shared_max_port,
                })
            })()
            .and_then(handle_to_jlong);
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments, clippy::too_many_lines)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitCreatePeer(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    urls: JObjectArray<'_, JString<'_>>,
    usernames: JObjectArray<'_, JString<'_>>,
    credentials: JObjectArray<'_, JString<'_>>,
    min_port: jint,
    max_port: jint,
    ice_transport_policy: jint,
    disconnected_timeout_millis: jlong,
    failed_timeout_millis: jlong,
    keep_alive_interval_millis: jlong,
    check_interval_millis: jlong,
    max_binding_requests: jint,
    host_acceptance_min_wait_millis: jlong,
    server_reflexive_acceptance_min_wait_millis: jlong,
    peer_reflexive_acceptance_min_wait_millis: jlong,
    relay_acceptance_min_wait_millis: jlong,
    network_type_mask: jint,
    mdns_mode: jint,
    mdns_query_timeout_millis: jlong,
    ice_lite: jboolean,
    nat_addresses: JObjectArray<'_, JString<'_>>,
    nat_mapping_type: jint,
    discard_local_candidates_on_restart: jboolean,
    candidate_pool_size: jint,
    include_loopback_candidate: jboolean,
    mdns_local_name: JString<'_>,
    mdns_local_address: JString<'_>,
    ice_username_fragment: JString<'_>,
    ice_password: JString<'_>,
    sctp_send_buffer_limit: jint,
    sctp_receive_buffer_size: jint,
    sctp_maximum_message_size: jint,
    data_channel_receive_queue_capacity: jint,
    dtls_answering_role: jint,
    media_level_fingerprints: jboolean,
    dtls_replay_protection_window: jint,
    dtls_cipher_suite_mask: jint,
    udp_bind_addresses: JObjectArray<'_, JString<'_>>,
    tcp_bind_addresses: JObjectArray<'_, JString<'_>>,
    receive_mtu: jint,
    timeout_millis: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let urls = read_string_array(env, &urls)?;
            let usernames = read_string_array(env, &usernames)?;
            let credentials = read_string_array(env, &credentials)?;
            let nat_addresses = read_string_array(env, &nat_addresses)?;
            let mdns_local_name = read_string(env, &mdns_local_name)?;
            let mdns_local_address = read_string(env, &mdns_local_address)?;
            let ice_username_fragment = read_string(env, &ice_username_fragment)?;
            let ice_password = read_string(env, &ice_password)?;
            let udp_bind_addresses = read_string_array(env, &udp_bind_addresses)?;
            let tcp_bind_addresses = read_string_array(env, &tcp_bind_addresses)?;
            let result = (|| {
                let runtime_handle = handle_from_jlong(runtime_handle)?;
                let operation_handle = handle_from_jlong(operation_handle)?;
                if urls.len() != usernames.len() || urls.len() != credentials.len() {
                    return Err("ICE server arrays must have the same length".to_owned());
                }
                let min_port = port(min_port, "minimum")?;
                let max_port = port(max_port, "maximum")?;
                if (min_port == 0) != (max_port == 0) || min_port > max_port {
                    return Err("Invalid UDP port range".to_owned());
                }
                if !(0..=1).contains(&ice_transport_policy) {
                    return Err("Invalid ICE transport policy".to_owned());
                }
                let network_types = network_types(network_type_mask)?;
                let mdns_mode = match mdns_mode {
                    0 => MulticastDnsMode::Disabled,
                    1 => MulticastDnsMode::QueryOnly,
                    2 => MulticastDnsMode::QueryAndGather,
                    _ => return Err("Invalid ICE mDNS mode".to_owned()),
                };
                let nat_mapping = match nat_mapping_type {
                    -1 if nat_addresses.is_empty() => None,
                    0 if !nat_addresses.is_empty() => {
                        Some((nat_addresses, RTCIceCandidateType::Host))
                    }
                    1 if !nat_addresses.is_empty() => {
                        Some((nat_addresses, RTCIceCandidateType::Srflx))
                    }
                    _ => return Err("Invalid ICE NAT mapping".to_owned()),
                };
                let sctp_receive_buffer_size =
                    positive_u32(sctp_receive_buffer_size, "SCTP receive buffer size")?;
                let sctp_maximum_message_size =
                    positive_u32(sctp_maximum_message_size, "SCTP maximum message size")?;
                let receive_queue_capacity = usize::try_from(data_channel_receive_queue_capacity)
                    .map_err(|_| {
                    "DataChannel receive queue capacity must be positive".to_owned()
                })?;
                if receive_queue_capacity == 0 {
                    return Err("DataChannel receive queue capacity must be positive".to_owned());
                }
                if sctp_maximum_message_size > 256 * 1024 {
                    return Err("SCTP maximum message size must not exceed 262144".to_owned());
                }
                if sctp_receive_buffer_size < 1_500
                    || sctp_receive_buffer_size < sctp_maximum_message_size
                {
                    return Err(
                        "SCTP receive buffer size must cover the maximum message size".to_owned(),
                    );
                }
                let timeout = timeout(timeout_millis)?;
                let answering_role = match dtls_answering_role {
                    0 => None,
                    1 => Some(RTCDtlsRole::Client),
                    2 => Some(RTCDtlsRole::Server),
                    _ => return Err("Invalid DTLS answering role".to_owned()),
                };
                let replay_protection_window = usize::try_from(dtls_replay_protection_window)
                    .map_err(|_| "DTLS replay protection window must be positive".to_owned())?;
                if replay_protection_window == 0 {
                    return Err("DTLS replay protection window must be positive".to_owned());
                }
                let receive_mtu = usize::try_from(receive_mtu)
                    .map_err(|_| "Receive MTU must not be negative".to_owned())?;
                let mdns_local_address = if mdns_local_address.is_empty() {
                    None
                } else {
                    Some(
                        mdns_local_address
                            .parse::<IpAddr>()
                            .map_err(|error| format!("Invalid mDNS local address: {error}"))?,
                    )
                };
                let ice_credentials =
                    match (ice_username_fragment.is_empty(), ice_password.is_empty()) {
                        (true, true) => None,
                        (false, false) => Some((ice_username_fragment, ice_password)),
                        _ => {
                            return Err("ICE username fragment and password must be set together"
                                .to_owned());
                        }
                    };
                let ice_servers = urls
                    .into_iter()
                    .zip(usernames)
                    .zip(credentials)
                    .map(|((url, username), credential)| RTCIceServer {
                        urls: vec![url],
                        username,
                        credential,
                    })
                    .collect();
                runtime::submit(
                    runtime_handle,
                    Command::CreatePeer {
                        operation_handle,
                        timeout,
                        configuration: PeerConfiguration {
                            ice_servers,
                            min_port,
                            max_port,
                            relay_only: ice_transport_policy == 1,
                            ice: IceConfiguration {
                                disconnected_timeout: optional_duration(
                                    disconnected_timeout_millis,
                                    "ICE disconnected timeout",
                                )?,
                                failed_timeout: optional_duration(
                                    failed_timeout_millis,
                                    "ICE failed timeout",
                                )?,
                                keep_alive_interval: optional_duration(
                                    keep_alive_interval_millis,
                                    "ICE keep-alive interval",
                                )?,
                                check_interval: optional_duration(
                                    check_interval_millis,
                                    "ICE check interval",
                                )?,
                                max_binding_requests: optional_u16(
                                    max_binding_requests,
                                    "maximum ICE binding requests",
                                )?,
                                host_acceptance_min_wait: optional_duration(
                                    host_acceptance_min_wait_millis,
                                    "host candidate acceptance wait",
                                )?,
                                server_reflexive_acceptance_min_wait: optional_duration(
                                    server_reflexive_acceptance_min_wait_millis,
                                    "server-reflexive candidate acceptance wait",
                                )?,
                                peer_reflexive_acceptance_min_wait: optional_duration(
                                    peer_reflexive_acceptance_min_wait_millis,
                                    "peer-reflexive candidate acceptance wait",
                                )?,
                                relay_acceptance_min_wait: optional_duration(
                                    relay_acceptance_min_wait_millis,
                                    "relay candidate acceptance wait",
                                )?,
                                network_types,
                                mdns_mode,
                                mdns_query_timeout: optional_duration(
                                    mdns_query_timeout_millis,
                                    "mDNS query timeout",
                                )?,
                                lite: ice_lite,
                                nat_mapping,
                                discard_local_candidates_on_restart,
                                candidate_pool_size: match candidate_pool_size {
                                    0 => 0,
                                    1 => 1,
                                    _ => {
                                        return Err(
                                            "ICE candidate pool size must be 0 or 1".to_owned()
                                        );
                                    }
                                },
                                include_loopback_candidate,
                                mdns_local_name: (!mdns_local_name.is_empty())
                                    .then_some(mdns_local_name),
                                mdns_local_address,
                                credentials: ice_credentials,
                            },
                            sctp: SctpConfiguration {
                                send_buffer_limit: usize::try_from(sctp_send_buffer_limit)
                                    .map_err(|_| {
                                        "SCTP send buffer limit must not be negative".to_owned()
                                    })?,
                                receive_buffer_size: sctp_receive_buffer_size,
                                maximum_message_size: sctp_maximum_message_size,
                                receive_queue_capacity,
                            },
                            dtls: DtlsConfiguration {
                                answering_role,
                                media_level_fingerprints,
                                replay_protection_window,
                                cipher_suites: cipher_suites(dtls_cipher_suite_mask)?,
                            },
                            transport: TransportConfiguration {
                                udp_bind_addresses,
                                tcp_bind_addresses,
                                receive_mtu,
                            },
                        },
                    },
                )
            })();
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitRestartIce(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    peer_handle: jlong,
    timeout_millis: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let result = (|| {
                runtime::submit(
                    handle_from_jlong(runtime_handle)?,
                    Command::RestartIce {
                        operation_handle: handle_from_jlong(operation_handle)?,
                        timeout: timeout(timeout_millis)?,
                        peer_handle: handle_from_jlong(peer_handle)?,
                    },
                )
            })();
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitSetConfiguration(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    peer_handle: jlong,
    urls: JObjectArray<'_, JString<'_>>,
    usernames: JObjectArray<'_, JString<'_>>,
    credentials: JObjectArray<'_, JString<'_>>,
    ice_transport_policy: jint,
    timeout_millis: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let urls = read_string_array(env, &urls)?;
            let usernames = read_string_array(env, &usernames)?;
            let credentials = read_string_array(env, &credentials)?;
            let result = (|| {
                if urls.len() != usernames.len() || urls.len() != credentials.len() {
                    return Err("ICE server arrays must have the same length".to_owned());
                }
                if !(0..=1).contains(&ice_transport_policy) {
                    return Err("Invalid ICE transport policy".to_owned());
                }
                let ice_servers = urls
                    .into_iter()
                    .zip(usernames)
                    .zip(credentials)
                    .map(|((url, username), credential)| RTCIceServer {
                        urls: vec![url],
                        username,
                        credential,
                    })
                    .collect();
                runtime::submit(
                    handle_from_jlong(runtime_handle)?,
                    Command::SetConfiguration {
                        operation_handle: handle_from_jlong(operation_handle)?,
                        timeout: timeout(timeout_millis)?,
                        peer_handle: handle_from_jlong(peer_handle)?,
                        ice_servers,
                        relay_only: ice_transport_policy == 1,
                    },
                )
            })();
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitCreateDescription(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    peer_handle: jlong,
    description_type: jint,
    timeout_millis: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let result = (|| {
                let runtime_handle = handle_from_jlong(runtime_handle)?;
                let operation_handle = handle_from_jlong(operation_handle)?;
                let peer_handle = handle_from_jlong(peer_handle)?;
                let answer = match description_type {
                    0 => false,
                    1 => true,
                    _ => return Err("Only offer and answer descriptions can be created".to_owned()),
                };
                runtime::submit(
                    runtime_handle,
                    Command::CreateDescription {
                        operation_handle,
                        timeout: timeout(timeout_millis)?,
                        peer_handle,
                        answer,
                    },
                )
            })();
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitSetLocalDescription(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    peer_handle: jlong,
    sdp: JString<'_>,
    description_type: jint,
    timeout_millis: jlong,
) {
    submit_description(
        &mut unowned_env,
        runtime_handle,
        operation_handle,
        peer_handle,
        &sdp,
        description_type,
        timeout_millis,
        true,
    );
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitSetRemoteDescription(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    peer_handle: jlong,
    sdp: JString<'_>,
    description_type: jint,
    timeout_millis: jlong,
) {
    submit_description(
        &mut unowned_env,
        runtime_handle,
        operation_handle,
        peer_handle,
        &sdp,
        description_type,
        timeout_millis,
        false,
    );
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitAddIceCandidate(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    peer_handle: jlong,
    candidate: JString<'_>,
    sdp_mid: JString<'_>,
    sdp_mline_index: jint,
    timeout_millis: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let candidate = read_string(env, &candidate)?;
            let sdp_mid = read_optional_string(env, &sdp_mid)?;
            let result = (|| {
                runtime::submit(
                    handle_from_jlong(runtime_handle)?,
                    Command::AddIceCandidate {
                        operation_handle: handle_from_jlong(operation_handle)?,
                        timeout: timeout(timeout_millis)?,
                        peer_handle: handle_from_jlong(peer_handle)?,
                        candidate: RTCIceCandidateInit {
                            candidate,
                            sdp_mid,
                            sdp_mline_index: optional_u16(sdp_mline_index, "SDP m-line index")?,
                            username_fragment: None,
                            url: None,
                        },
                    },
                )
            })();
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitCreateDataChannel(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    peer_handle: jlong,
    label: JString<'_>,
    ordered: jboolean,
    max_packet_life_time: jint,
    max_retransmits: jint,
    protocol: JString<'_>,
    negotiated_id: jint,
    timeout_millis: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let label = read_string(env, &label)?;
            let protocol = read_string(env, &protocol)?;
            let result = (|| {
                runtime::submit(
                    handle_from_jlong(runtime_handle)?,
                    Command::CreateDataChannel {
                        operation_handle: handle_from_jlong(operation_handle)?,
                        timeout: timeout(timeout_millis)?,
                        peer_handle: handle_from_jlong(peer_handle)?,
                        configuration: DataChannelConfiguration {
                            label,
                            ordered,
                            max_packet_life_time: optional_u16(
                                max_packet_life_time,
                                "maximum packet lifetime",
                            )?,
                            max_retransmits: optional_u16(
                                max_retransmits,
                                "maximum retransmissions",
                            )?,
                            protocol,
                            negotiated_id: optional_u16(
                                negotiated_id,
                                "negotiated DataChannel ID",
                            )?,
                        },
                    },
                )
            })();
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitSendDataChannelText(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    channel_handle: jlong,
    text: JString<'_>,
    timeout_millis: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let text = read_string(env, &text)?;
            let result = (|| {
                runtime::submit(
                    handle_from_jlong(runtime_handle)?,
                    Command::SendText {
                        operation_handle: handle_from_jlong(operation_handle)?,
                        timeout: timeout(timeout_millis)?,
                        channel_handle: handle_from_jlong(channel_handle)?,
                        text,
                    },
                )
            })();
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeReleaseBuffer(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    buffer_handle: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let result = (|| {
                runtime::release_buffer(
                    handle_from_jlong(runtime_handle)?,
                    handle_from_jlong(buffer_handle)?,
                )
            })();
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitSendDataChannelBinary(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    channel_handle: jlong,
    data: JByteArray<'_>,
    timeout_millis: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let data = env.convert_byte_array(&data)?;
            let result = (|| {
                runtime::submit(
                    handle_from_jlong(runtime_handle)?,
                    Command::SendBinary {
                        operation_handle: handle_from_jlong(operation_handle)?,
                        timeout: timeout(timeout_millis)?,
                        channel_handle: handle_from_jlong(channel_handle)?,
                        data,
                    },
                )
            })();
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitTrySendDataChannelText(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    channel_handle: jlong,
    text: JString<'_>,
    timeout_millis: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let text = read_string(env, &text)?;
            let result = (|| {
                runtime::submit(
                    handle_from_jlong(runtime_handle)?,
                    Command::TrySendText {
                        operation_handle: handle_from_jlong(operation_handle)?,
                        timeout: timeout(timeout_millis)?,
                        channel_handle: handle_from_jlong(channel_handle)?,
                        text,
                    },
                )
            })();
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitTrySendDataChannelBinary(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    channel_handle: jlong,
    data: JByteArray<'_>,
    timeout_millis: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let data = env.convert_byte_array(&data)?;
            let result = (|| {
                runtime::submit(
                    handle_from_jlong(runtime_handle)?,
                    Command::TrySendBinary {
                        operation_handle: handle_from_jlong(operation_handle)?,
                        timeout: timeout(timeout_millis)?,
                        channel_handle: handle_from_jlong(channel_handle)?,
                        data,
                    },
                )
            })();
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitDataChannelWritable(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    channel_handle: jlong,
    timeout_millis: jlong,
) {
    submit_simple_channel_command(
        &mut unowned_env,
        runtime_handle,
        operation_handle,
        channel_handle,
        timeout_millis,
        |operation_handle, timeout, channel_handle| Command::DataChannelWritable {
            operation_handle,
            timeout,
            channel_handle,
        },
    );
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitDataChannelOutstandingBytes(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    channel_handle: jlong,
    timeout_millis: jlong,
) {
    submit_simple_channel_command(
        &mut unowned_env,
        runtime_handle,
        operation_handle,
        channel_handle,
        timeout_millis,
        |operation_handle, timeout, channel_handle| Command::DataChannelOutstandingBytes {
            operation_handle,
            timeout,
            channel_handle,
        },
    );
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitSetDataChannelThresholds(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    channel_handle: jlong,
    low: jlong,
    high: jlong,
    timeout_millis: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let result = (|| {
                let low = u32::try_from(low)
                    .map_err(|_| "Low buffered amount threshold must not be negative".to_owned())?;
                let high = u32::try_from(high).map_err(|_| {
                    "High buffered amount threshold must not be negative".to_owned()
                })?;
                if low > high {
                    return Err("Low buffered amount threshold must not exceed high".to_owned());
                }
                runtime::submit(
                    handle_from_jlong(runtime_handle)?,
                    Command::SetDataChannelThresholds {
                        operation_handle: handle_from_jlong(operation_handle)?,
                        timeout: timeout(timeout_millis)?,
                        channel_handle: handle_from_jlong(channel_handle)?,
                        low,
                        high,
                    },
                )
            })();
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitGetStats(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    peer_handle: jlong,
    timeout_millis: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let result = (|| {
                runtime::submit(
                    handle_from_jlong(runtime_handle)?,
                    Command::GetStats {
                        operation_handle: handle_from_jlong(operation_handle)?,
                        timeout: timeout(timeout_millis)?,
                        peer_handle: handle_from_jlong(peer_handle)?,
                    },
                )
            })();
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitRotateCertificate(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    certificate_pem: JString<'_>,
    timeout_millis: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let pem = read_string(env, &certificate_pem)?;
            let result = (|| {
                runtime::submit(
                    handle_from_jlong(runtime_handle)?,
                    Command::RotateCertificate {
                        operation_handle: handle_from_jlong(operation_handle)?,
                        timeout: timeout(timeout_millis)?,
                        pem: (!pem.is_empty()).then_some(pem),
                    },
                )
            })();
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitCloseDataChannel(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    channel_handle: jlong,
    timeout_millis: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let result = (|| {
                runtime::submit(
                    handle_from_jlong(runtime_handle)?,
                    Command::CloseDataChannel {
                        operation_handle: handle_from_jlong(operation_handle)?,
                        timeout: timeout(timeout_millis)?,
                        channel_handle: handle_from_jlong(channel_handle)?,
                    },
                )
            })();
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitClosePeer(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    peer_handle: jlong,
    timeout_millis: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let result = (|| {
                runtime::submit(
                    handle_from_jlong(runtime_handle)?,
                    Command::ClosePeer {
                        operation_handle: handle_from_jlong(operation_handle)?,
                        timeout: timeout(timeout_millis)?,
                        peer_handle: handle_from_jlong(peer_handle)?,
                    },
                )
            })();
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeSubmitCloseRuntime(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    timeout_millis: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let result = (|| {
                runtime::submit(
                    handle_from_jlong(runtime_handle)?,
                    Command::Shutdown {
                        operation_handle: handle_from_jlong(operation_handle)?,
                        timeout: timeout(timeout_millis)?,
                    },
                )
            })();
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativePollRuntimeEvent(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
    timeout_millis: jlong,
) -> jobject {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jobject> {
            let result = handle_from_jlong(runtime_handle)
                .and_then(|runtime_handle| runtime::poll(runtime_handle, timeout(timeout_millis)?));
            let Some(mut event) = operation_result(env, result)? else {
                return Ok(std::ptr::null_mut());
            };
            let text = optional_java_string(env, event.text.take())?;
            let secondary_text = optional_java_string(env, event.secondary_text.take())?;
            let mut message_handle = 0;
            let mut direct_data = JObject::null();
            if let Some(permit) = event.delivery_permit.take() {
                let view = operation_result(
                    env,
                    handle_from_jlong(runtime_handle).and_then(|runtime_handle| {
                        runtime::register_delivery_buffer(runtime_handle, event.data.take(), permit)
                    }),
                )?;
                message_handle = handle_to_jlong(view.handle).unwrap_or_default();
                if event.kind == crate::events::DATA_CHANNEL_BINARY {
                    // The runtime registry owns the allocation until Java closes the message.
                    let buffer = unsafe { env.new_direct_byte_buffer(view.address, view.length)? };
                    direct_data = JObject::from(buffer);
                }
            }
            let data = match event.data {
                Some(data) => JObject::from(env.byte_array_from_slice(&data)?),
                None => JObject::null(),
            };
            let object = env.new_object(
                jni_str!("com/enderdash/kestara/webrtc/internal/JvmNativeEvent"),
                jni_sig!("(IJJJLjava/lang/String;Ljava/lang/String;I[BJLjava/nio/ByteBuffer;)V"),
                &[
                    JValue::Int(event.kind),
                    JValue::Long(handle_to_jlong(event.peer_handle).unwrap_or_default()),
                    JValue::Long(handle_to_jlong(event.channel_handle).unwrap_or_default()),
                    JValue::Long(handle_to_jlong(event.operation_handle).unwrap_or_default()),
                    JValue::Object(&text),
                    JValue::Object(&secondary_text),
                    JValue::Int(event.number),
                    JValue::Object(&data),
                    JValue::Long(message_handle),
                    JValue::Object(&direct_data),
                ],
            )?;
            Ok(object.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeWakeRuntime(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let result = handle_from_jlong(runtime_handle).and_then(runtime::wake);
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeReleaseRuntime(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    runtime_handle: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let result = handle_from_jlong(runtime_handle).and_then(runtime::release);
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

#[allow(clippy::too_many_arguments)]
fn submit_description(
    unowned_env: &mut EnvUnowned<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    peer_handle: jlong,
    sdp: &JString<'_>,
    description_type: jint,
    timeout_millis: jlong,
    local: bool,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let sdp = read_string(env, sdp)?;
            let result = (|| {
                let runtime_handle = handle_from_jlong(runtime_handle)?;
                let operation_handle = handle_from_jlong(operation_handle)?;
                let peer_handle = handle_from_jlong(peer_handle)?;
                let timeout = timeout(timeout_millis)?;
                let command = parse_description(sdp, description_type).map(|description| {
                    if local {
                        Command::SetLocalDescription {
                            operation_handle,
                            timeout,
                            peer_handle,
                            description,
                        }
                    } else {
                        Command::SetRemoteDescription {
                            operation_handle,
                            timeout,
                            peer_handle,
                            description,
                        }
                    }
                });
                match command {
                    Ok(command) => runtime::submit(runtime_handle, command),
                    Err(error) => runtime::complete_error(runtime_handle, operation_handle, error),
                }
            })();
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

fn submit_simple_channel_command(
    unowned_env: &mut EnvUnowned<'_>,
    runtime_handle: jlong,
    operation_handle: jlong,
    channel_handle: jlong,
    timeout_millis: jlong,
    command: impl FnOnce(u64, Duration, u64) -> Command,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let result = (|| {
                runtime::submit(
                    handle_from_jlong(runtime_handle)?,
                    command(
                        handle_from_jlong(operation_handle)?,
                        timeout(timeout_millis)?,
                        handle_from_jlong(channel_handle)?,
                    ),
                )
            })();
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>();
}

fn read_string(env: &Env<'_>, value: &JString<'_>) -> jni::errors::Result<String> {
    value.try_to_string(env)
}

fn read_optional_string(env: &Env<'_>, value: &JString<'_>) -> jni::errors::Result<Option<String>> {
    if value.is_null() {
        Ok(None)
    } else {
        read_string(env, value).map(Some)
    }
}

fn read_string_array(
    env: &mut Env<'_>,
    array: &JObjectArray<'_, JString<'_>>,
) -> jni::errors::Result<Vec<String>> {
    let mut values = Vec::with_capacity(array.len(env)?);
    for index in 0..array.len(env)? {
        let value = array.get_element(env, index)?;
        values.push(read_string(env, &value)?);
    }
    Ok(values)
}

fn optional_java_string<'local>(
    env: &mut Env<'local>,
    value: Option<String>,
) -> jni::errors::Result<JObject<'local>> {
    match value {
        Some(value) => Ok(JObject::from(env.new_string(value)?)),
        None => Ok(JObject::null()),
    }
}

fn operation_result<T: Default>(
    env: &mut Env<'_>,
    result: Result<T, String>,
) -> jni::errors::Result<T> {
    match result {
        Ok(value) => Ok(value),
        Err(message) => {
            env.throw_new(
                jni_str!("com/enderdash/kestara/webrtc/WebRtcException"),
                JNIString::from(message),
            )?;
            Ok(T::default())
        }
    }
}

pub(crate) fn parse_description(
    sdp: String,
    description_type: jint,
) -> Result<RTCSessionDescription, String> {
    let result = match description_type {
        0 => RTCSessionDescription::offer(sdp),
        1 => RTCSessionDescription::answer(sdp),
        2 => RTCSessionDescription::pranswer(sdp),
        3 => RTCSessionDescription::rollback(Some(sdp)),
        _ => {
            return Err(format!(
                "Unknown session description type: {description_type}"
            ));
        }
    };
    result.map_err(|error| format!("Invalid session description: {error}"))
}

pub(crate) fn timeout(millis: jlong) -> Result<Duration, String> {
    let millis =
        u64::try_from(millis).map_err(|_| "Operation timeout must not be negative".to_owned())?;
    Ok(Duration::from_millis(millis))
}

pub(crate) fn optional_duration(millis: jlong, name: &str) -> Result<Option<Duration>, String> {
    if millis == -1 {
        Ok(None)
    } else {
        u64::try_from(millis)
            .map(Duration::from_millis)
            .map(Some)
            .map_err(|_| format!("The {name} must not be negative"))
    }
}

pub(crate) fn positive_u32(value: jint, name: &str) -> Result<u32, String> {
    let value = u32::try_from(value).map_err(|_| format!("The {name} must be positive"))?;
    if value == 0 {
        Err(format!("The {name} must be positive"))
    } else {
        Ok(value)
    }
}

pub(crate) fn network_types(mask: jint) -> Result<Vec<NetworkType>, String> {
    if mask <= 0 || mask & !0b1111 != 0 {
        return Err("Invalid ICE network type mask".to_owned());
    }
    let mut values = Vec::new();
    if mask & 0b0001 != 0 {
        values.push(NetworkType::Udp4);
    }
    if mask & 0b0010 != 0 {
        values.push(NetworkType::Udp6);
    }
    if mask & 0b0100 != 0 {
        values.push(NetworkType::Tcp4);
    }
    if mask & 0b1000 != 0 {
        values.push(NetworkType::Tcp6);
    }
    Ok(values)
}

pub(crate) fn cipher_suites(mask: jint) -> Result<Vec<CipherSuiteId>, String> {
    if mask <= 0 || mask & !0xff != 0 {
        return Err("Invalid DTLS cipher suite mask".to_owned());
    }
    let all = [
        CipherSuiteId::Tls_Ecdhe_Ecdsa_With_Aes_128_Ccm,
        CipherSuiteId::Tls_Ecdhe_Ecdsa_With_Aes_128_Ccm_8,
        CipherSuiteId::Tls_Ecdhe_Ecdsa_With_Aes_128_Gcm_Sha256,
        CipherSuiteId::Tls_Ecdhe_Rsa_With_Aes_128_Gcm_Sha256,
        CipherSuiteId::Tls_Ecdhe_Ecdsa_With_Aes_256_Cbc_Sha,
        CipherSuiteId::Tls_Ecdhe_Rsa_With_Aes_256_Cbc_Sha,
        CipherSuiteId::Tls_Ecdhe_Rsa_With_ChaCha20_Poly1305_Sha256,
        CipherSuiteId::Tls_Ecdhe_Ecdsa_With_ChaCha20_Poly1305_Sha256,
    ];
    Ok(all
        .into_iter()
        .enumerate()
        .filter_map(|(index, suite)| (mask & (1 << index) != 0).then_some(suite))
        .collect())
}

pub(crate) fn port(value: jint, name: &str) -> Result<u16, String> {
    u16::try_from(value).map_err(|_| format!("The {name} port must be between 0 and 65535"))
}

pub(crate) fn optional_u16(value: jint, name: &str) -> Result<Option<u16>, String> {
    if value == -1 {
        Ok(None)
    } else {
        u16::try_from(value)
            .map(Some)
            .map_err(|_| format!("The {name} must be between 0 and 65535"))
    }
}

fn handle_from_jlong(value: jlong) -> Result<u64, String> {
    u64::try_from(value).map_err(|_| "Native handle must not be negative".to_owned())
}

fn handle_to_jlong(value: u64) -> Result<jlong, String> {
    jlong::try_from(value).map_err(|_| "Native handle range is exhausted".to_owned())
}

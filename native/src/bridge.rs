use std::time::Duration;

use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JClass, JObject, JObjectArray, JString, JValue};
use jni::strings::JNIString;
use jni::sys::{jboolean, jint, jlong, jobject, jstring};
use jni::{Env, EnvUnowned, jni_sig, jni_str};
use webrtc::peer_connection::{RTCIceCandidateInit, RTCIceServer, RTCSessionDescription};

use crate::NATIVE_ABI_VERSION;
use crate::registry::{DataChannelConfiguration, PeerConfiguration};
use crate::runtime::{self, Command};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeAbiVersion(
    _env: EnvUnowned<'_>,
    _class: JClass<'_>,
) -> jint {
    NATIVE_ABI_VERSION
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeLibraryVersion(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            Ok(env.new_string(env!("CARGO_PKG_VERSION"))?.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_kestara_webrtc_internal_NativeBindings_nativeCreateRuntime(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
    worker_threads: jint,
) -> jlong {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jlong> {
            let result = usize::try_from(worker_threads)
                .map_err(|_| "The worker thread count must not be negative".to_owned())
                .and_then(runtime::create)
                .and_then(handle_to_jlong);
            operation_result(env, result)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
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
    data_channel_send_buffer_limit: jint,
    timeout_millis: jlong,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let urls = read_string_array(env, &urls)?;
            let usernames = read_string_array(env, &usernames)?;
            let credentials = read_string_array(env, &credentials)?;
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
                let data_channel_send_buffer_limit =
                    usize::try_from(data_channel_send_buffer_limit).map_err(|_| {
                        "DataChannel send buffer limit must not be negative".to_owned()
                    })?;
                let timeout = timeout(timeout_millis)?;
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
                            data_channel_send_buffer_limit,
                        },
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
            let Some(event) = operation_result(env, result)? else {
                return Ok(std::ptr::null_mut());
            };
            let text = optional_java_string(env, event.text)?;
            let secondary_text = optional_java_string(env, event.secondary_text)?;
            let data = match event.data {
                Some(data) => JObject::from(env.byte_array_from_slice(&data)?),
                None => JObject::null(),
            };
            let object = env.new_object(
                jni_str!("com/enderdash/kestara/webrtc/internal/NativeEvent"),
                jni_sig!("(IJJJLjava/lang/String;Ljava/lang/String;I[B)V"),
                &[
                    JValue::Int(event.kind),
                    JValue::Long(handle_to_jlong(event.peer_handle).unwrap_or_default()),
                    JValue::Long(handle_to_jlong(event.channel_handle).unwrap_or_default()),
                    JValue::Long(handle_to_jlong(event.operation_handle).unwrap_or_default()),
                    JValue::Object(&text),
                    JValue::Object(&secondary_text),
                    JValue::Int(event.number),
                    JValue::Object(&data),
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

fn parse_description(sdp: String, description_type: jint) -> Result<RTCSessionDescription, String> {
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

fn timeout(millis: jlong) -> Result<Duration, String> {
    let millis =
        u64::try_from(millis).map_err(|_| "Operation timeout must not be negative".to_owned())?;
    Ok(Duration::from_millis(millis))
}

fn port(value: jint, name: &str) -> Result<u16, String> {
    u16::try_from(value).map_err(|_| format!("The {name} port must be between 0 and 65535"))
}

fn optional_u16(value: jint, name: &str) -> Result<Option<u16>, String> {
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

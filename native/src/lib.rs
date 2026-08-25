use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::JClass;
use jni::sys::{jint, jstring};

const NATIVE_ABI_VERSION: jint = 1;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_alloy_webrtc_internal_NativeBindings_nativeAbiVersion(
    _env: EnvUnowned<'_>,
    _class: JClass<'_>,
) -> jint {
    NATIVE_ABI_VERSION
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_enderdash_alloy_webrtc_internal_NativeBindings_nativeLibraryVersion(
    mut unowned_env: EnvUnowned<'_>,
    _class: JClass<'_>,
) -> jstring {
    unowned_env
        .with_env(|env| -> jni::errors::Result<jstring> {
            Ok(env.new_string(env!("CARGO_PKG_VERSION"))?.into_raw())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[cfg(test)]
mod tests {
    use super::NATIVE_ABI_VERSION;

    #[test]
    fn native_abi_starts_at_one() {
        assert_eq!(NATIVE_ABI_VERSION, 1);
    }
}

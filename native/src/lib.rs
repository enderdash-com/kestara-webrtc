mod events;
mod registry;
mod runtime;

mod bridge;
mod c_bridge;

const NATIVE_ABI_VERSION: i32 = 7;
const LIBRARY_VERSION: &str = match option_env!("KESTARA_LIBRARY_VERSION") {
    Some(version) => version,
    None => env!("CARGO_PKG_VERSION"),
};

#[cfg(test)]
mod tests {
    use super::NATIVE_ABI_VERSION;

    #[test]
    fn native_abi_is_version_seven() {
        assert_eq!(NATIVE_ABI_VERSION, 7);
    }
}

mod events;
mod registry;
mod runtime;

mod bridge;

const NATIVE_ABI_VERSION: i32 = 4;

#[cfg(test)]
mod tests {
    use super::NATIVE_ABI_VERSION;

    #[test]
    fn native_abi_is_version_four() {
        assert_eq!(NATIVE_ABI_VERSION, 4);
    }
}

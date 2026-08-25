mod events;
mod registry;
mod runtime;

mod bridge;

const NATIVE_ABI_VERSION: i32 = 3;

#[cfg(test)]
mod tests {
    use super::NATIVE_ABI_VERSION;

    #[test]
    fn native_abi_is_version_three() {
        assert_eq!(NATIVE_ABI_VERSION, 3);
    }
}

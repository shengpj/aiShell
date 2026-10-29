use base64::Engine;

/// Returns the 32-byte AES master key as base64, creating it in the Windows Credential Manager on
/// first run. On other platforms credential storage is unavailable, so the backend runs without
/// secret storage instead of falling back to a weaker on-disk key.
pub fn master_key_base64() -> Option<String> {
    match load_or_create() {
        Ok(key) => Some(base64::engine::general_purpose::STANDARD.encode(key)),
        Err(error) => {
            eprintln!("ai-shell: credential master key unavailable: {error}");
            None
        }
    }
}

#[cfg(windows)]
fn load_or_create() -> Result<[u8; 32], String> {
    use windows::core::{PCWSTR, PWSTR};
    use windows::Win32::Security::Credentials::{
        CredFree, CredReadW, CredWriteW, CREDENTIALW, CRED_FLAGS, CRED_PERSIST_LOCAL_MACHINE,
        CRED_TYPE_GENERIC,
    };

    const TARGET: &str = "AI Shell/master-key";
    const USER: &str = "ai-shell";

    fn wide(value: &str) -> Vec<u16> {
        value.encode_utf16().chain(std::iter::once(0)).collect()
    }

    let target = wide(TARGET);
    let mut credential: *mut CREDENTIALW = std::ptr::null_mut();
    let read = unsafe {
        CredReadW(
            PCWSTR(target.as_ptr()),
            CRED_TYPE_GENERIC,
            None,
            &mut credential,
        )
    };
    if read.is_ok() && !credential.is_null() {
        let existing = unsafe {
            let credential = &*credential;
            let blob = std::slice::from_raw_parts(
                credential.CredentialBlob,
                credential.CredentialBlobSize as usize,
            );
            let key = (blob.len() == 32).then(|| {
                let mut key = [0u8; 32];
                key.copy_from_slice(blob);
                key
            });
            CredFree(credential as *const _ as *const std::ffi::c_void);
            key
        };
        if let Some(key) = existing {
            return Ok(key);
        }
    }

    let key = generate_key();
    let mut target = wide(TARGET);
    let mut user = wide(USER);
    let mut blob = key.to_vec();
    let entry = CREDENTIALW {
        Flags: CRED_FLAGS(0),
        Type: CRED_TYPE_GENERIC,
        TargetName: PWSTR(target.as_mut_ptr()),
        Comment: PWSTR::null(),
        LastWritten: Default::default(),
        CredentialBlobSize: blob.len() as u32,
        CredentialBlob: blob.as_mut_ptr(),
        Persist: CRED_PERSIST_LOCAL_MACHINE,
        AttributeCount: 0,
        Attributes: std::ptr::null_mut(),
        TargetAlias: PWSTR::null(),
        UserName: PWSTR(user.as_mut_ptr()),
    };
    unsafe { CredWriteW(&entry, 0) }.map_err(|error| format!("CredWriteW failed: {error}"))?;
    Ok(key)
}

#[cfg(not(windows))]
fn load_or_create() -> Result<[u8; 32], String> {
    Err("credential storage requires Windows Credential Manager".to_string())
}

/// Two UUIDv4 values give 244 random bits, enough for an AES-256 key without a RNG dependency.
fn generate_key() -> [u8; 32] {
    let mut key = [0u8; 32];
    key[..16].copy_from_slice(uuid::Uuid::new_v4().as_bytes());
    key[16..].copy_from_slice(uuid::Uuid::new_v4().as_bytes());
    key
}

#[cfg(all(test, windows))]
mod tests {
    use base64::Engine;

    #[test]
    #[ignore = "writes the real Windows Credential Manager entry"]
    fn master_key_survives_a_second_read() {
        let first = super::master_key_base64().expect("credential master key");
        let second = super::master_key_base64().expect("credential master key");
        assert_eq!(first, second);
        let decoded = base64::engine::general_purpose::STANDARD
            .decode(first)
            .expect("base64 key");
        assert_eq!(decoded.len(), 32);
    }
}

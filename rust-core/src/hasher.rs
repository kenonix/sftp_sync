use crate::errors::SyncCoreError;
use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::{BufReader, Read};
use std::path::Path;

#[uniffi::export]
pub fn calculate_file_sha256(file_path: String) -> Result<String, SyncCoreError> {
    let path = Path::new(&file_path);
    if !path.exists() {
        return Err(SyncCoreError::IoError {
            msg: format!("File does not exist: {}", file_path),
        });
    }

    let file = File::open(path)?;
    let mut reader = BufReader::with_capacity(64 * 1024, file);
    let mut hasher = Sha256::new();
    let mut buffer = [0u8; 16384];

    loop {
        let count = reader.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        hasher.update(&buffer[..count]);
    }

    let result = hasher.finalize();
    Ok(format!("{:x}", result))
}

#[uniffi::export]
pub fn calculate_bytes_sha256(bytes: Vec<u8>) -> String {
    let mut hasher = Sha256::new();
    hasher.update(&bytes);
    let result = hasher.finalize();
    format!("{:x}", result)
}

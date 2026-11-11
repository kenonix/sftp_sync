#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SyncCoreError {
    #[error("IO error: {msg}")]
    IoError { msg: String },

    #[error("Hash calculation failed: {msg}")]
    HashError { msg: String },

    #[error("Sync engine error: {msg}")]
    EngineError { msg: String },

    #[error("Invalid configuration: {msg}")]
    ConfigError { msg: String },
}

impl From<std::io::Error> for SyncCoreError {
    fn from(err: std::io::Error) -> Self {
        SyncCoreError::IoError {
            msg: err.to_string(),
        }
    }
}

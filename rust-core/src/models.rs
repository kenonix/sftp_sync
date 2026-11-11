use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum NativeConflictStrategy {
    NewerWins,
    LocalWins,
    RemoteWins,
    KeepBoth,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum NativeSyncCondition {
    TimeDifferent,
    TimeAndSizeDifferent,
    SizeDifferent,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum NativeActionType {
    Upload,
    Download,
    DeleteLocal,
    DeleteRemote,
    Conflict,
    None,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct NativeFileMetadata {
    pub relative_path: String,
    pub size: i64,
    pub last_modified: i64,
    pub is_directory: bool,
    pub hash: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct NativeSyncAction {
    pub relative_path: String,
    pub action_type: NativeActionType,
    pub size: i64,
    pub local_modified: i64,
    pub remote_modified: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct NativeBandwidthConfig {
    pub enabled: bool,
    pub upload_limit_kbps: i64,
    pub download_limit_kbps: i64,
    pub block_metered_download: bool,
}

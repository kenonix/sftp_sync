pub mod bandwidth;
pub mod engine;
pub mod errors;
pub mod hasher;
pub mod models;

uniffi::setup_scaffolding!();

pub use bandwidth::NativeBandwidthLimiter;
pub use engine::{calculate_sync_actions, is_path_excluded, resolve_conflict_action};
pub use errors::SyncCoreError;
pub use hasher::{calculate_bytes_sha256, calculate_file_sha256};
pub use models::{
    NativeActionType, NativeBandwidthConfig, NativeConflictStrategy, NativeFileMetadata,
    NativeSyncAction, NativeSyncCondition,
};

#[uniffi::export]
pub fn get_core_version() -> String {
    format!("sftp_sync_core v{}", env!("CARGO_PKG_VERSION"))
}

use crate::models::{
    NativeActionType, NativeConflictStrategy, NativeFileMetadata, NativeSyncAction,
    NativeSyncCondition,
};
use std::collections::{HashMap, HashSet};

#[uniffi::export]
pub fn is_path_excluded(relative_path: String, exclusions: Vec<String>) -> bool {
    if exclusions.is_empty() {
        return false;
    }
    let normalized = relative_path.replace('\\', "/");
    exclusions.iter().any(|exclusion| {
        let clean_ex = exclusion.replace('\\', "/").trim().to_string();
        if clean_ex.is_empty() {
            return false;
        }

        normalized.contains(&format!("/{}/", clean_ex))
            || normalized.starts_with(&format!("{}/", clean_ex))
            || normalized.ends_with(&format!("/{}", clean_ex))
            || normalized == clean_ex
            || (clean_ex.starts_with("*.") && normalized.ends_with(&clean_ex[1..]))
    })
}

#[uniffi::export]
pub fn calculate_sync_actions(
    local_files: Vec<NativeFileMetadata>,
    remote_files: Vec<NativeFileMetadata>,
    last_state_files: Vec<NativeFileMetadata>,
    exclusions: Vec<String>,
    _sync_condition: NativeSyncCondition,
) -> Vec<NativeSyncAction> {
    let local_map: HashMap<String, NativeFileMetadata> = local_files
        .into_iter()
        .map(|f| (f.relative_path.clone(), f))
        .collect();

    let remote_map: HashMap<String, NativeFileMetadata> = remote_files
        .into_iter()
        .map(|f| (f.relative_path.clone(), f))
        .collect();

    let last_map: HashMap<String, NativeFileMetadata> = last_state_files
        .into_iter()
        .map(|f| (f.relative_path.clone(), f))
        .collect();

    let mut all_paths = HashSet::new();
    for k in local_map.keys().chain(remote_map.keys()).chain(last_map.keys()) {
        if !is_path_excluded(k.clone(), exclusions.clone()) {
            all_paths.insert(k.clone());
        }
    }

    let mut actions = Vec::new();

    for path in all_paths {
        let local = local_map.get(&path);
        let remote = remote_map.get(&path);
        let meta = last_map.get(&path);

        if local.map_or(false, |l| l.is_directory) || remote.map_or(false, |r| r.is_directory) {
            continue;
        }

        match (local, remote, meta) {
            // Case 1: Exists in Local, Remote, and Last State
            (Some(l), Some(r), Some(m)) => {
                let local_changed = if let (Some(lh), Some(mh)) = (&l.hash, &m.hash) {
                    lh != mh
                } else {
                    l.size != m.size || (l.last_modified - m.last_modified).abs() > 2000
                };

                let remote_changed = if let (Some(rh), Some(mh)) = (&r.hash, &m.hash) {
                    rh != mh
                } else {
                    r.size != m.size || (r.last_modified - m.last_modified).abs() > 2000
                };

                if local_changed && remote_changed {
                    let hashes_equal = match (&l.hash, &r.hash) {
                        (Some(lh), Some(rh)) => lh == rh,
                        _ => false,
                    };
                    let size_time_equal = l.size == r.size && (l.last_modified - r.last_modified).abs() <= 2000;

                    if hashes_equal || size_time_equal {
                        actions.push(NativeSyncAction {
                            relative_path: path,
                            action_type: NativeActionType::None,
                            size: l.size,
                            local_modified: l.last_modified,
                            remote_modified: r.last_modified,
                        });
                    } else {
                        actions.push(NativeSyncAction {
                            relative_path: path,
                            action_type: NativeActionType::Conflict,
                            size: l.size,
                            local_modified: l.last_modified,
                            remote_modified: r.last_modified,
                        });
                    }
                } else if local_changed {
                    actions.push(NativeSyncAction {
                        relative_path: path,
                        action_type: NativeActionType::Upload,
                        size: l.size,
                        local_modified: l.last_modified,
                        remote_modified: r.last_modified,
                    });
                } else if remote_changed {
                    actions.push(NativeSyncAction {
                        relative_path: path,
                        action_type: NativeActionType::Download,
                        size: r.size,
                        local_modified: l.last_modified,
                        remote_modified: r.last_modified,
                    });
                } else {
                    actions.push(NativeSyncAction {
                        relative_path: path,
                        action_type: NativeActionType::None,
                        size: l.size,
                        local_modified: l.last_modified,
                        remote_modified: r.last_modified,
                    });
                }
            }

            // Case 2: Exists in Local and Remote, but NOT in Last State
            (Some(l), Some(r), None) => {
                let hashes_equal = match (&l.hash, &r.hash) {
                    (Some(lh), Some(rh)) => lh == rh,
                    _ => false,
                };
                let size_time_equal = l.size == r.size && (l.last_modified - r.last_modified).abs() <= 2000;

                if hashes_equal || size_time_equal {
                    actions.push(NativeSyncAction {
                        relative_path: path,
                        action_type: NativeActionType::None,
                        size: l.size,
                        local_modified: l.last_modified,
                        remote_modified: r.last_modified,
                    });
                } else {
                    actions.push(NativeSyncAction {
                        relative_path: path,
                        action_type: NativeActionType::Conflict,
                        size: l.size,
                        local_modified: l.last_modified,
                        remote_modified: r.last_modified,
                    });
                }
            }

            // Case 3: Local exists, Remote does not, exists in Last State (Remote deleted)
            (Some(l), None, Some(m)) => {
                let local_changed = if let (Some(lh), Some(mh)) = (&l.hash, &m.hash) {
                    lh != mh
                } else {
                    l.size != m.size || (l.last_modified - m.last_modified).abs() > 2000
                };

                if local_changed {
                    actions.push(NativeSyncAction {
                        relative_path: path,
                        action_type: NativeActionType::Conflict,
                        size: l.size,
                        local_modified: l.last_modified,
                        remote_modified: 0,
                    });
                } else {
                    actions.push(NativeSyncAction {
                        relative_path: path,
                        action_type: NativeActionType::DeleteLocal,
                        size: l.size,
                        local_modified: l.last_modified,
                        remote_modified: 0,
                    });
                }
            }

            // Case 4: Local does not exist, Remote exists, exists in Last State (Local deleted)
            (None, Some(r), Some(m)) => {
                let remote_changed = if let (Some(rh), Some(mh)) = (&r.hash, &m.hash) {
                    rh != mh
                } else {
                    r.size != m.size || (r.last_modified - m.last_modified).abs() > 2000
                };

                if remote_changed {
                    actions.push(NativeSyncAction {
                        relative_path: path,
                        action_type: NativeActionType::Conflict,
                        size: r.size,
                        local_modified: 0,
                        remote_modified: r.last_modified,
                    });
                } else {
                    actions.push(NativeSyncAction {
                        relative_path: path,
                        action_type: NativeActionType::DeleteRemote,
                        size: r.size,
                        local_modified: 0,
                        remote_modified: r.last_modified,
                    });
                }
            }

            // Case 5: Local exists only (New local file)
            (Some(l), None, None) => {
                actions.push(NativeSyncAction {
                    relative_path: path,
                    action_type: NativeActionType::Upload,
                    size: l.size,
                    local_modified: l.last_modified,
                    remote_modified: 0,
                });
            }

            // Case 6: Remote exists only (New remote file)
            (None, Some(r), None) => {
                actions.push(NativeSyncAction {
                    relative_path: path,
                    action_type: NativeActionType::Download,
                    size: r.size,
                    local_modified: 0,
                    remote_modified: r.last_modified,
                });
            }

            (None, None, _) => {}
        }
    }

    actions
}

#[uniffi::export]
pub fn resolve_conflict_action(
    action: NativeSyncAction,
    strategy: NativeConflictStrategy,
) -> NativeSyncAction {
    if action.action_type != NativeActionType::Conflict {
        return action;
    }

    let resolved_type = match strategy {
        NativeConflictStrategy::NewerWins => {
            if action.local_modified >= action.remote_modified {
                NativeActionType::Upload
            } else {
                NativeActionType::Download
            }
        }
        NativeConflictStrategy::LocalWins => NativeActionType::Upload,
        NativeConflictStrategy::RemoteWins => NativeActionType::Download,
        NativeConflictStrategy::KeepBoth => NativeActionType::Conflict,
    };

    NativeSyncAction {
        action_type: resolved_type,
        ..action
    }
}

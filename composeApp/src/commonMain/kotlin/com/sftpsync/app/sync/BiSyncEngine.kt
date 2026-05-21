package com.sftpsync.app.sync

import com.sftpsync.app.models.*

object BiSyncEngine {

    /**
     * Filters out files matching exclusion list.
     */
    fun isExcluded(relativePath: String, exclusions: List<String>): Boolean {
        if (exclusions.isEmpty()) return false
        val normalized = relativePath.replace("\\", "/")
        return exclusions.any { exclusion ->
            val cleanEx = exclusion.replace("\\", "/").trim()
            if (cleanEx.isEmpty()) return@any false
            
            // Simple matchers
            normalized.contains("/$cleanEx/") || 
            normalized.startsWith("$cleanEx/") || 
            normalized.endsWith("/$cleanEx") || 
            normalized == cleanEx ||
            // Glob-like extensions: e.g. "*.txt"
            (cleanEx.startsWith("*.") && normalized.endsWith(cleanEx.substring(1)))
        }
    }

    /**
     * The core 3-way synchronization decision engine.
     * Takes current local, current remote, and last sync states, and calculates required actions.
     */
    fun calculateSyncActions(
        localFiles: Map<String, SyncFile>,
        remoteFiles: Map<String, SyncFile>,
        lastState: SyncState,
        exclusions: List<String>
    ): List<SyncAction> {
        val actions = mutableListOf<SyncAction>()
        
        // Gather all unique relative paths (ignoring directory objects themselves, as they are created implicitly)
        val allPaths = (localFiles.keys + remoteFiles.keys + lastState.files.keys)
            .filter { path -> !isExcluded(path, exclusions) }
            .toSet()

        val lastStateFiles = lastState.files

        for (path in allPaths) {
            val local = localFiles[path]
            val remote = remoteFiles[path]
            val meta = lastStateFiles[path]

            // Skip if both exist and are directories (directories handled implicitly)
            if ((local?.isDirectory == true) || (remote?.isDirectory == true)) {
                continue
            }

            when {
                // 1. Exists in Local, Remote, and Last State
                local != null && remote != null && meta != null -> {
                    val localChanged = local.lastModified != meta.lastModifiedLocal || local.size != meta.size
                    val remoteChanged = remote.lastModified != meta.lastModifiedRemote || remote.size != meta.size

                    when {
                        localChanged && remoteChanged -> {
                            // CONFLICT: Both modified since last sync
                            actions.add(SyncAction(
                                relativePath = path,
                                actionType = SyncActionType.CONFLICT,
                                isDirectory = false,
                                size = local.size,
                                localLastModified = local.lastModified,
                                remoteLastModified = remote.lastModified
                            ))
                        }
                        localChanged -> {
                            // Local modified, Remote not modified -> UPLOAD
                            actions.add(SyncAction(
                                relativePath = path,
                                actionType = SyncActionType.UPLOAD,
                                isDirectory = false,
                                size = local.size,
                                localLastModified = local.lastModified,
                                remoteLastModified = remote.lastModified
                            ))
                        }
                        remoteChanged -> {
                            // Remote modified, Local not modified -> DOWNLOAD
                            actions.add(SyncAction(
                                relativePath = path,
                                actionType = SyncActionType.DOWNLOAD,
                                isDirectory = false,
                                size = remote.size,
                                localLastModified = local.lastModified,
                                remoteLastModified = remote.lastModified
                            ))
                        }
                        else -> {
                            // No changes -> NONE
                            actions.add(SyncAction(path, SyncActionType.NONE, false))
                        }
                    }
                }

                // 2. Exists in Local and Remote, but NOT in Last State (both added independently)
                local != null && remote != null && meta == null -> {
                    // Check if they are identical by size (quick check)
                    if (local.size == remote.size) {
                        // Consider them in sync, just record in state
                        actions.add(SyncAction(path, SyncActionType.NONE, false))
                    } else {
                        // Conflict
                        actions.add(SyncAction(
                            relativePath = path,
                            actionType = SyncActionType.CONFLICT,
                            isDirectory = false,
                            size = local.size,
                            localLastModified = local.lastModified,
                            remoteLastModified = remote.lastModified
                        ))
                    }
                }

                // 3. Exists in Local, NOT in Remote, exists in Last State (Remote deleted it)
                local != null && remote == null && meta != null -> {
                    val localChanged = local.lastModified != meta.lastModifiedLocal || local.size != meta.size
                    if (localChanged) {
                        // Remote deleted, Local modified -> Conflict
                        actions.add(SyncAction(
                            relativePath = path,
                            actionType = SyncActionType.CONFLICT,
                            isDirectory = false,
                            size = local.size,
                            localLastModified = local.lastModified,
                            remoteLastModified = 0
                        ))
                    } else {
                        // Remote deleted, Local not modified -> DELETE_LOCAL
                        actions.add(SyncAction(
                            relativePath = path,
                            actionType = SyncActionType.DELETE_LOCAL,
                            isDirectory = false,
                            size = local.size
                        ))
                    }
                }

                // 4. NOT in Local, exists in Remote, exists in Last State (Local deleted it)
                local == null && remote != null && meta != null -> {
                    val remoteChanged = remote.lastModified != meta.lastModifiedRemote || remote.size != meta.size
                    if (remoteChanged) {
                        // Local deleted, Remote modified -> Conflict
                        actions.add(SyncAction(
                            relativePath = path,
                            actionType = SyncActionType.CONFLICT,
                            isDirectory = false,
                            size = remote.size,
                            localLastModified = 0,
                            remoteLastModified = remote.lastModified
                        ))
                    } else {
                        // Local deleted, Remote not modified -> DELETE_REMOTE
                        actions.add(SyncAction(
                            relativePath = path,
                            actionType = SyncActionType.DELETE_REMOTE,
                            isDirectory = false,
                            size = remote.size
                        ))
                    }
                }

                // 5. Exists in Local, NOT in Remote, NOT in Last State (Added to Local)
                local != null && remote == null && meta == null -> {
                    actions.add(SyncAction(
                        relativePath = path,
                        actionType = SyncActionType.UPLOAD,
                        isDirectory = false,
                        size = local.size,
                        localLastModified = local.lastModified
                    ))
                }

                // 6. NOT in Local, exists in Remote, NOT in Last State (Added to Remote)
                local == null && remote != null && meta == null -> {
                    actions.add(SyncAction(
                        relativePath = path,
                        actionType = SyncActionType.DOWNLOAD,
                        isDirectory = false,
                        size = remote.size,
                        remoteLastModified = remote.lastModified
                    ))
                }

                // 7. NOT in Local, NOT in Remote, exists in Last State (Deleted on both sides)
                else -> {
                    // Already deleted everywhere, do nothing
                    actions.add(SyncAction(path, SyncActionType.NONE, false))
                }
            }
        }

        return actions.filter { it.actionType != SyncActionType.NONE }
    }

    /**
     * Resolves a conflict based on the selected strategy.
     * Returns the resolved action, or null if the file should be skipped.
     */
    fun resolveConflict(
        action: SyncAction,
        strategy: ConflictStrategy
    ): SyncAction? {
        if (action.actionType != SyncActionType.CONFLICT) return action

        return when (strategy) {
            ConflictStrategy.NEWER_WINS -> {
                if (action.localLastModified >= action.remoteLastModified) {
                    // Local is newer -> UPLOAD
                    SyncAction(action.relativePath, SyncActionType.UPLOAD, action.isDirectory, action.size, action.localLastModified, action.remoteLastModified)
                } else {
                    // Remote is newer -> DOWNLOAD
                    SyncAction(action.relativePath, SyncActionType.DOWNLOAD, action.isDirectory, action.size, action.localLastModified, action.remoteLastModified)
                }
            }
            ConflictStrategy.LOCAL_WINS -> {
                SyncAction(action.relativePath, SyncActionType.UPLOAD, action.isDirectory, action.size, action.localLastModified, action.remoteLastModified)
            }
            ConflictStrategy.REMOTE_WINS -> {
                SyncAction(action.relativePath, SyncActionType.DOWNLOAD, action.isDirectory, action.size, action.localLastModified, action.remoteLastModified)
            }
            ConflictStrategy.KEEP_BOTH -> {
                // Return original conflict, execution engine will handle renaming
                action
            }
        }
    }
}

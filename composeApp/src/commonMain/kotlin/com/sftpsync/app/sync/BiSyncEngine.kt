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
    suspend fun calculateSyncActions(
        localFiles: Map<String, SyncFile>,
        remoteFiles: Map<String, SyncFile>,
        lastState: SyncState,
        exclusions: List<String>,
        syncCondition: SyncCondition,
        getLocalHash: suspend (String) -> String?,
        getRemoteHash: suspend (String) -> String?
    ): List<SyncAction> {
        val actions = mutableListOf<SyncAction>()
        
        // Gather all unique relative paths (ignoring directory objects themselves, as they are created implicitly)
        val allPaths = (localFiles.keys + remoteFiles.keys + lastState.files.keys)
            .filter { path -> !isExcluded(path, exclusions) }
            .toSet()

        val lastStateFiles = lastState.files

        suspend fun getOrComputeLocalHash(path: String, local: SyncFile, meta: SyncFileMetadata?): String? {
            if (meta != null && meta.hash != null && local.size == meta.size && local.lastModified == meta.lastModifiedLocal) {
                return meta.hash
            }
            return getLocalHash(path)
        }

        suspend fun getOrComputeRemoteHash(path: String, remote: SyncFile, meta: SyncFileMetadata?): String? {
            if (meta != null && meta.hash != null && remote.size == meta.size && remote.lastModified == meta.lastModifiedRemote) {
                return meta.hash
            }
            return getRemoteHash(path)
        }

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
                    val localHash = getOrComputeLocalHash(path, local, meta)
                    val remoteHash = getOrComputeRemoteHash(path, remote, meta)

                    val localChanged: Boolean
                    val remoteChanged: Boolean

                    if (localHash != null && remoteHash != null && meta.hash != null) {
                        localChanged = localHash != meta.hash
                        remoteChanged = remoteHash != meta.hash
                    } else {
                        // Fallback to size/mtime
                        localChanged = when (syncCondition) {
                            SyncCondition.TIME_DIFFERENT -> local.lastModified != meta.lastModifiedLocal
                            SyncCondition.TIME_AND_SIZE_DIFFERENT -> local.lastModified != meta.lastModifiedLocal && local.size != meta.size
                            SyncCondition.SIZE_DIFFERENT -> local.size != meta.size
                        }
                        remoteChanged = when (syncCondition) {
                            SyncCondition.TIME_DIFFERENT -> remote.lastModified != meta.lastModifiedRemote
                            SyncCondition.TIME_AND_SIZE_DIFFERENT -> remote.lastModified != meta.lastModifiedRemote && remote.size != meta.size
                            SyncCondition.SIZE_DIFFERENT -> remote.size != meta.size
                        }
                    }

                    when {
                        localChanged && remoteChanged -> {
                            if (localHash != null && remoteHash != null && localHash == remoteHash) {
                                // Content is identical, no conflict, resolve to NONE
                                actions.add(SyncAction(path, SyncActionType.NONE, false))
                            } else {
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
                    val localHash = getLocalHash(path)
                    val remoteHash = getRemoteHash(path)

                    val areIdentical: Boolean
                    if (localHash != null && remoteHash != null) {
                        areIdentical = localHash == remoteHash
                    } else {
                        areIdentical = when (syncCondition) {
                            SyncCondition.TIME_DIFFERENT -> local.lastModified == remote.lastModified
                            SyncCondition.TIME_AND_SIZE_DIFFERENT -> local.lastModified == remote.lastModified && local.size == remote.size
                            SyncCondition.SIZE_DIFFERENT -> local.size == remote.size
                        }
                    }

                    if (areIdentical) {
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
                    val localHash = getOrComputeLocalHash(path, local, meta)
                    val localChanged: Boolean
                    if (localHash != null && meta.hash != null) {
                        localChanged = localHash != meta.hash
                    } else {
                        localChanged = when (syncCondition) {
                            SyncCondition.TIME_DIFFERENT -> local.lastModified != meta.lastModifiedLocal
                            SyncCondition.TIME_AND_SIZE_DIFFERENT -> local.lastModified != meta.lastModifiedLocal && local.size != meta.size
                            SyncCondition.SIZE_DIFFERENT -> local.size != meta.size
                        }
                    }

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
                    val remoteHash = getOrComputeRemoteHash(path, remote, meta)
                    val remoteChanged: Boolean
                    if (remoteHash != null && meta.hash != null) {
                        remoteChanged = remoteHash != meta.hash
                    } else {
                        remoteChanged = when (syncCondition) {
                            SyncCondition.TIME_DIFFERENT -> remote.lastModified != meta.lastModifiedRemote
                            SyncCondition.TIME_AND_SIZE_DIFFERENT -> remote.lastModified != meta.lastModifiedRemote && remote.size != meta.size
                            SyncCondition.SIZE_DIFFERENT -> remote.size != meta.size
                        }
                    }

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

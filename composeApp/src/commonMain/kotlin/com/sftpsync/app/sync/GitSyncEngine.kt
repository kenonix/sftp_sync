package com.sftpsync.app.sync

import com.sftpsync.app.models.*
import com.sftpsync.app.sftp.GitClient
import com.sftpsync.app.sftp.GitStatus

object GitSyncEngine {

    fun calculateSyncActions(
        localFiles: Map<String, SyncFile>,
        gitStatus: GitStatus,
        lastState: GitSyncState,
        exclusions: List<String>,
        syncCondition: SyncCondition
    ): List<SyncAction> {
        val actions = mutableListOf<SyncAction>()

        // Git 상태의 모든 파일을 처리
        val allPaths = mutableSetOf<String>()
        allPaths.addAll(localFiles.keys)
        allPaths.addAll(gitStatus.added)
        allPaths.addAll(gitStatus.modified)
        allPaths.addAll(gitStatus.deleted)
        allPaths.addAll(lastState.trackedFiles.keys)

        val filteredPaths = allPaths.filter { path -> !BiSyncEngine.isExcluded(path, exclusions) }

        for (path in filteredPaths) {
            val local = localFiles[path]
            val wasTracked = lastState.trackedFiles.containsKey(path)
            val lastModified = lastState.trackedFiles[path] ?: 0L

            when {
                // 파일이 Git에서 수정됨
                path in gitStatus.modified -> {
                    if (local != null) {
                        actions.add(
                            SyncAction(
                                relativePath = path,
                                actionType = SyncActionType.UPLOAD,
                                isDirectory = false,
                                size = local.size,
                                localLastModified = local.lastModified
                            )
                        )
                    }
                }

                // 파일이 Git에 추가됨 (새로운 파일)
                path in gitStatus.added -> {
                    if (local != null && !wasTracked) {
                        actions.add(
                            SyncAction(
                                relativePath = path,
                                actionType = SyncActionType.UPLOAD,
                                isDirectory = false,
                                size = local.size,
                                localLastModified = local.lastModified
                            )
                        )
                    }
                }

                // 파일이 Git에서 삭제됨
                path in gitStatus.deleted -> {
                    if (wasTracked && local == null) {
                        actions.add(
                            SyncAction(
                                relativePath = path,
                                actionType = SyncActionType.DELETE_LOCAL,
                                isDirectory = false
                            )
                        )
                    }
                }

                // Git에서 제거되었지만 로컬에는 있는 파일
                wasTracked && local != null && path !in gitStatus.added && path !in gitStatus.modified -> {
                    // 파일이 여전히 로컬에 있으면 다시 추가해야 함
                    actions.add(
                        SyncAction(
                            relativePath = path,
                            actionType = SyncActionType.UPLOAD,
                            isDirectory = false,
                            size = local.size,
                            localLastModified = local.lastModified
                        )
                    )
                }

                // 로컬에만 있는 파일 (Git에 없음)
                local != null && !wasTracked && path !in gitStatus.added -> {
                    actions.add(
                        SyncAction(
                            relativePath = path,
                            actionType = SyncActionType.UPLOAD,
                            isDirectory = false,
                            size = local.size,
                            localLastModified = local.lastModified
                        )
                    )
                }
            }
        }

        return actions.filter { it.actionType != SyncActionType.NONE }
    }

    fun resolveConflict(
        action: SyncAction,
        strategy: ConflictStrategy
    ): SyncAction? {
        if (action.actionType != SyncActionType.CONFLICT) return action

        return when (strategy) {
            ConflictStrategy.NEWER_WINS -> {
                if (action.localLastModified >= action.remoteLastModified) {
                    SyncAction(action.relativePath, SyncActionType.UPLOAD, action.isDirectory, action.size, action.localLastModified, action.remoteLastModified)
                } else {
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
                action
            }
        }
    }
}

package com.sftpsync.app.sync

import com.sftpsync.app.models.*
import com.sftpsync.app.sftp.LocalFileClient
import com.sftpsync.app.sftp.SftpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class BiSyncEngineRunner(
    private val sftpClient: SftpClient,
    private val localClient: LocalFileClient
) {

    /**
     * Executes the bi-directional synchronization process for a given profile.
     * Reports progress and logs back to the caller.
     * Returns the updated SyncState.
     */
    suspend fun executeSync(
        profile: SyncProfile,
        lastState: SyncState,
        onProgress: (String, Float) -> Unit,
        onLog: (SyncLog) -> Unit,
        checkDirectoryApproval: suspend (isLocal: Boolean, path: String) -> Boolean
    ): SyncState = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        onProgress("원격 SFTP 서버 연결 중...", 0.02f)

        if (!sftpClient.connect()) {
            val errLog = SyncLog(
                timestamp = timestamp,
                profileId = profile.id,
                profileName = profile.name,
                relativePath = "",
                actionType = "CONNECT",
                status = SyncLogStatus.ERROR,
                message = "SFTP 서버 연결에 실패했습니다 (${profile.host}:${profile.port})"
            )
            onLog(errLog)
            onProgress("SFTP 연결 실패", 1.0f)
            return@withContext lastState
        }

        // 1. Check local directory existence
        val localPath = profile.localPath
        if (!localClient.exists(localPath)) {
            val approved = checkDirectoryApproval(true, localPath)
            if (approved) {
                val created = localClient.createDirectory(localPath)
                if (created) {
                    onLog(SyncLog(
                        timestamp = System.currentTimeMillis(),
                        profileId = profile.id,
                        profileName = profile.name,
                        relativePath = "",
                        actionType = "CREATE_DIR",
                        status = SyncLogStatus.SUCCESS,
                        message = "로컬 폴더를 자동 생성했습니다: $localPath"
                    ))
                } else {
                    onLog(SyncLog(
                        timestamp = System.currentTimeMillis(),
                        profileId = profile.id,
                        profileName = profile.name,
                        relativePath = "",
                        actionType = "CREATE_DIR",
                        status = SyncLogStatus.ERROR,
                        message = "로컬 폴더 생성에 실패했습니다: $localPath"
                    ))
                    sftpClient.disconnect()
                    return@withContext lastState
                }
            } else {
                onLog(SyncLog(
                    timestamp = System.currentTimeMillis(),
                    profileId = profile.id,
                    profileName = profile.name,
                    relativePath = "",
                    actionType = "SYNC",
                    status = SyncLogStatus.SKIPPED,
                    message = "로컬 폴더 미존재로 인해 동기화가 취소되었습니다."
                ))
                onProgress("로컬 폴더 미존재로 취소됨", 1.0f)
                sftpClient.disconnect()
                return@withContext lastState
            }
        }

        // 2. Check remote directory existence
        val remotePath = profile.remotePath
        if (!sftpClient.exists(remotePath)) {
            val approved = checkDirectoryApproval(false, remotePath)
            if (approved) {
                val created = createRemoteDirectoryRecursive(remotePath)
                if (created) {
                    onLog(SyncLog(
                        timestamp = System.currentTimeMillis(),
                        profileId = profile.id,
                        profileName = profile.name,
                        relativePath = "",
                        actionType = "CREATE_DIR",
                        status = SyncLogStatus.SUCCESS,
                        message = "원격 폴더를 자동 생성했습니다: $remotePath"
                    ))
                } else {
                    onLog(SyncLog(
                        timestamp = System.currentTimeMillis(),
                        profileId = profile.id,
                        profileName = profile.name,
                        relativePath = "",
                        actionType = "CREATE_DIR",
                        status = SyncLogStatus.ERROR,
                        message = "원격 폴더 생성에 실패했습니다: $remotePath"
                    ))
                    sftpClient.disconnect()
                    return@withContext lastState
                }
            } else {
                onLog(SyncLog(
                    timestamp = System.currentTimeMillis(),
                    profileId = profile.id,
                    profileName = profile.name,
                    relativePath = "",
                    actionType = "SYNC",
                    status = SyncLogStatus.SKIPPED,
                    message = "원격 폴더 미존재로 인해 동기화가 취소되었습니다."
                ))
                onProgress("원격 폴더 미존재로 취소됨", 1.0f)
                sftpClient.disconnect()
                return@withContext lastState
            }
        }

        try {
            onProgress("원격 파일 목록 수집 중...", 0.08f)
            val remoteFiles = sftpClient.listFiles(profile.remotePath)
                .associateBy { it.relativePath }

            onProgress("로컬 파일 목록 수집 중...", 0.15f)
            val localFiles = localClient.listFiles(profile.localPath)
                .associateBy { it.relativePath }

            onProgress("동기화 변경사항 비교 중...", 0.20f)
            val pendingActions = BiSyncEngine.calculateSyncActions(
                localFiles = localFiles,
                remoteFiles = remoteFiles,
                lastState = lastState,
                exclusions = profile.exclusions
            )

            if (pendingActions.isEmpty()) {
                onProgress("동기화 완료 (변경 사항 없음)", 1.0f)
                onLog(SyncLog(
                    timestamp = System.currentTimeMillis(),
                    profileId = profile.id,
                    profileName = profile.name,
                    relativePath = "",
                    actionType = "SYNC",
                    status = SyncLogStatus.SUCCESS,
                    message = "동기화 완료: 변경 사항이 없습니다."
                ))
                return@withContext lastState.copy(lastSyncTime = timestamp)
            }

            val totalActions = pendingActions.size
            var completedActions = 0
            val newFilesMetadata = lastState.files.toMutableMap()

            for (action in pendingActions) {
                val currentProgress = 0.20f + (0.75f * (completedActions.toFloat() / totalActions.toFloat()))
                val actionLabel = when (action.actionType) {
                    SyncActionType.UPLOAD -> "업로드"
                    SyncActionType.DOWNLOAD -> "다운로드"
                    SyncActionType.DELETE_LOCAL -> "로컬 삭제"
                    SyncActionType.DELETE_REMOTE -> "원격 삭제"
                    SyncActionType.CONFLICT -> "충돌 해결"
                    SyncActionType.NONE -> ""
                }
                
                onProgress("$actionLabel 진행 중: ${action.relativePath}", currentProgress)

                try {
                    when (action.actionType) {
                        SyncActionType.UPLOAD -> {
                            val localFile = "${profile.localPath}/${action.relativePath}"
                            val remoteFile = "${profile.remotePath}/${action.relativePath}"
                            
                            val ok = sftpClient.uploadFile(localFile, remoteFile) { bytes ->
                                // Optional fine-grained progress reporting
                            }
                            
                            if (ok) {
                                val remoteModified = sftpClient.getFileLastModified(remoteFile)
                                val localModified = localFiles[action.relativePath]?.lastModified ?: System.currentTimeMillis()
                                
                                newFilesMetadata[action.relativePath] = SyncFileMetadata(
                                    relativePath = action.relativePath,
                                    size = action.size,
                                    lastModifiedLocal = localModified,
                                    lastModifiedRemote = remoteModified,
                                    isDirectory = false
                                )
                                
                                onLog(SyncLog(
                                    timestamp = System.currentTimeMillis(),
                                    profileId = profile.id,
                                    profileName = profile.name,
                                    relativePath = action.relativePath,
                                    actionType = "UPLOAD",
                                    status = SyncLogStatus.SUCCESS,
                                    message = "업로드 완료 (크기: ${formatSize(action.size)})"
                                ))
                            } else {
                                throw Exception("SFTP 업로드 실패")
                            }
                        }

                        SyncActionType.DOWNLOAD -> {
                            val localFile = "${profile.localPath}/${action.relativePath}"
                            val remoteFile = "${profile.remotePath}/${action.relativePath}"
                            
                            // Ensure local parent directories exist
                            val parentFile = java.io.File(localFile).parentFile
                            if (parentFile != null && !parentFile.exists()) {
                                parentFile.mkdirs()
                            }
                            
                            val ok = sftpClient.downloadFile(remoteFile, localFile) { bytes -> }
                            
                            if (ok) {
                                // Sync timestamps: set local modification time to match remote
                                val remoteModified = action.remoteLastModified
                                localClient.setLastModified(localFile, remoteModified)
                                
                                newFilesMetadata[action.relativePath] = SyncFileMetadata(
                                    relativePath = action.relativePath,
                                    size = action.size,
                                    lastModifiedLocal = remoteModified,
                                    lastModifiedRemote = remoteModified,
                                    isDirectory = false
                                )
                                
                                onLog(SyncLog(
                                    timestamp = System.currentTimeMillis(),
                                    profileId = profile.id,
                                    profileName = profile.name,
                                    relativePath = action.relativePath,
                                    actionType = "DOWNLOAD",
                                    status = SyncLogStatus.SUCCESS,
                                    message = "다운로드 완료 (크기: ${formatSize(action.size)})"
                                ))
                            } else {
                                throw Exception("SFTP 다운로드 실패")
                            }
                        }

                        SyncActionType.DELETE_LOCAL -> {
                            val localFile = "${profile.localPath}/${action.relativePath}"
                            val ok = localClient.deleteFile(localFile)
                            if (ok || !localClient.exists(localFile)) {
                                newFilesMetadata.remove(action.relativePath)
                                onLog(SyncLog(
                                    timestamp = System.currentTimeMillis(),
                                    profileId = profile.id,
                                    profileName = profile.name,
                                    relativePath = action.relativePath,
                                    actionType = "DELETE_LOCAL",
                                    status = SyncLogStatus.SUCCESS,
                                    message = "로컬 파일 삭제 완료"
                                ))
                            } else {
                                throw Exception("로컬 파일 삭제 실패")
                            }
                        }

                        SyncActionType.DELETE_REMOTE -> {
                            val remoteFile = "${profile.remotePath}/${action.relativePath}"
                            val ok = sftpClient.deleteFile(remoteFile)
                            if (ok) {
                                newFilesMetadata.remove(action.relativePath)
                                onLog(SyncLog(
                                    timestamp = System.currentTimeMillis(),
                                    profileId = profile.id,
                                    profileName = profile.name,
                                    relativePath = action.relativePath,
                                    actionType = "DELETE_REMOTE",
                                    status = SyncLogStatus.SUCCESS,
                                    message = "원격 파일 삭제 완료"
                                ))
                            } else {
                                throw Exception("원격 파일 삭제 실패")
                            }
                        }

                        SyncActionType.CONFLICT -> {
                            // Resolve conflict based on strategy
                            val resolved = BiSyncEngine.resolveConflict(action, profile.conflictStrategy)
                            if (resolved != null && resolved.actionType != SyncActionType.CONFLICT) {
                                // Execute resolved action (either UPLOAD or DOWNLOAD)
                                val resolvedAction = action.copy(actionType = resolved.actionType)
                                // We call executeSync recursively for just this single action to keep it DRY!
                                // Wait, running it via a local helper is cleaner and avoids full sync recursion.
                                runResolvedConflict(resolvedAction, profile, localFiles, remoteFiles, newFilesMetadata, onLog)
                            } else if (profile.conflictStrategy == ConflictStrategy.KEEP_BOTH) {
                                // KEEP_BOTH strategy: Rename files on both sides to keep both contents
                                executeKeepBoth(action, profile, localFiles, remoteFiles, newFilesMetadata, onLog)
                            } else {
                                onLog(SyncLog(
                                    timestamp = System.currentTimeMillis(),
                                    profileId = profile.id,
                                    profileName = profile.name,
                                    relativePath = action.relativePath,
                                    actionType = "CONFLICT",
                                    status = SyncLogStatus.SKIPPED,
                                    message = "충돌이 감지되어 건너뛰었습니다 (수동 확인 필요)"
                                ))
                            }
                        }
                        else -> {}
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    onLog(SyncLog(
                        timestamp = System.currentTimeMillis(),
                        profileId = profile.id,
                        profileName = profile.name,
                        relativePath = action.relativePath,
                        actionType = action.actionType.name,
                        status = SyncLogStatus.ERROR,
                        message = "작업 실패: ${e.message}"
                    ))
                }
                
                completedActions++
            }

            onProgress("동기화 완료", 1.0f)
            return@withContext SyncState(
                profileId = profile.id,
                lastSyncTime = System.currentTimeMillis(),
                files = newFilesMetadata
            )

        } finally {
            sftpClient.disconnect()
        }
    }

    private fun runResolvedConflict(
        resolved: SyncAction,
        profile: SyncProfile,
        localFiles: Map<String, SyncFile>,
        remoteFiles: Map<String, SyncFile>,
        newMetadata: MutableMap<String, SyncFileMetadata>,
        onLog: (SyncLog) -> Unit
    ) {
        val localFile = "${profile.localPath}/${resolved.relativePath}"
        val remoteFile = "${profile.remotePath}/${resolved.relativePath}"
        
        if (resolved.actionType == SyncActionType.UPLOAD) {
            val ok = sftpClient.uploadFile(localFile, remoteFile) {}
            if (ok) {
                val remoteModified = sftpClient.getFileLastModified(remoteFile)
                val localModified = localFiles[resolved.relativePath]?.lastModified ?: System.currentTimeMillis()
                newMetadata[resolved.relativePath] = SyncFileMetadata(
                    relativePath = resolved.relativePath,
                    size = resolved.size,
                    lastModifiedLocal = localModified,
                    lastModifiedRemote = remoteModified,
                    isDirectory = false
                )
                onLog(SyncLog(
                    timestamp = System.currentTimeMillis(),
                    profileId = profile.id,
                    profileName = profile.name,
                    relativePath = resolved.relativePath,
                    actionType = "CONFLICT_RESOLVED_UPLOAD",
                    status = SyncLogStatus.SUCCESS,
                    message = "충돌 해결: 로컬 덮어쓰기 완료"
                ))
            } else {
                throw Exception("충돌 해결 중 업로드 실패")
            }
        } else if (resolved.actionType == SyncActionType.DOWNLOAD) {
            val ok = sftpClient.downloadFile(remoteFile, localFile) {}
            if (ok) {
                val remoteModified = resolved.remoteLastModified
                localClient.setLastModified(localFile, remoteModified)
                newMetadata[resolved.relativePath] = SyncFileMetadata(
                    relativePath = resolved.relativePath,
                    size = resolved.size,
                    lastModifiedLocal = remoteModified,
                    lastModifiedRemote = remoteModified,
                    isDirectory = false
                )
                onLog(SyncLog(
                    timestamp = System.currentTimeMillis(),
                    profileId = profile.id,
                    profileName = profile.name,
                    relativePath = resolved.relativePath,
                    actionType = "CONFLICT_RESOLVED_DOWNLOAD",
                    status = SyncLogStatus.SUCCESS,
                    message = "충돌 해결: 원격 덮어쓰기 완료"
                ))
            } else {
                throw Exception("충돌 해결 중 다운로드 실패")
            }
        }
    }

    private fun executeKeepBoth(
        action: SyncAction,
        profile: SyncProfile,
        localFiles: Map<String, SyncFile>,
        remoteFiles: Map<String, SyncFile>,
        newMetadata: MutableMap<String, SyncFileMetadata>,
        onLog: (SyncLog) -> Unit
    ) {
        val relPath = action.relativePath
        val nameIndex = relPath.lastIndexOf('/')
        val parent = if (nameIndex >= 0) relPath.substring(0, nameIndex) else ""
        val filename = if (nameIndex >= 0) relPath.substring(nameIndex + 1) else relPath
        
        val extIndex = filename.lastIndexOf('.')
        val (base, ext) = if (extIndex > 0) {
            filename.substring(0, extIndex) to filename.substring(extIndex)
        } else {
            filename to ""
        }

        // Generate renamed relative paths
        val localRenamed = if (parent.isEmpty()) "${base}_local$ext" else "$parent/${base}_local$ext"
        val remoteRenamed = if (parent.isEmpty()) "${base}_remote$ext" else "$parent/${base}_remote$ext"

        val localFileOriginal = "${profile.localPath}/$relPath"
        val localFileRenamed = "${profile.localPath}/$localRenamed"
        val localFileRemoteRenamed = "${profile.localPath}/$remoteRenamed"
        
        val remoteFileOriginal = "${profile.remotePath}/$relPath"
        val remoteFileRenamed = "${profile.remotePath}/$remoteRenamed"
        val remoteFileLocalRenamed = "${profile.remotePath}/$localRenamed"

        // 1. Rename Local Original to Local_Local
        val localFileObj = java.io.File(localFileOriginal)
        val renamedLocalFileObj = java.io.File(localFileRenamed)
        val renameLocalOk = localFileObj.renameTo(renamedLocalFileObj)

        if (!renameLocalOk) {
            throw Exception("로컬 충돌 파일 이름 변경 실패")
        }

        // 2. Download Remote Original to Local_Remote
        val downloadOk = sftpClient.downloadFile(remoteFileOriginal, localFileRemoteRenamed) {}
        if (!downloadOk) {
            // Rollback local rename
            renamedLocalFileObj.renameTo(localFileObj)
            throw Exception("원격 충돌 파일 다운로드 실패")
        }

        // 3. Set remote mTime to local downloaded file
        val remoteOriginal = remoteFiles[relPath] ?: throw Exception("원격 원본 메타데이터 손실")
        localClient.setLastModified(localFileRemoteRenamed, remoteOriginal.lastModified)

        // 4. Upload Local_Local to Remote
        val uploadOk = sftpClient.uploadFile(localFileRenamed, remoteFileLocalRenamed) {}
        if (!uploadOk) {
            throw Exception("이름이 변경된 로컬 파일 업로드 실패")
        }

        // 5. Delete Original Remote File
        sftpClient.deleteFile(remoteFileOriginal)

        // 6. Update metadata state: remove original and add the two renamed files
        newMetadata.remove(relPath)

        // Record local renamed metadata
        val localModifiedTime = renamedLocalFileObj.lastModified()
        val localUploadedRemoteTime = sftpClient.getFileLastModified(remoteFileLocalRenamed)
        newMetadata[localRenamed] = SyncFileMetadata(
            relativePath = localRenamed,
            size = renamedLocalFileObj.length(),
            lastModifiedLocal = localModifiedTime,
            lastModifiedRemote = localUploadedRemoteTime,
            isDirectory = false
        )

        // Record remote renamed metadata
        newMetadata[remoteRenamed] = SyncFileMetadata(
            relativePath = remoteRenamed,
            size = java.io.File(localFileRemoteRenamed).length(),
            lastModifiedLocal = remoteOriginal.lastModified,
            lastModifiedRemote = remoteOriginal.lastModified,
            isDirectory = false
        )

        onLog(SyncLog(
            timestamp = System.currentTimeMillis(),
            profileId = profile.id,
            profileName = profile.name,
            relativePath = relPath,
            actionType = "CONFLICT_KEEP_BOTH",
            status = SyncLogStatus.WARNING,
            message = "충돌 파일 보존: '${localRenamed}' 및 '${remoteRenamed}' 생성 완료"
        ))
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1] + ""
        return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    private fun createRemoteDirectoryRecursive(remotePath: String): Boolean {
        val parts = remotePath.split("/")
        var current = ""
        var success = true
        for (part in parts) {
            if (part.isEmpty()) continue
            current += "/$part"
            if (!sftpClient.exists(current)) {
                success = sftpClient.createDirectory(current) && success
            }
        }
        return success
    }
}

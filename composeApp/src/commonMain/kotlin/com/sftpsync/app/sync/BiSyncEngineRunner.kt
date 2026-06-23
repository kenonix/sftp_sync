package com.sftpsync.app.sync

import com.sftpsync.app.models.*
import com.sftpsync.app.sftp.LocalFileClient
import com.sftpsync.app.sftp.SftpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import com.sftpsync.app.utils.createSftpClient

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
        concurrency: Int,
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

        val sftpClients = mutableListOf(sftpClient)
        if (concurrency > 1) {
            onProgress("추가 SFTP 연결 구성 중...", 0.05f)
            for (i in 1 until concurrency) {
                val client = createSftpClient(profile)
                if (client.connect()) {
                    sftpClients.add(client)
                } else {
                    client.disconnect()
                }
            }
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

            val allPaths = (localFiles.keys + remoteFiles.keys + lastState.files.keys)
                .filter { path -> !BiSyncEngine.isExcluded(path, profile.exclusions) }
                .toSet()

            val lastStateFiles = lastState.files
            val pathsNeedingLocalHash = mutableListOf<String>()
            val pathsNeedingRemoteHash = mutableListOf<String>()

            for (path in allPaths) {
                val local = localFiles[path]
                val remote = remoteFiles[path]
                val meta = lastStateFiles[path]

                if ((local?.isDirectory == true) || (remote?.isDirectory == true)) {
                    continue
                }

                if (local != null && remote != null && meta != null) {
                    if (!(meta.hash != null && local.size == meta.size && local.lastModified == meta.lastModifiedLocal)) {
                        pathsNeedingLocalHash.add(path)
                    }
                    if (!(meta.hash != null && remote.size == meta.size && remote.lastModified == meta.lastModifiedRemote)) {
                        pathsNeedingRemoteHash.add(path)
                    }
                }
            }

            val localHashes = mutableMapOf<String, String>()
            val remoteHashes = mutableMapOf<String, String>()
            val hashMutex = Mutex()

            if (pathsNeedingLocalHash.isNotEmpty()) {
                onProgress("로컬 파일 해시 분석 중 (${pathsNeedingLocalHash.size}개)...", 0.16f)
                val localChannel = Channel<String>(Channel.UNLIMITED)
                pathsNeedingLocalHash.forEach { localChannel.trySend(it) }
                localChannel.close()

                coroutineScope {
                    repeat(concurrency) {
                        launch(Dispatchers.IO) {
                            for (path in localChannel) {
                                val hash = localClient.getFileHash("${profile.localPath}/$path")
                                if (hash != null) {
                                    hashMutex.withLock {
                                        localHashes[path] = hash
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (pathsNeedingRemoteHash.isNotEmpty()) {
                onProgress("원격 파일 해시 분석 중 (${pathsNeedingRemoteHash.size}개)...", 0.18f)
                val remoteChannel = Channel<String>(Channel.UNLIMITED)
                pathsNeedingRemoteHash.forEach { remoteChannel.trySend(it) }
                remoteChannel.close()

                coroutineScope {
                    sftpClients.map { client ->
                        launch(Dispatchers.IO) {
                            for (path in remoteChannel) {
                                val hash = client.getFileHash("${profile.remotePath}/$path")
                                if (hash != null) {
                                    hashMutex.withLock {
                                        remoteHashes[path] = hash
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val pendingActions = BiSyncEngine.calculateSyncActions(
                localFiles = localFiles,
                remoteFiles = remoteFiles,
                lastState = lastState,
                exclusions = profile.exclusions,
                syncCondition = profile.syncCondition,
                getLocalHash = { relPath ->
                    localHashes[relPath] ?: localClient.getFileHash("${profile.localPath}/$relPath")
                },
                getRemoteHash = { relPath ->
                    remoteHashes[relPath] ?: sftpClient.getFileHash("${profile.remotePath}/$relPath")
                }
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

            val progressMutex = Mutex()
            val actionChannel = Channel<SyncAction>(Channel.UNLIMITED)
            pendingActions.forEach { actionChannel.trySend(it) }
            actionChannel.close()

            coroutineScope {
                sftpClients.map { client ->
                    launch(Dispatchers.IO) {
                        for (action in actionChannel) {
                            val actionLabel = when (action.actionType) {
                                SyncActionType.UPLOAD -> "업로드"
                                SyncActionType.DOWNLOAD -> "다운로드"
                                SyncActionType.DELETE_LOCAL -> "로컬 삭제"
                                SyncActionType.DELETE_REMOTE -> "원격 삭제"
                                SyncActionType.CONFLICT -> "충돌 해결"
                                SyncActionType.NONE -> ""
                            }
                            
                            var currentProgress = 0.20f
                            progressMutex.withLock {
                                currentProgress = 0.20f + (0.75f * (completedActions.toFloat() / totalActions.toFloat()))
                            }
                            onProgress("$actionLabel 진행 중: ${action.relativePath}", currentProgress)

                            try {
                                when (action.actionType) {
                                    SyncActionType.UPLOAD -> {
                                        val localFile = "${profile.localPath}/${action.relativePath}"
                                        val remoteFile = "${profile.remotePath}/${action.relativePath}"
                                        
                                        val ok = client.uploadFile(localFile, remoteFile) { bytes -> }
                                        
                                        if (ok) {
                                            val remoteModified = client.getFileLastModified(remoteFile)
                                            val localModified = localFiles[action.relativePath]?.lastModified ?: System.currentTimeMillis()
                                            val localHash = localClient.getFileHash(localFile)
                                            
                                            progressMutex.withLock {
                                                newFilesMetadata[action.relativePath] = SyncFileMetadata(
                                                    relativePath = action.relativePath,
                                                    size = action.size,
                                                    lastModifiedLocal = localModified,
                                                    lastModifiedRemote = remoteModified,
                                                    isDirectory = false,
                                                    hash = localHash
                                                )
                                            }
                                            
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
                                        
                                        val parentFile = java.io.File(localFile).parentFile
                                        if (parentFile != null && !parentFile.exists()) {
                                            progressMutex.withLock {
                                                if (!parentFile.exists()) {
                                                    parentFile.mkdirs()
                                                }
                                            }
                                        }
                                        
                                        val ok = client.downloadFile(remoteFile, localFile) { bytes -> }
                                        
                                        if (ok) {
                                            val remoteModified = action.remoteLastModified
                                            localClient.setLastModified(localFile, remoteModified)
                                            val localHash = localClient.getFileHash(localFile)
                                            
                                            progressMutex.withLock {
                                                newFilesMetadata[action.relativePath] = SyncFileMetadata(
                                                    relativePath = action.relativePath,
                                                    size = action.size,
                                                    lastModifiedLocal = remoteModified,
                                                    lastModifiedRemote = remoteModified,
                                                    isDirectory = false,
                                                    hash = localHash
                                                )
                                            }
                                            
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
                                            progressMutex.withLock {
                                                newFilesMetadata.remove(action.relativePath)
                                            }
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
                                        val ok = client.deleteFile(remoteFile)
                                        if (ok) {
                                            progressMutex.withLock {
                                                newFilesMetadata.remove(action.relativePath)
                                            }
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
                                        val resolved = BiSyncEngine.resolveConflict(action, profile.conflictStrategy)
                                        if (resolved != null && resolved.actionType != SyncActionType.CONFLICT) {
                                            val resolvedAction = action.copy(actionType = resolved.actionType)
                                            progressMutex.withLock {
                                                runResolvedConflict(client, resolvedAction, profile, localFiles, remoteFiles, newFilesMetadata, onLog)
                                            }
                                        } else if (profile.conflictStrategy == ConflictStrategy.KEEP_BOTH) {
                                            progressMutex.withLock {
                                                executeKeepBoth(client, action, profile, localFiles, remoteFiles, newFilesMetadata, onLog)
                                            }
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
                            } finally {
                                progressMutex.withLock {
                                    completedActions++
                                }
                            }
                        }
                    }
                }
            }

            onProgress("동기화 완료", 1.0f)
            return@withContext SyncState(
                profileId = profile.id,
                lastSyncTime = System.currentTimeMillis(),
                files = newFilesMetadata
            )

        } finally {
            if (concurrency > 1) {
                sftpClients.forEachIndexed { index, client ->
                    if (index > 0) {
                        try {
                            client.disconnect()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
            sftpClient.disconnect()
        }
    }

    private fun runResolvedConflict(
        client: SftpClient,
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
            val ok = client.uploadFile(localFile, remoteFile) {}
            if (ok) {
                val remoteModified = client.getFileLastModified(remoteFile)
                val localModified = localFiles[resolved.relativePath]?.lastModified ?: System.currentTimeMillis()
                val localHash = localClient.getFileHash(localFile)
                newMetadata[resolved.relativePath] = SyncFileMetadata(
                    relativePath = resolved.relativePath,
                    size = resolved.size,
                    lastModifiedLocal = localModified,
                    lastModifiedRemote = remoteModified,
                    isDirectory = false,
                    hash = localHash
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
            val ok = client.downloadFile(remoteFile, localFile) {}
            if (ok) {
                val remoteModified = resolved.remoteLastModified
                localClient.setLastModified(localFile, remoteModified)
                val localHash = localClient.getFileHash(localFile)
                newMetadata[resolved.relativePath] = SyncFileMetadata(
                    relativePath = resolved.relativePath,
                    size = resolved.size,
                    lastModifiedLocal = remoteModified,
                    lastModifiedRemote = remoteModified,
                    isDirectory = false,
                    hash = localHash
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
        client: SftpClient,
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

        val conflictTimestamp = com.sftpsync.app.utils.getConflictTimestamp()
        val remoteRenamedBase = if (parent.isEmpty()) "${base}_conflict_$conflictTimestamp$ext" else "$parent/${base}_conflict_$conflictTimestamp$ext"

        // Generate unique renamed path if conflict file already exists
        var counter = 0
        var uniqueRemoteRenamed = remoteRenamedBase
        while (java.io.File("${profile.localPath}/$uniqueRemoteRenamed").exists()) {
            counter++
            uniqueRemoteRenamed = if (parent.isEmpty()) {
                "${base}_conflict_${conflictTimestamp}_$counter$ext"
            } else {
                "$parent/${base}_conflict_${conflictTimestamp}_$counter$ext"
            }
        }
        val finalRemoteRenamed = uniqueRemoteRenamed

        val localFileOriginal = "${profile.localPath}/$relPath"
        val localFileConflict = "${profile.localPath}/$finalRemoteRenamed"
        
        val remoteFileOriginal = "${profile.remotePath}/$relPath"
        val remoteFileConflict = "${profile.remotePath}/$finalRemoteRenamed"

        // 1. Download Remote Original to Local Conflict file
        val downloadOk = client.downloadFile(remoteFileOriginal, localFileConflict) {}
        if (!downloadOk) {
            throw Exception("원격 충돌 파일 다운로드 실패")
        }

        // 2. Set remote mTime to local downloaded conflict file
        val remoteOriginal = remoteFiles[relPath] ?: throw Exception("원격 원본 메타데이터 손실")
        localClient.setLastModified(localFileConflict, remoteOriginal.lastModified)

        // 3. Upload Local Original to Remote Original (Client wins/overwrites remote original)
        val uploadOriginalOk = client.uploadFile(localFileOriginal, remoteFileOriginal) {}
        if (!uploadOriginalOk) {
            throw Exception("원격 원본 파일 업로드 실패")
        }

        // 4. Upload Local Conflict file to Remote Conflict file
        val uploadConflictOk = client.uploadFile(localFileConflict, remoteFileConflict) {}
        if (!uploadConflictOk) {
            throw Exception("이름이 변경된 충돌 파일 업로드 실패")
        }

        // 5. Update metadata state: update original (local original) and add the conflict file
        val localOriginalFile = java.io.File(localFileOriginal)
        val localOriginalModifiedTime = localOriginalFile.lastModified()
        val remoteOriginalModifiedTime = client.getFileLastModified(remoteFileOriginal)
        val localOriginalHash = localClient.getFileHash(localFileOriginal)

        newMetadata[relPath] = SyncFileMetadata(
            relativePath = relPath,
            size = localOriginalFile.length(),
            lastModifiedLocal = localOriginalModifiedTime,
            lastModifiedRemote = remoteOriginalModifiedTime,
            isDirectory = false,
            hash = localOriginalHash
        )

        val localConflictFile = java.io.File(localFileConflict)
        val localConflictHash = localClient.getFileHash(localFileConflict)
        newMetadata[finalRemoteRenamed] = SyncFileMetadata(
            relativePath = finalRemoteRenamed,
            size = localConflictFile.length(),
            lastModifiedLocal = remoteOriginal.lastModified,
            lastModifiedRemote = remoteOriginal.lastModified,
            isDirectory = false,
            hash = localConflictHash
        )

        onLog(SyncLog(
            timestamp = System.currentTimeMillis(),
            profileId = profile.id,
            profileName = profile.name,
            relativePath = relPath,
            actionType = "CONFLICT_KEEP_BOTH",
            status = SyncLogStatus.WARNING,
            message = "충돌 파일 보존: 원격 파일이 '${finalRemoteRenamed}'(으)로 보존되었습니다."
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

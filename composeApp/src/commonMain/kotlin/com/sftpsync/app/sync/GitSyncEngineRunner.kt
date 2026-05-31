package com.sftpsync.app.sync

import com.sftpsync.app.models.*
import com.sftpsync.app.sftp.GitClient
import com.sftpsync.app.sftp.LocalFileClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class GitSyncEngineRunner(
    private val gitClient: GitClient,
    private val localClient: LocalFileClient
) {

    suspend fun executeSync(
        profile: SyncProfile,
        lastState: GitSyncState,
        onProgress: (String, Float) -> Unit,
        onLog: (SyncLog) -> Unit,
        checkDirectoryApproval: suspend (isLocal: Boolean, path: String) -> Boolean
    ): GitSyncState = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        onProgress("Git 저장소 연결 중...", 0.02f)

        // Git 저장소 초기화 또는 연결
        val localPath = profile.localPath
        if (!gitClient.connect()) {
            onLog(SyncLog(
                timestamp = timestamp,
                profileId = profile.id,
                profileName = profile.name,
                relativePath = "",
                actionType = "GIT_CONNECT",
                status = SyncLogStatus.ERROR,
                message = "Git 저장소 연결에 실패했습니다"
            ))
            onProgress("Git 연결 실패", 1.0f)
            return@withContext lastState
        }

        // Git 원격 저장소에서 최신 커밋 풀(Pull)
        if (profile.gitRepositoryUrl.isNotEmpty()) {
            onProgress("Git 원격 저장소에서 풀(Pull) 진행 중...", 0.05f)
            val pullSuccess = gitClient.pull()
            if (pullSuccess) {
                onLog(SyncLog(
                    timestamp = System.currentTimeMillis(),
                    profileId = profile.id,
                    profileName = profile.name,
                    relativePath = "",
                    actionType = "PULL",
                    status = SyncLogStatus.SUCCESS,
                    message = "원격 저장소로부터 최신 변경사항 풀(Pull) 완료"
                ))
            } else {
                onLog(SyncLog(
                    timestamp = System.currentTimeMillis(),
                    profileId = profile.id,
                    profileName = profile.name,
                    relativePath = "",
                    actionType = "PULL",
                    status = SyncLogStatus.WARNING,
                    message = "원격 저장소 풀(Pull) 실패 (원격 저장소가 비어있거나 접근 권한이 없을 수 있습니다)"
                ))
            }
        }

        // 1. 로컬 디렉토리 확인
        if (!localClient.exists(localPath)) {
            val approved = checkDirectoryApproval(true, localPath)
            if (approved) {
                val created = localClient.createDirectory(localPath)
                if (!created) {
                    onLog(SyncLog(
                        timestamp = System.currentTimeMillis(),
                        profileId = profile.id,
                        profileName = profile.name,
                        relativePath = "",
                        actionType = "CREATE_DIR",
                        status = SyncLogStatus.ERROR,
                        message = "로컬 폴더 생성에 실패했습니다: $localPath"
                    ))
                    gitClient.disconnect()
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
                gitClient.disconnect()
                return@withContext lastState
            }
        }

        try {
            onProgress("로컬 파일 목록 수집 중...", 0.10f)
            val localFiles = localClient.listFiles(profile.localPath)
                .associateBy { it.relativePath }

            onProgress("Git 상태 조회 중...", 0.20f)
            val gitStatus = gitClient.getStatus()

            onProgress("동기화 변경사항 분석 중...", 0.30f)
            val pendingActions = GitSyncEngine.calculateSyncActions(
                localFiles = localFiles,
                gitStatus = gitStatus,
                lastState = lastState,
                exclusions = profile.exclusions,
                syncCondition = profile.syncCondition
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
                    message = "Git 동기화 완료: 변경 사항이 없습니다."
                ))
                gitClient.disconnect()
                return@withContext lastState.copy(lastSyncTime = timestamp)
            }

            val totalActions = pendingActions.size
            var completedActions = 0
            val newTrackedFiles = lastState.trackedFiles.toMutableMap()

            for (action in pendingActions) {
                val currentProgress = 0.30f + (0.65f * (completedActions.toFloat() / totalActions.toFloat()))
                val actionLabel = when (action.actionType) {
                    SyncActionType.UPLOAD -> "스테이징"
                    SyncActionType.DOWNLOAD -> "제거"
                    SyncActionType.DELETE_LOCAL -> "삭제"
                    SyncActionType.DELETE_REMOTE -> "원격 제거"
                    SyncActionType.CONFLICT -> "충돌 해결"
                    SyncActionType.NONE -> ""
                }

                onProgress("$actionLabel 진행 중: ${action.relativePath}", currentProgress)

                try {
                    when (action.actionType) {
                        SyncActionType.UPLOAD -> {
                            // 파일을 스테이징 영역에 추가 (commit에 포함될 것)
                            val file = java.io.File("${profile.localPath}/${action.relativePath}")
                            if (file.exists()) {
                                newTrackedFiles[action.relativePath] = file.lastModified()
                                onLog(SyncLog(
                                    timestamp = System.currentTimeMillis(),
                                    profileId = profile.id,
                                    profileName = profile.name,
                                    relativePath = action.relativePath,
                                    actionType = "TRACK",
                                    status = SyncLogStatus.SUCCESS,
                                    message = "파일 스테이징 완료 (크기: ${formatSize(action.size)})"
                                ))
                            }
                        }

                        SyncActionType.DELETE_LOCAL -> {
                            val localFile = "${profile.localPath}/${action.relativePath}"
                            val ok = localClient.deleteFile(localFile)
                            if (ok || !localClient.exists(localFile)) {
                                newTrackedFiles.remove(action.relativePath)
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

            onProgress("Git 커밋 중...", 0.95f)
            // 자동 커밋 메시지 생성
            val commitMessage = generateCommitMessage(profile, pendingActions, timestamp)
            val commitSuccess = gitClient.commit(commitMessage)

            if (commitSuccess) {
                onLog(SyncLog(
                    timestamp = System.currentTimeMillis(),
                    profileId = profile.id,
                    profileName = profile.name,
                    relativePath = "",
                    actionType = "COMMIT",
                    status = SyncLogStatus.SUCCESS,
                    message = "커밋 완료: $commitMessage"
                ))

                onProgress("Git 푸시 중...", 0.97f)
                val pushSuccess = gitClient.push()
                if (pushSuccess) {
                    onLog(SyncLog(
                        timestamp = System.currentTimeMillis(),
                        profileId = profile.id,
                        profileName = profile.name,
                        relativePath = "",
                        actionType = "PUSH",
                        status = SyncLogStatus.SUCCESS,
                        message = "푸시 완료"
                    ))
                } else {
                    onLog(SyncLog(
                        timestamp = System.currentTimeMillis(),
                        profileId = profile.id,
                        profileName = profile.name,
                        relativePath = "",
                        actionType = "PUSH",
                        status = SyncLogStatus.WARNING,
                        message = "푸시에 실패했습니다. 로컬 변경사항은 저장되었습니다."
                    ))
                }
            }

            onProgress("Git 동기화 완료", 1.0f)
            return@withContext GitSyncState(
                profileId = profile.id,
                currentBranch = gitClient.getCurrentBranch(),
                lastCommitHash = getLastCommitHash(),
                lastSyncTime = System.currentTimeMillis(),
                trackedFiles = newTrackedFiles
            )

        } finally {
            gitClient.disconnect()
        }
    }

    private fun generateCommitMessage(
        profile: SyncProfile,
        actions: List<SyncAction>,
        timestamp: Long
    ): String {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(timestamp))

        val uploadCount = actions.count { it.actionType == SyncActionType.UPLOAD }
        val deleteCount = actions.count { it.actionType == SyncActionType.DELETE_LOCAL }
        val changes = mutableListOf<String>()

        if (uploadCount > 0) changes.add("$uploadCount uploaded")
        if (deleteCount > 0) changes.add("$deleteCount deleted")

        val changeText = if (changes.isNotEmpty()) {
            " (${changes.joinToString(", ")})"
        } else {
            ""
        }

        return "[$date] Sync: ${profile.name}$changeText"
    }

    private fun getLastCommitHash(): String {
        return try {
            val history = gitClient.getHistory(1)
            if (history.isNotEmpty()) history[0].hash else ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1] + ""
        return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }
}

package com.sftpsync.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sftpsync.app.models.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import com.sftpsync.app.sync.BiSyncEngineRunner
import com.sftpsync.app.sync.GitSyncEngineRunner
import com.sftpsync.app.sftp.JGitClient
import com.sftpsync.app.utils.*
import kotlinx.coroutines.*

data class DirectoryApprovalRequest(
    val isLocal: Boolean,
    val path: String,
    val onResponse: (Boolean) -> Unit
)

data class UiState(
    val profiles: List<SyncProfile> = emptyList(),
    val selectedProfile: SyncProfile? = null,
    val logs: List<SyncLog> = emptyList(),
    val isSyncing: Boolean = false,
    val syncProgress: Float = 0f,
    val syncStatusText: String = "",
    val isConnecting: Boolean = false,
    val connectionResult: Boolean? = null, // null = not tested, true = success, false = fail
    val currentScreen: AppScreen = AppScreen.DASHBOARD,
    val editingProfile: SyncProfile? = null,
    val androidPermissionGranted: Boolean = true,
    val directoryApprovalRequest: DirectoryApprovalRequest? = null
)

enum class AppScreen {
    DASHBOARD,
    PROFILE_EDITOR,
    LOGS,
    SETTINGS
}

class SyncViewModel {
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Active file watchers for profiles with auto-sync enabled
    private val activeWatchers = HashMap<String, FileWatcherJob>()
    // Debounce jobs to delay sync on changes (3 seconds)
    private val debounceJobs = HashMap<String, Job>()
    // Active remote polling jobs for profiles with auto-sync enabled (30 seconds polling)
    private val remotePollingJobs = HashMap<String, Job>()
    // 동기화 완료 후 자동 재트리거 방지용 쿨다운 타임스탬프 (밀리초)
    @Volatile
    private var syncCooldownUntil: Long = 0L

    var state by mutableStateOf(UiState())
        private set

    init {
        loadAllData()
    }

    fun loadAllData() {
        coroutineScope.launch {
            val loadedProfiles = withContext(Dispatchers.Default) {
                ProfileManager.loadProfiles()
            }
            val loadedLogs = withContext(Dispatchers.Default) {
                ProfileManager.loadLogs()
            }
            
            val selected = if (loadedProfiles.isNotEmpty()) {
                state.selectedProfile ?: loadedProfiles.first()
            } else {
                null
            }

            state = state.copy(
                profiles = loadedProfiles,
                selectedProfile = selected,
                logs = loadedLogs
            )
            
            // Start watchers for all profiles
            loadedProfiles.forEach { updateAutoSyncWatcher(it) }
            
            // Start/stop background service on Android
            checkAndControlBackgroundService(loadedProfiles)
        }
    }

    fun selectProfile(profile: SyncProfile) {
        state = state.copy(selectedProfile = profile)
    }

    fun navigateTo(screen: AppScreen) {
        state = state.copy(
            currentScreen = screen,
            connectionResult = null,
            editingProfile = if (screen == AppScreen.PROFILE_EDITOR) {
                state.selectedProfile ?: createEmptyProfile()
            } else {
                null
            }
        )
    }

    fun startNewProfile() {
        state = state.copy(
            currentScreen = AppScreen.PROFILE_EDITOR,
            editingProfile = createEmptyProfile(),
            connectionResult = null
        )
    }

    private fun createEmptyProfile(): SyncProfile {
        val defaultLocal = if (getPlatformName() == "Android") {
            "/storage/emulated/0/Download/SftpSync"
        } else {
            System.getProperty("user.home") + "/SftpSync"
        }
        return SyncProfile(
            id = generateUuid(),
            name = "신규 동기화 프로필",
            host = "",
            port = 22,
            username = "",
            authType = AuthType.PASSWORD,
            password = "",
            localPath = defaultLocal,
            remotePath = "/home/username/sync"
        )
    }

    fun updateEditingProfile(updated: SyncProfile) {
        state = state.copy(editingProfile = updated)
    }

    fun saveProfile(profile: SyncProfile) {
        coroutineScope.launch {
            val updatedProfiles = state.profiles.toMutableList()
            val index = updatedProfiles.indexOfFirst { it.id == profile.id }
            if (index >= 0) {
                updatedProfiles[index] = profile
            } else {
                updatedProfiles.add(profile)
            }

            withContext(Dispatchers.Default) {
                ProfileManager.saveProfiles(updatedProfiles)
            }

            state = state.copy(
                profiles = updatedProfiles,
                selectedProfile = profile,
                currentScreen = AppScreen.DASHBOARD,
                editingProfile = null
            )
            
            // Update watcher for this profile
            updateAutoSyncWatcher(profile)
            
            // Update background service on Android
            checkAndControlBackgroundService(updatedProfiles)
        }
    }

    fun deleteProfile(profile: SyncProfile) {
        coroutineScope.launch {
            val updatedProfiles = state.profiles.filter { it.id != profile.id }
            withContext(Dispatchers.Default) {
                ProfileManager.saveProfiles(updatedProfiles)
            }

            val nextSelected = if (updatedProfiles.isNotEmpty()) updatedProfiles.first() else null
            state = state.copy(
                profiles = updatedProfiles,
                selectedProfile = nextSelected,
                currentScreen = AppScreen.DASHBOARD
            )
            
            // Stop watcher for this profile
            val stoppedProfile = profile.copy(autoSyncEnabled = false)
            updateAutoSyncWatcher(stoppedProfile)
            
            // Update background service on Android
            checkAndControlBackgroundService(updatedProfiles)
        }
    }

    fun testConnection(profile: SyncProfile) {
        if (state.isConnecting) return
        state = state.copy(isConnecting = true, connectionResult = null)

        coroutineScope.launch {
            val success = withContext(Dispatchers.Default) {
                if (profile.syncMode == SyncMode.GIT) {
                    val client = JGitClient(
                        repositoryPath = profile.localPath,
                        sshKeyPath = profile.gitSshKeyPath,
                        author = profile.gitCommitAuthor,
                        email = profile.gitCommitEmail,
                        remoteUrl = profile.gitRepositoryUrl,
                        branch = profile.gitBranch
                    )
                    try {
                        client.connect()
                    } catch (e: Exception) {
                        false
                    } finally {
                        client.disconnect()
                    }
                } else {
                    val client = createSftpClient(profile)
                    try {
                        client.connect()
                    } catch (e: Exception) {
                        false
                    } finally {
                        client.disconnect()
                    }
                }
            }

            state = state.copy(
                isConnecting = false,
                connectionResult = success
            )
        }
    }

    /**
     * 특정 프로필에 대해 수동 또는 자동 동기화를 시작합니다.
     * UI 스레드 상에서 구동되어 실시간 프로그레스 바 및 상태 메시지를 출력합니다.
     */
    fun startSync(profile: SyncProfile) {
        // UI가 이미 동기화 중이거나 백그라운드 서비스가 구동 중인 경우 사용자에게 피드백 제공
        synchronized(SyncLock) {
            if (state.isSyncing || SyncLock.isSyncing) {
                // 버그 #3 수정: 락 충돌 시 사용자에게 피드백 메시지 표시
                state = state.copy(
                    syncStatusText = "이미 동기화가 진행 중입니다. 완료 후 다시 시도해 주세요."
                )
                return
            }
            SyncLock.isSyncing = true // 전역 동시 구동 차단 락 활성화
        }
        
        state = state.copy(
            isSyncing = true,
            syncProgress = 0f,
            syncStatusText = "동기화 준비 중..."
        )

        // 버그 #5 수정: 동기화 시작 시 자동 동기화 감시자(데스크톱)를 일시 정지하여 재트리거 방지
        pauseAutoSyncWatchers()

        try {
            coroutineScope.launch {
                try {
                    val localClient = createLocalFileClient()

                    if (profile.syncMode == SyncMode.GIT) {
                        val gitClient = JGitClient(
                            repositoryPath = profile.localPath,
                            sshKeyPath = profile.gitSshKeyPath,
                            author = profile.gitCommitAuthor,
                            email = profile.gitCommitEmail,
                            remoteUrl = profile.gitRepositoryUrl,
                            branch = profile.gitBranch
                        )
                        val runner = GitSyncEngineRunner(gitClient, localClient)

                        val lastState = withContext(Dispatchers.Default) {
                            ProfileManager.loadGitState(profile.id)
                        }

                        val updatedState = runner.executeSync(
                            profile = profile,
                            lastState = lastState,
                            onProgress = { statusText, progress ->
                                state = state.copy(
                                    syncStatusText = statusText,
                                    syncProgress = progress
                                )
                            },
                            onLog = { log ->
                                ProfileManager.addLog(log)
                                val loadedLogs = ProfileManager.loadLogs()
                                state = state.copy(logs = loadedLogs)
                            },
                            checkDirectoryApproval = { isLocal, path ->
                                val deferred = CompletableDeferred<Boolean>()
                                state = state.copy(
                                    directoryApprovalRequest = DirectoryApprovalRequest(
                                        isLocal = isLocal,
                                        path = path,
                                        onResponse = { approved ->
                                            state = state.copy(directoryApprovalRequest = null)
                                            deferred.complete(approved)
                                        }
                                    )
                                )
                                deferred.await()
                            }
                        )

                        withContext(Dispatchers.Default) {
                            ProfileManager.saveGitState(updatedState)
                        }
                    } else {
                        val sftpClient = createSftpClient(profile)
                        val runner = BiSyncEngineRunner(sftpClient, localClient)

                        val lastState = withContext(Dispatchers.Default) {
                            ProfileManager.loadState(profile.id)
                        }

                        val updatedState = runner.executeSync(
                            profile = profile,
                            lastState = lastState,
                            onProgress = { statusText, progress ->
                                state = state.copy(
                                    syncStatusText = statusText,
                                    syncProgress = progress
                                )
                            },
                            onLog = { log ->
                                ProfileManager.addLog(log)
                                // Reload logs in UI
                                val loadedLogs = ProfileManager.loadLogs()
                                state = state.copy(logs = loadedLogs)
                            },
                            checkDirectoryApproval = { isLocal, path ->
                                val deferred = CompletableDeferred<Boolean>()
                                state = state.copy(
                                    directoryApprovalRequest = DirectoryApprovalRequest(
                                        isLocal = isLocal,
                                        path = path,
                                        onResponse = { approved ->
                                            state = state.copy(directoryApprovalRequest = null)
                                            deferred.complete(approved)
                                        }
                                    )
                                )
                                deferred.await()
                            }
                        )

                        withContext(Dispatchers.Default) {
                            ProfileManager.saveState(updatedState)
                        }
                    }

                    state = state.copy(
                        isSyncing = false,
                        syncProgress = 1.0f,
                        syncStatusText = "동기화 완료"
                    )
                    
                    // Auto refresh logs
                    loadAllData()
                } catch (e: Exception) {
                    e.printStackTrace()
                    state = state.copy(
                        isSyncing = false,
                        syncStatusText = "동기화 실패: ${e.message ?: "알 수 없는 오류"}"
                    )
                } finally {
                    // 버그 #1 수정: state.isSyncing도 반드시 해제 (버튼 영구 비활성화 방지)
                    state = state.copy(isSyncing = false)
                    synchronized(SyncLock) {
                        SyncLock.isSyncing = false
                    }
                    // 버그 #5 수정: 동기화 완료 후 5초 쿨다운 설정 후 감시자 재활성화
                    syncCooldownUntil = System.currentTimeMillis() + 5000L
                    resumeAutoSyncWatchers()
                }
            }
        } catch (e: Exception) {
            // 버그 #1 수정: launch 실패 시에도 state.isSyncing 해제
            state = state.copy(
                isSyncing = false,
                syncStatusText = "동기화 시작 실패: ${e.message ?: "알 수 없는 오류"}"
            )
            synchronized(SyncLock) {
                SyncLock.isSyncing = false
            }
            resumeAutoSyncWatchers()
            e.printStackTrace()
        }
    }

    fun clearHistory() {
        coroutineScope.launch {
            withContext(Dispatchers.Default) {
                ProfileManager.clearLogs()
            }
            state = state.copy(logs = emptyList())
        }
    }

    fun setAndroidPermissionGranted(granted: Boolean) {
        state = state.copy(androidPermissionGranted = granted)
    }

    private fun generateUuid(): String {
        return java.util.UUID.randomUUID().toString()
    }

    // 프로필 설정에 맞춰 파일 감시자(FileWatcher)와 원격 서버 감지용 폴링 스케줄러를 개설하거나 파기합니다.
    fun updateAutoSyncWatcher(profile: SyncProfile) {
        // 기존 감시 스케줄 파기
        activeWatchers[profile.id]?.cancel()
        activeWatchers.remove(profile.id)
        debounceJobs[profile.id]?.cancel()
        debounceJobs.remove(profile.id)
        
        // 기존 원격 검사 타이머 파기
        remotePollingJobs[profile.id]?.cancel()
        remotePollingJobs.remove(profile.id)

        // Android 환경에서는 포그라운드 서비스(SyncForegroundService)가 파일 감시와 
        // 폴링을 독립적으로 관리하므로, UI 뷰모델 레벨의 감시 등록을 건너뜁니다.
        if (getPlatformName() == "Android") {
            return
        }

        if (profile.autoSyncEnabled) {
            // 1. Local File Watcher Setup
            val watcher = startFileWatcher(profile.id, profile.localPath) {
                // File changed callback
                triggerAutoSync(profile.id)
            }
            if (watcher != null) {
                activeWatchers[profile.id] = watcher
            }

            // 2. Remote Server Polling Job Setup (30-second interval)
            remotePollingJobs[profile.id] = coroutineScope.launch {
                while (isActive) {
                    delay(30000) // Wait for 30 seconds
                    // 버그 #2/#5 수정: 쿨다운 및 SyncLock도 확인
                    if (System.currentTimeMillis() < syncCooldownUntil) continue
                    val curProfile = state.profiles.firstOrNull { it.id == profile.id }
                    if (curProfile != null && curProfile.autoSyncEnabled && !state.isSyncing && !SyncLock.isSyncing) {
                        startSync(curProfile)
                    }
                }
            }
        }
    }

    private fun triggerAutoSync(profileId: String) {
        debounceJobs[profileId]?.cancel()
        debounceJobs[profileId] = coroutineScope.launch {
            delay(3000) // 3-second debouncing
            // 버그 #5 수정: 쿨다운 기간 내에는 자동 동기화 트리거 무시
            if (System.currentTimeMillis() < syncCooldownUntil) return@launch
            val profile = state.profiles.firstOrNull { it.id == profileId }
            if (profile != null && profile.autoSyncEnabled && !state.isSyncing && !SyncLock.isSyncing) {
                startSync(profile)
            }
        }
    }

    /**
     * 동기화 실행 중 자동 동기화 감시자를 일시 정지합니다.
     * 동기화 엔진이 로컬에 파일을 쓸 때 파일 감시자가 변경을 감지하여
     * 즉시 재동기화를 트리거하는 무한 루프를 방지합니다.
     */
    private fun pauseAutoSyncWatchers() {
        // 데스크톱에서만 뷰모델이 감시자를 관리함 (Android는 서비스가 관리)
        if (getPlatformName() == "Android") return
        debounceJobs.values.forEach { it.cancel() }
        debounceJobs.clear()
        remotePollingJobs.values.forEach { it.cancel() }
        remotePollingJobs.clear()
    }

    /**
     * 동기화 완료 후 자동 동기화 감시자를 재활성화합니다.
     */
    private fun resumeAutoSyncWatchers() {
        if (getPlatformName() == "Android") return
        val profiles = state.profiles.filter { it.autoSyncEnabled }
        for (profile in profiles) {
            // 원격 폴링 재시작 (파일 감시자는 loadAllData()에서 재등록됨)
            remotePollingJobs[profile.id] = coroutineScope.launch {
                // 쿨다운 대기
                val remaining = syncCooldownUntil - System.currentTimeMillis()
                if (remaining > 0) delay(remaining)
                while (isActive) {
                    delay(30000)
                    if (System.currentTimeMillis() < syncCooldownUntil) continue
                    val curProfile = state.profiles.firstOrNull { it.id == profile.id }
                    if (curProfile != null && curProfile.autoSyncEnabled && !state.isSyncing && !SyncLock.isSyncing) {
                        startSync(curProfile)
                    }
                }
            }
        }
    }

    private fun checkAndControlBackgroundService(profiles: List<SyncProfile>) {
        if (getPlatformName() == "Android") {
            val anyAutoSync = profiles.any { it.autoSyncEnabled }
            if (anyAutoSync) {
                startPlatformBackgroundService()
            } else {
                stopPlatformBackgroundService()
            }
        }
    }

    fun exportSettingsToFile(onComplete: (String?) -> Unit) {
        coroutineScope.launch {
            try {
                val profiles = state.profiles
                val jsonStr = withContext(Dispatchers.Default) {
                    kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(profiles)
                }
                val path = exportSettings(jsonStr)
                onComplete(path)
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete(null)
            }
        }
    }

    fun importSettingsFromFile(onComplete: (Boolean, String) -> Unit) {
        coroutineScope.launch {
            try {
                val jsonStr = importSettings()
                if (jsonStr.isNullOrEmpty()) {
                    onComplete(false, "가져오기가 취소되었거나 파일이 비어 있습니다.")
                    return@launch
                }
                val importedProfiles: List<SyncProfile> = withContext(Dispatchers.Default) {
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(jsonStr)
                }
                if (importedProfiles.isEmpty()) {
                    onComplete(false, "유효한 프로필 설정을 찾을 수 없습니다.")
                    return@launch
                }

                val currentProfiles = state.profiles.toMutableList()
                var addedCount = 0
                var updatedCount = 0
                for (imported in importedProfiles) {
                    val index = currentProfiles.indexOfFirst { it.id == imported.id }
                    if (index >= 0) {
                        currentProfiles[index] = imported
                        updatedCount++
                    } else {
                        currentProfiles.add(imported)
                        addedCount++
                    }
                }

                withContext(Dispatchers.Default) {
                    ProfileManager.saveProfiles(currentProfiles)
                }

                loadAllData()
                onComplete(true, "백업 파일에서 프로필 ${addedCount}개 추가, ${updatedCount}개 업데이트 완료!")
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete(false, "설정 가져오기 실패: ${e.message ?: "알 수 없는 오류"}")
            }
        }
    }

    fun dispose() {
        activeWatchers.values.forEach { it.cancel() }
        activeWatchers.clear()
        debounceJobs.values.forEach { it.cancel() }
        debounceJobs.clear()
        remotePollingJobs.values.forEach { it.cancel() }
        remotePollingJobs.clear()
        coroutineScope.cancel()
    }
}

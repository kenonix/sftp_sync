package com.sftpsync.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sftpsync.app.models.*
import com.sftpsync.app.sync.BiSyncEngineRunner
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
    LOGS
}

class SyncViewModel {
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Active file watchers for profiles with auto-sync enabled
    private val activeWatchers = HashMap<String, FileWatcherJob>()
    // Debounce jobs to delay sync on changes (3 seconds)
    private val debounceJobs = HashMap<String, Job>()
    // Active remote polling jobs for profiles with auto-sync enabled (30 seconds polling)
    private val remotePollingJobs = HashMap<String, Job>()

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
        }
    }

    fun testConnection(profile: SyncProfile) {
        if (state.isConnecting) return
        state = state.copy(isConnecting = true, connectionResult = null)

        coroutineScope.launch {
            val success = withContext(Dispatchers.Default) {
                val client = createSftpClient(profile)
                val ok = client.connect()
                client.disconnect()
                ok
            }

            state = state.copy(
                isConnecting = false,
                connectionResult = success
            )
        }
    }

    fun startSync(profile: SyncProfile) {
        if (state.isSyncing) return
        state = state.copy(
            isSyncing = true,
            syncProgress = 0f,
            syncStatusText = "동기화 준비 중..."
        )

        coroutineScope.launch {
            val localClient = createLocalFileClient()
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

            state = state.copy(
                isSyncing = false,
                syncProgress = 1.0f,
                syncStatusText = "동기화 완료"
            )
            
            // Auto refresh logs
            loadAllData()
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

    // Starts or stops watcher for a specific profile based on its config
    fun updateAutoSyncWatcher(profile: SyncProfile) {
        // Cancel existing watcher if any
        activeWatchers[profile.id]?.cancel()
        activeWatchers.remove(profile.id)
        debounceJobs[profile.id]?.cancel()
        debounceJobs.remove(profile.id)
        
        // Cancel existing remote polling job if any
        remotePollingJobs[profile.id]?.cancel()
        remotePollingJobs.remove(profile.id)

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
                    val curProfile = state.profiles.firstOrNull { it.id == profile.id }
                    if (curProfile != null && curProfile.autoSyncEnabled && !state.isSyncing) {
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
            val profile = state.profiles.firstOrNull { it.id == profileId }
            if (profile != null && profile.autoSyncEnabled && !state.isSyncing) {
                startSync(profile)
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

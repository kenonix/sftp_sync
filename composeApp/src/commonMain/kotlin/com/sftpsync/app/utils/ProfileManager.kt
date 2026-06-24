package com.sftpsync.app.utils

import com.sftpsync.app.models.SyncLog
import com.sftpsync.app.models.SyncProfile
import com.sftpsync.app.models.SyncState
import com.sftpsync.app.models.GitSyncState
import com.sftpsync.app.models.SyncMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ProfileManager {

    private val json = Json { 
        prettyPrint = true 
        ignoreUnknownKeys = true
    }

    private const val PROFILES_FILE = "profiles.json"
    private const val LOGS_FILE = "logs.json"
    private const val STATE_FILE_PREFIX = "sync_state_"
    private const val GIT_STATE_FILE_PREFIX = "git_sync_state_"

    fun loadProfiles(): List<SyncProfile> {
        return try {
            val content = readTextFile(PROFILES_FILE)
            if (content.isNullOrEmpty()) emptyList() else json.decodeFromString(content)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun saveProfiles(profiles: List<SyncProfile>) {
        try {
            val content = json.encodeToString(profiles)
            writeTextFile(PROFILES_FILE, content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadState(profileId: String): SyncState {
        val fileName = "$STATE_FILE_PREFIX$profileId.json"
        return try {
            val content = readTextFile(fileName)
            if (content.isNullOrEmpty()) {
                SyncState(profileId = profileId, lastSyncTime = 0L)
            } else {
                json.decodeFromString(content)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            SyncState(profileId = profileId, lastSyncTime = 0L)
        }
    }

    fun saveState(state: SyncState) {
        val fileName = "$STATE_FILE_PREFIX${state.profileId}.json"
        try {
            val content = json.encodeToString(state)
            writeTextFile(fileName, content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadGitState(profileId: String): GitSyncState {
        val fileName = "$GIT_STATE_FILE_PREFIX$profileId.json"
        return try {
            val content = readTextFile(fileName)
            if (content.isNullOrEmpty()) {
                GitSyncState(profileId = profileId, currentBranch = "main", lastCommitHash = "", lastSyncTime = 0L)
            } else {
                json.decodeFromString(content)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            GitSyncState(profileId = profileId, currentBranch = "main", lastCommitHash = "", lastSyncTime = 0L)
        }
    }

    fun saveGitState(state: GitSyncState) {
        val fileName = "$GIT_STATE_FILE_PREFIX${state.profileId}.json"
        try {
            val content = json.encodeToString(state)
            writeTextFile(fileName, content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadLogs(): List<SyncLog> {
        return try {
            val content = readTextFile(LOGS_FILE)
            if (content.isNullOrEmpty()) emptyList() else json.decodeFromString(content)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun saveLogs(logs: List<SyncLog>) {
        try {
            val content = json.encodeToString(logs)
            writeTextFile(LOGS_FILE, content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addLog(log: SyncLog) {
        try {
            val currentLogs = loadLogs().toMutableList()
            currentLogs.add(0, log) // Add to top for timeline view
            
            // Limit log history to last 500 entries to prevent files from getting too large
            val limitedLogs = if (currentLogs.size > 500) {
                currentLogs.subList(0, 500)
            } else {
                currentLogs
            }
            
            saveLogs(limitedLogs)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun clearLogs() {
        saveLogs(emptyList())
    }

    fun hasLocalChanges(profile: SyncProfile): Boolean {
        return try {
            val localClient = createLocalFileClient()
            if (!localClient.exists(profile.localPath)) return false

            val localFiles = localClient.listFiles(profile.localPath).associateBy { it.relativePath }

            // Filter out exclusions
            val filteredLocalFiles = localFiles.filterKeys { path ->
                !com.sftpsync.app.sync.BiSyncEngine.isExcluded(path, profile.exclusions)
            }

            if (profile.syncMode == SyncMode.GIT) {
                val lastState = loadGitState(profile.id)
                val trackedFiles = lastState.trackedFiles

                val filteredLocalFilesNoDir = filteredLocalFiles.filterValues { !it.isDirectory }

                // 1. Check if size of keys is different
                if (filteredLocalFilesNoDir.size != trackedFiles.size) return true

                // 2. Check each file
                for ((path, localFile) in filteredLocalFilesNoDir) {
                    val lastModified = trackedFiles[path] ?: return true
                    if (localFile.lastModified != lastModified) return true
                }
            } else {
                val lastState = loadState(profile.id)
                val stateFiles = lastState.files

                val filteredStateFiles = stateFiles.filterValues { !it.isDirectory }
                val filteredLocalFilesNoDir = filteredLocalFiles.filterValues { !it.isDirectory }

                // 1. Check if size of keys is different
                if (filteredLocalFilesNoDir.size != filteredStateFiles.size) return true

                // 2. Check each file
                for ((path, localFile) in filteredLocalFilesNoDir) {
                    val meta = filteredStateFiles[path] ?: return true
                    if (localFile.size != meta.size || localFile.lastModified != meta.lastModifiedLocal) {
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            e.printStackTrace()
            true // Fallback to true on error to ensure sync runs
        }
    }
}

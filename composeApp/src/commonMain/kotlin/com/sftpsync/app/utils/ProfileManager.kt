package com.sftpsync.app.utils

import com.sftpsync.app.models.SyncLog
import com.sftpsync.app.models.SyncProfile
import com.sftpsync.app.models.SyncState
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
}

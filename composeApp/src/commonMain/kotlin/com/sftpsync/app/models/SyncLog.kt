package com.sftpsync.app.models

import kotlinx.serialization.Serializable

@Serializable
enum class SyncLogStatus {
    SUCCESS,
    ERROR,
    SKIPPED,
    WARNING
}

@Serializable
data class SyncLog(
    val timestamp: Long,
    val profileId: String,
    val profileName: String,
    val relativePath: String,
    val actionType: String, // String representation of SyncActionType
    val status: SyncLogStatus,
    val message: String
)

package com.sftpsync.app.models

import kotlinx.serialization.Serializable

data class SimpleSyncFile(
    override val relativePath: String,
    override val size: Long,
    override val lastModified: Long,
    override val isDirectory: Boolean
) : SyncFile

interface SyncFile {
    val relativePath: String
    val size: Long
    val lastModified: Long
    val isDirectory: Boolean
}

enum class SyncActionType {
    UPLOAD,          // Local -> Remote
    DOWNLOAD,        // Remote -> Local
    DELETE_LOCAL,    // Delete from local
    DELETE_REMOTE,   // Delete from remote
    CONFLICT,        // Conflict (both modified or mod/del)
    NONE             // In sync, do nothing
}

data class SyncAction(
    val relativePath: String,
    val actionType: SyncActionType,
    val isDirectory: Boolean,
    val size: Long = 0,
    val localLastModified: Long = 0,
    val remoteLastModified: Long = 0
)

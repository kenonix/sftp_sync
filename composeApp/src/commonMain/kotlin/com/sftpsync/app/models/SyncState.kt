package com.sftpsync.app.models

import kotlinx.serialization.Serializable

@Serializable
data class SyncFileMetadata(
    val relativePath: String,
    val size: Long,
    val lastModifiedLocal: Long,
    val lastModifiedRemote: Long,
    val isDirectory: Boolean
)

@Serializable
data class SyncState(
    val profileId: String,
    val lastSyncTime: Long,
    val files: Map<String, SyncFileMetadata> = emptyMap() // Key is relativePath
)

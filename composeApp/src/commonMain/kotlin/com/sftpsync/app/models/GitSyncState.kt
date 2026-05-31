package com.sftpsync.app.models

import kotlinx.serialization.Serializable

@Serializable
data class GitSyncState(
    val profileId: String,
    val currentBranch: String,
    val lastCommitHash: String,
    val lastSyncTime: Long,
    val trackedFiles: Map<String, Long> = emptyMap()  // path -> lastModified
)

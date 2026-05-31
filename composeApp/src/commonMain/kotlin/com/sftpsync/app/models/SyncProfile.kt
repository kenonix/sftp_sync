package com.sftpsync.app.models

import kotlinx.serialization.Serializable

@Serializable
enum class AuthType {
    PASSWORD,
    PRIVATE_KEY
}

@Serializable
enum class ConflictStrategy {
    NEWER_WINS,
    LOCAL_WINS,
    REMOTE_WINS,
    KEEP_BOTH
}

@Serializable
enum class SyncCondition {
    TIME_DIFFERENT,
    TIME_AND_SIZE_DIFFERENT,
    SIZE_DIFFERENT
}

@Serializable
data class SyncProfile(
    val id: String,
    val name: String,
    val syncMode: SyncMode = SyncMode.SFTP,

    // SFTP 관련 필드
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val password: String = "",
    val privateKeyContent: String? = null,
    val passphrase: String? = null,

    // Git 관련 필드
    val gitRepositoryUrl: String = "",
    val gitBranch: String = "main",
    val gitSshKeyPath: String? = null,
    val gitCommitAuthor: String = "SyncBot",
    val gitCommitEmail: String = "sync@sftpsync.local",

    // 공통 필드
    val localPath: String,
    val remotePath: String,
    val syncIntervalMinutes: Int = 0,
    val conflictStrategy: ConflictStrategy = ConflictStrategy.NEWER_WINS,
    val syncCondition: SyncCondition = SyncCondition.TIME_DIFFERENT,
    val autoSyncEnabled: Boolean = false,
    val exclusions: List<String> = listOf(".git", ".DS_Store", "Thumbs.db", ".sftp-sync-state.json")
)


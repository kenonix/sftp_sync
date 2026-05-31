package com.sftpsync.app.sftp

import kotlinx.serialization.Serializable

interface GitClient {
    fun connect(): Boolean
    fun disconnect()
    fun commit(message: String): Boolean
    fun push(remoteName: String = "origin"): Boolean
    fun pull(remoteName: String = "origin"): Boolean
    fun getStatus(): GitStatus
    fun getHistory(limit: Int = 10): List<GitCommit>
    fun getCurrentBranch(): String
    fun getBranches(): List<String>
    fun checkout(branchName: String): Boolean
    fun createBranch(branchName: String): Boolean
    fun getRemoteStatus(): GitRemoteStatus
    fun initRepository(path: String): Boolean
    fun getFileLastModified(path: String): Long
}

@Serializable
data class GitStatus(
    val added: List<String>,
    val modified: List<String>,
    val deleted: List<String>,
    val untracked: List<String>
)

@Serializable
data class GitCommit(
    val hash: String,
    val message: String,
    val author: String,
    val timestamp: Long
)

@Serializable
enum class GitRemoteStatus {
    SYNCED,         // 로컬과 원격 동기화됨
    AHEAD,          // 로컬에 커밋이 더 있음
    BEHIND,         // 원격에 커밋이 더 있음
    DIVERGED,       // 양쪽 다 변경사항 있음
    UNKNOWN         // 불명확 (오프라인 등)
}

package com.sftpsync.app.sftp

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory
import org.eclipse.jgit.util.FS
import com.jcraft.jsch.JSch
import java.io.File
import java.util.Date

class JGitClient(
    private val repositoryPath: String,
    private val sshKeyPath: String? = null,
    private val author: String = "SyncBot",
    private val email: String = "sync@example.com",
    private val remoteUrl: String? = null,
    private val branch: String = "main"
) : GitClient {

    private var git: Git? = null
    private var repository: Repository? = null

    override fun connect(): Boolean {
        return try {
            val repoDir = File(repositoryPath)

            // SSH 키 설정
            if (sshKeyPath != null && sshKeyPath.isNotEmpty()) {
                configureSshKey(sshKeyPath)
            }

            if (!File(repoDir, ".git").exists()) {
                if (remoteUrl != null && remoteUrl.isNotEmpty()) {
                    // Clone remote repo
                    val cloneCommand = Git.cloneRepository()
                        .setURI(remoteUrl)
                        .setDirectory(repoDir)
                        .setBranch(branch)
                    val clonedGit = cloneCommand.call()
                    git = clonedGit
                    repository = clonedGit.repository
                    return true
                } else {
                    // Init local repo
                    Git.init().setDirectory(repoDir).call().close()
                }
            }

            repository = FileRepositoryBuilder()
                .setGitDir(File(repoDir, ".git"))
                .build()

            git = Git(repository)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun disconnect() {
        try {
            git?.close()
            repository?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun commit(message: String): Boolean {
        return try {
            val git = git ?: return false

            // 모든 파일 스테이징
            git.add().addFilepattern(".").call()

            // 커밋
            git.commit()
                .setAuthor(author, email)
                .setMessage(message)
                .call()

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun push(remoteName: String): Boolean {
        return try {
            val git = git ?: return false
            git.push().setRemote(remoteName).call()
            true
        } catch (e: GitAPIException) {
            e.printStackTrace()
            false
        }
    }

    override fun pull(remoteName: String): Boolean {
        return try {
            val git = git ?: return false
            git.pull().setRemote(remoteName).call()
            true
        } catch (e: GitAPIException) {
            e.printStackTrace()
            false
        }
    }

    override fun getStatus(): GitStatus {
        return try {
            val git = git ?: return GitStatus(emptyList(), emptyList(), emptyList(), emptyList())

            val status = git.status().call()

            GitStatus(
                added = status.added.toList(),
                modified = status.modified.toList(),
                deleted = status.removed.toList(),
                untracked = status.untracked.toList()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            GitStatus(emptyList(), emptyList(), emptyList(), emptyList())
        }
    }

    override fun getHistory(limit: Int): List<GitCommit> {
        return try {
            val git = git ?: return emptyList()
            val repository = repository ?: return emptyList()

            val commits = mutableListOf<GitCommit>()
            val logs = git.log().setMaxCount(limit).call()

            for (commit in logs) {
                commits.add(
                    GitCommit(
                        hash = commit.id.abbreviate(7).name(),
                        message = commit.shortMessage,
                        author = commit.authorIdent.name,
                        timestamp = commit.commitTime.toLong() * 1000
                    )
                )
            }

            commits
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override fun getCurrentBranch(): String {
        return try {
            val repository = repository ?: return "main"
            val fullBranch = repository.branch
            if (fullBranch.startsWith("refs/heads/")) {
                fullBranch.substring("refs/heads/".length)
            } else {
                fullBranch
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "main"
        }
    }

    override fun getBranches(): List<String> {
        return try {
            val git = git ?: return listOf("main")
            val branches = mutableListOf<String>()

            // 로컬 브랜치 조회
            val allRefs = git.branchList().call()
            for (ref in allRefs) {
                val branchName = ref.name.replace("refs/heads/", "")
                branches.add(branchName)
            }

            branches
        } catch (e: Exception) {
            e.printStackTrace()
            listOf("main")
        }
    }

    override fun checkout(branchName: String): Boolean {
        return try {
            val git = git ?: return false
            git.checkout().setName(branchName).call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun createBranch(branchName: String): Boolean {
        return try {
            val git = git ?: return false
            git.branchCreate().setName(branchName).call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun getRemoteStatus(): GitRemoteStatus {
        return try {
            val git = git ?: return GitRemoteStatus.UNKNOWN
            val repository = repository ?: return GitRemoteStatus.UNKNOWN

            // 원격 추적 업데이트
            try {
                git.fetch().setRemote("origin").call()
            } catch (e: Exception) {
                // 원격이 없을 수 있음
            }

            // 현재 브랜치 조회
            val currentBranch = repository.branch
            val localRef = repository.findRef(currentBranch) ?: return GitRemoteStatus.UNKNOWN
            val remoteRef = repository.findRef("refs/remotes/origin/${currentBranch.replace("refs/heads/", "")}")
                ?: return GitRemoteStatus.UNKNOWN

            when {
                localRef.objectId == remoteRef.objectId -> GitRemoteStatus.SYNCED
                else -> {
                    // ahead/behind 정확히 계산은 복잡하므로 DIVERGED로 처리
                    GitRemoteStatus.DIVERGED
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            GitRemoteStatus.UNKNOWN
        }
    }

    override fun initRepository(path: String): Boolean {
        return try {
            val repoDir = File(path)
            repoDir.mkdirs()

            Git.init().setDirectory(repoDir).call().close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun getFileLastModified(path: String): Long {
        return try {
            val file = File(path)
            if (file.exists()) {
                file.lastModified()
            } else {
                System.currentTimeMillis()
            }
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun configureSshKey(sshKeyPath: String) {
        try {
            val factory = object : JschConfigSessionFactory() {
                override fun configure(hc: org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig.Host, session: com.jcraft.jsch.Session) {
                    session.setConfig("StrictHostKeyChecking", "no")
                }

                override fun createDefaultJSch(fs: FS): JSch {
                    val jsch = super.createDefaultJSch(fs)
                    jsch.addIdentity(sshKeyPath)
                    return jsch
                }
            }
            SshSessionFactory.setInstance(factory)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

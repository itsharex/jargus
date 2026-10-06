package com.qqmu.jargus.service;

import com.qqmu.jargus.entity.CiTriggerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

/**
 * CI 异步扫描执行器
 *
 * 独立成 Bean 是为了让 @Async 通过 Spring 代理生效（同类内部自调用不会走代理），
 * 并统一受 scanTaskExecutor 有界线程池约束，多项目并发扫描时排队执行、互不影响。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CiScanExecutor {

    private final CiTriggerService ciTriggerService;
    private final ScanTaskService scanTaskService;
    private final CiCallbackService ciCallbackService;

    /**
     * 克隆仓库并触发扫描（在 scanTaskExecutor 线程池中执行）
     */
    @Async("scanTaskExecutor")
    public void runGitScan(Long recordId, CiTriggerConfig config, String repoUrl,
                           String branch, String commitId) {
        Path sourceDir = null;
        try {
            ciTriggerService.updateRecordStatus(recordId, "RUNNING", null);

            // 克隆代码
            sourceDir = cloneRepo(config, repoUrl, branch, commitId, recordId);
            if (sourceDir == null) {
                ciTriggerService.updateRecordStatus(recordId, "FAILED", null);
                // 尚未生成扫描任务，按记录级失败回调（commit status = error）
                ciCallbackService.onRecordFailed(recordId);
                return;
            }

            // 克隆目录本身就是现成源码树，直接拷进任务快照，跳过 zip 往返（省数百 MB 堆峰值）
            var task = scanTaskService.createFromLocalDirectory(
                    sourceDir,
                    "CI-" + recordId,
                    null,
                    Boolean.TRUE.equals(config.getIncludeTestCode()),
                    Boolean.TRUE.equals(config.getEnableAiReview()),
                    Boolean.TRUE.equals(config.getSkipUnitTest()),
                    Boolean.TRUE.equals(config.getNotifyEnabled()),
                    config.getNotifyRecipientIds()
            );

            ciTriggerService.attachTask(recordId, task.getId());
            ciTriggerService.updateRecordStatus(recordId, null, "/scan/result/" + task.getId());
            scanTaskService.executeScanAsync(task.getId());

        } catch (Exception e) {
            log.error("CI 扫描触发失败: recordId={}", recordId, e);
            ciTriggerService.updateRecordStatus(recordId, "FAILED", null);
            ciCallbackService.onRecordFailed(recordId);
        } finally {
            // 扫描基于 createFromZip 落盘的快照进行，克隆目录用完即删，避免磁盘泄漏
            if (sourceDir != null) {
                deleteRecursively(sourceDir);
            }
        }
    }

    /**
     * 递归删除目录（CI 克隆临时目录清理）
     */
    private void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception e) {
                    log.warn("删除 CI 临时文件失败: {}", p);
                }
            });
        } catch (Exception e) {
            log.warn("清理 CI 克隆目录失败: {}", dir, e);
        }
    }

    /**
     * 克隆 Git 仓库到按记录隔离的临时目录 ./work/ci/{recordId}
     */
    private Path cloneRepo(CiTriggerConfig config, String repoUrl, String branch,
                           String commitId, Long recordId) {
        try {
            // SSRF 防护：只允许常规 git 协议。GENERIC webhook 的 repoUrl 由 payload 提供，
            // 持 token 者也不应能让服务器克隆本地仓库（file:// 或裸路径，JGit 均支持）。
            // 内网 http(s) 自建 GitLab/Gitee 是合法场景，不按 IP 段拦截。
            if (repoUrl == null || !repoUrl.matches("(?i)^(https?|git|ssh)://\\S+|^git@\\S+")) {
                throw new IllegalArgumentException("非法的仓库地址（仅支持 http(s)/git/ssh）: " + repoUrl);
            }
            Path workDir = Paths.get("./work/ci", String.valueOf(recordId));
            Files.createDirectories(workDir);

            var cloneCmd = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(workDir.toFile())
                    .setBranch(branch != null ? branch : "main")
                    .setDepth(1);

            // 私有库凭据（AES 加密存储，使用时解密）
            String token = CiTriggerService.decryptStored(config.getRepoToken());
            if (token != null && !token.isEmpty()) {
                String username = config.getRepoUsername() != null && !config.getRepoUsername().isBlank()
                        ? config.getRepoUsername().trim() : "oauth2";
                cloneCmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, token));
            }

            try (Git git = cloneCmd.call()) {
                // 如果指定了 commit，checkout 到该 commit
                if (commitId != null && !commitId.isEmpty()) {
                    git.checkout().setName(commitId).call();
                }
            }

            return workDir;
        } catch (GitAPIException | IOException e) {
            log.error("克隆仓库失败: url={}, err={}", repoUrl, e.getMessage());
            return null;
        }
    }
}

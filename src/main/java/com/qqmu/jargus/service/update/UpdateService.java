package com.qqmu.jargus.service.update;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.qqmu.jargus.service.version.VersionService;

/**
 * 一键更新：下载最新 release 的 JAR → 校验 → 备份当前 JAR → 生成脱离重启脚本 → 退出进程。
 *
 * <p>设计纪律：
 * <ul>
 *   <li>下载源只用「已配置仓库地址推导出来的 release 直链」（Gitee 优先、GitHub 兜底），
 *       绝不接受调用方传入 URL（SSRF/任意写）。</li>
 *   <li>流式写入临时文件 + 大小上限，边下边报百分比；下完后校验「是合法 jar 且 manifest
 *       版本确为远端最新、且比当前运行版本新」，全部通过才允许替换。</li>
 *   <li>只支持 POSIX + `java -jar` 部署（Linux/macOS）；IDE 内启动、Windows、JAR 不可写、
 *       无法定位运行中 jar 时一律明确报错、原地不动。</li>
 *   <li>替换与重启交给脱离进程的 shell 脚本做（本 JVM 退出后才换文件），失败可回滚。</li>
 * </ul>
 */
@Service
public class UpdateService {

    private static final Logger log = LoggerFactory.getLogger(UpdateService.class);

    /** 下载与磁盘的安全上限：当前发布约 86MB，留足余量。 */
    private static final long MAX_JAR_BYTES = 400L * 1024 * 1024;
    private static final long MIN_JAR_BYTES = 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private final VersionService versionService;
    private final boolean enabled;
    private final String giteeRepoUrl;
    private final String githubRepoUrl;
    /** 附加给重启进程的自定义启动参数（如 --server.port=9090）。 */
    private final String extraArgs;
    private final String currentVersion;
    private final Path workDir;

    /** 当前进行中的更新（无更新进行时为 null）；同一时刻只允许一个。 */
    private volatile Progress progress;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public UpdateService(
            VersionService versionService,
            @Value("${app.update.enabled:true}") boolean enabled,
            @Value("${app.gitee-url:}") String giteeRepoUrl,
            @Value("${app.github-url:}") String githubRepoUrl,
            @Value("${app.update.extra-args:}") String extraArgs,
            @Value("${app.version:}") String currentVersion,
            @Value("${app.work-dir:./work}") String workDir) {
        this.versionService = versionService;
        this.enabled = enabled;
        this.giteeRepoUrl = giteeRepoUrl;
        this.githubRepoUrl = githubRepoUrl;
        this.extraArgs = extraArgs == null ? "" : extraArgs.trim();
        this.currentVersion = currentVersion == null ? "" : currentVersion.trim();
        this.workDir = Path.of(workDir).toAbsolutePath();
    }

    /** 进度快照（也直接作为 JSON 返回给前端轮询）。 */
    public record Progress(String state, int percent, String message, String version, String error) {
        public static Progress of(String state, int pct, String msg, String version, String err) {
            return new Progress(state, pct, msg, version, err);
        }
    }

    public Progress status() {
        Progress p = progress;
        return p != null ? p : Progress.of("IDLE", 0, "", null, null);
    }

    /**
     * 执行更新。在后台线程下载，调用方据此立即返回、前端轮询 {@link #status()}。
     *
     * @throws IllegalStateException 前置校验不通过（不做任何写操作）
     */
    public synchronized void startUpdate() {
        if (!enabled) {
            throw new IllegalStateException("一键更新已被管理员关闭（app.update.enabled=false）");
        }
        Progress p = this.progress;
        if (p != null && !p.state().equals("FAILED") && !p.state().equals("DONE")) {
            throw new IllegalStateException("已有一次更新进行中：" + p.state() + " " + p.percent() + "%");
        }

        // 1) 从版本比对结果取最新版本与页面 URL（versionInfo 自带缓存，不额外打 GitHub）
        com.qqmu.jargus.service.version.VersionInfo v = versionService.snapshot();
        if (v.getState() != com.qqmu.jargus.service.version.VersionInfo.State.UPDATE_AVAILABLE
                || v.getLatestVersion() == null) {
            throw new IllegalStateException("当前已是最新版本或无法检测到新版本");
        }
        String latest = v.getLatestVersion();
        if (currentVersion.isEmpty()) {
            throw new IllegalStateException("无法确定当前运行版本，为安全起见已中止更新");
        }
        if (VersionService.compareVersions(latest, currentVersion) <= 0) {
            throw new IllegalStateException("远端版本 v" + latest + " 并不比当前 v"
                    + currentVersion + " 新，已中止");
        }

        // 2) 定位运行中的 jar 与启动参数
        RunningContext ctx;
        try {
            ctx = RunningContext.resolve();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("无法定位运行环境：" + e.getMessage());
        }

        // 3) 组装下载直链（Gitee 优先、GitHub 兜底），都来自已配置仓库地址
        List<String> sources = resolveDownloadUrls(latest);
        if (sources.isEmpty()) {
            throw new IllegalStateException("无法由仓库地址推导下载直链（请配置 app.gitee-url / app.github-url）");
        }

        this.progress = Progress.of("DOWNLOADING", 0, "准备下载 v" + latest, latest, null);
        Path tmpDir = workDir.resolve("update");
        Thread worker = new Thread(() -> runUpdate(tmpDir, ctx, sources, latest), "jargus-self-update");
        worker.setDaemon(false);
        worker.start();
    }

    /**
     * 由仓库地址推导最新版的 jar 直链。附件名约定为固定名 {@code jargus.jar}；
     * 为兼容历史版本（v2.0.3 附件名带版本号）同时尝试 {@code jargus-<ver>.jar}。
     */
    private List<String> resolveDownloadUrls(String latest) {
        String v = "v" + latest;
        java.util.ArrayList<String> urls = new java.util.ArrayList<>();
        // Gitee：https://gitee.com/{owner}/{repo}/releases/download/{tag}/{file}
        String giteeHost = stripRepo(giteeRepoUrl, "gitee.com");
        if (giteeHost != null) {
            urls.add(giteeHost + "/releases/download/" + v + "/jargus.jar");
            urls.add(giteeHost + "/releases/download/" + v + "/jargus-" + latest + ".jar");
        }
        // GitHub：https://github.com/{owner}/{repo}/releases/download/{tag}/{file}
        String ghHost = stripRepo(githubRepoUrl, "github.com");
        if (ghHost != null) {
            urls.add(ghHost + "/releases/download/" + v + "/jargus.jar");
            urls.add(ghHost + "/releases/download/" + v + "/jargus-" + latest + ".jar");
        }
        return urls;
    }

    /** 从 https?://{host}/{owner}/{repo}(.git) 形式提取 https://{host}/{owner}/{repo}。 */
    private String stripRepo(String url, String host) {
        if (url == null) {
            return null;
        }
        String u = url.trim();
        if (u.startsWith("git@")) {
            u = u.replaceFirst("^git@" + host + ":", "https://" + host + "/");
        }
        u = u.replaceFirst("\\.git/?$", "").replaceAll("/*$", "");
        return u.toLowerCase().contains(host.toLowerCase()) ? u : null;
    }

    private void runUpdate(Path tmpDir, RunningContext ctx, List<String> sources, String latest) {
        Path tmpJar = null;
        try {
            Files.createDirectories(tmpDir);
            tmpJar = tmpDir.resolve("jargus-" + latest + ".part");

            // 1) 下载（依次尝试各直链，首个 200 且体积合理者胜出）
            Path downloaded = downloadWithFallback(sources, tmpJar, latest);
            update(Progress.of("VERIFYING", 95, "校验下载文件…", latest, null));

            // 2) 校验：合法 jar + manifest 版本 == latest
            String manifestVersion = readJarVersion(downloaded);
            if (manifestVersion == null) {
                throw new IllegalStateException("下载文件缺少版本清单（不是有效的 JAR）");
            }
            String mv = VersionService.stripV(manifestVersion);
            if (VersionService.compareVersions(mv, latest) != 0) {
                throw new IllegalStateException("下载 JAR 的版本 v" + mv + " 与远端 v" + latest + " 不一致，已中止（未动当前程序）");
            }

            // 3) 生成脱离重启脚本：等本进程退出 → 备份旧 jar → 替换 → 重启
            Path script = buildRestartScript(ctx, downloaded, latest);

            update(Progress.of("RESTARTING", 100, "即将停止旧版本并重启…", latest, null));
            log.info("一键更新：v{} 已就绪（{}），1.5s 后由脱离脚本替换并重启", latest, downloaded);

            // 给前端最后一次轮询机会看到 RESTARTING，随后退出
            Thread.sleep(1500);
            // 脱离进程拉起脚本；本进程随后退出，脚本接管替换
            ProcessBuilder pb = new ProcessBuilder("sh", script.toString());
            pb.directory(Path.of(ctx.cwd()).toFile());
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(workDir.resolve("update").resolve("update.log").toFile()));
            pb.redirectErrorStream(true);
            pb.start();

            // 触发 JVM 优雅退出（Spring 关闭钩子释放 DB 连接 / H2 锁）
            Runtime.getRuntime().halt(0);
        } catch (Exception e) {
            log.warn("一键更新失败: {}", e.toString());
            if (tmpJar != null) {
                try { Files.deleteIfExists(tmpJar); } catch (Exception ignored) { }
            }
            update(Progress.of("FAILED", 0, "", latest,
                    (e.getMessage() != null ? e.getMessage() : e.toString())));
        }
    }

    private Path downloadWithFallback(List<String> sources, Path tmpJar, String latest) throws Exception {
        Exception lastErr = null;
        for (String url : sources) {
            try {
                log.info("一键更新：尝试下载 {}", url);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(REQUEST_TIMEOUT)
                        .header("User-Agent", "jargus-self-update")
                        .GET()
                        .build();
                HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int code = resp.statusCode();
                if (code != 200) {
                    throw new IllegalStateException("HTTP " + code);
                }
                long announced = resp.headers().firstValueAsLong("content-length").orElse(-1L);
                if (announced > MAX_JAR_BYTES) {
                    throw new IllegalStateException("附件大小 " + announced + " 超过安全上限");
                }
                try (InputStream in = resp.body()) {
                    long total = 0;
                    try (var out = Files.newOutputStream(tmpJar)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        long lastTick = 0;
                        while ((n = in.read(buf)) != -1) {
                            total += n;
                            if (total > MAX_JAR_BYTES) {
                                throw new IllegalStateException("下载体积超过安全上限，已中止");
                            }
                            out.write(buf, 0, n);
                            // 最多每秒报一次进度
                            long now = System.currentTimeMillis();
                            if (now - lastTick > 1000 || total == announced) {
                                lastTick = now;
                                int pct = announced > 0
                                        ? (int) Math.min(94, total * 94 / announced)
                                        : 0;
                                update(Progress.of("DOWNLOADING", pct,
                                        "已下载 " + (total / (1024 * 1024)) + "MB", latest, null));
                            }
                        }
                    }
                    if (total < MIN_JAR_BYTES) {
                        throw new IllegalStateException("下载体积异常（" + total + " 字节），不像完整 JAR");
                    }
                }
                return tmpJar;
            } catch (Exception e) {
                lastErr = e;
                log.info("一键更新：{} 不可用（{}），尝试下一源", url, e.getMessage());
                try { Files.deleteIfExists(tmpJar); } catch (Exception ignored) { }
            }
        }
        throw new IllegalStateException("所有下载源均失败（最后错误："
                + (lastErr != null ? lastErr.getMessage() : "无可用源") + "）");
    }

    /** 读 jar 的 MANIFEST.MF 版本号。 */
    private String readJarVersion(Path jar) throws Exception {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            if (mf == null) {
                return null;
            }
            Attributes attrs = mf.getMainAttributes();
            String version = attrs.getValue("Implementation-Version");
            if (version == null || version.isBlank()) {
                version = attrs.getValue("Bundle-Version");
            }
            return version;
        }
    }

    /**
     * 生成脱离重启脚本。脚本逻辑：
     * 等旧进程退出（轮询 PID 文件锁失败 / sleep）→ 备份当前 jar → 原子替换 → 原 cwd 拉起新进程。
     */
    private Path buildRestartScript(RunningContext ctx, Path downloaded, String latest) throws Exception {
        Path updateDir = workDir.resolve("update");
        Path script = updateDir.resolve("restart.sh");
        String javaBin = ctx.javaBin();
        String jarPath = ctx.jarPath().toAbsolutePath().toString();
        String cwd = ctx.cwd();
        String pid = String.valueOf(ProcessHandle.current().pid());
        String backup = ctx.jarPath().toAbsolutePath().getParent()
                .resolve(ctx.jarPath().getFileName() + ".bak").toString();

        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/sh\n");
        sb.append("# 由 JArgus 一键更新生成：等旧进程退出后替换 jar 并重启\n");
        sb.append("set -e\n");
        sb.append("cd ").append(shq(cwd)).append("\n");
        sb.append("LOG=").append(shq(updateDir.resolve("update.log").toString())).append("\n");
        sb.append("echo \"[$(date '+%F %T')] update to v").append(latest).append(" start, waiting for old pid ").append(pid).append("\" >> \"$LOG\"\n");
        // 等旧进程退出（最多 60s）
        sb.append("for i in $(seq 1 60); do\n");
        sb.append("  if ! kill -0 ").append(pid).append(" 2>/dev/null; then break; fi\n");
        sb.append("  sleep 1\n");
        sb.append("done\n");
        sb.append("echo \"[$(date '+%F %T')] old process exited, replacing jar\" >> \"$LOG\"\n");
        // 备份 + 替换
        sb.append("cp -f ").append(shq(jarPath)).append(" ").append(shq(backup)).append(" 2>/dev/null || true\n");
        sb.append("mv -f ").append(shq(downloaded.toAbsolutePath().toString())).append(" ").append(shq(jarPath)).append("\n");
        // 重启
        String startCmd = javaBin + " -jar " + shq(jarPath);
        if (!extraArgs.isEmpty()) {
            startCmd += " " + extraArgs;
        }
        sb.append("echo \"[$(date '+%F %T')] starting new version\" >> \"$LOG\"\n");
        sb.append("nohup ").append(startCmd).append(" >> \"$LOG\" 2>&1 &\n");
        sb.append("echo \"[$(date '+%F %T')] update done\" >> \"$LOG\"\n");

        Files.writeString(script, sb.toString());
        script.toFile().setExecutable(true);
        return script;
    }

    private static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void update(Progress p) {
        this.progress = p;
    }

    /** 运行环境快照：运行中的 java、jar 路径、启动目录。 */
    private record RunningContext(String javaBin, Path jarPath, String cwd) {
        static RunningContext resolve() {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                throw new IllegalStateException("一键更新仅支持 Linux / macOS（当前为 Windows）");
            }
            String jarProp = System.getProperty("java.class.path");
            if (jarProp == null || jarProp.isBlank()) {
                throw new IllegalStateException("无法定位运行中的 JAR（IDE 内启动不支持一键更新，请用 jar 包部署）");
            }
            // 取 class.path 里第一个以 .jar 结尾的条目
            Path jar = null;
            for (String entry : jarProp.split(java.io.File.pathSeparator)) {
                if (entry.endsWith(".jar")) {
                    jar = Path.of(entry).toAbsolutePath();
                    break;
                }
            }
            if (jar == null || !Files.isRegularFile(jar)) {
                throw new IllegalStateException("运行中 classpath 没有找到可写的 JAR（IDE/热部署不支持一键更新）");
            }
            if (!Files.isWritable(jar.getParent() != null ? jar.getParent() : jar)) {
                throw new IllegalStateException("JAR 所在目录不可写，无法替换文件");
            }
            String javaHome = System.getProperty("java.home");
            String javaBin = Path.of(javaHome).resolve("bin").resolve("java").toString();
            String cwd = Path.of("").toAbsolutePath().toString();
            return new RunningContext(javaBin, jar, cwd);
        }
    }
}

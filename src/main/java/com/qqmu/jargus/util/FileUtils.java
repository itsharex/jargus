package com.qqmu.jargus.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 文件/路径通用工具：收敛原先散落在 CiScanExecutor / ScanTaskService / CallGraphService
 * / AiReviewService 中各自复制的目录递归删除与文件名提取实现。
 */
@Slf4j
public final class FileUtils {

    private FileUtils() {
    }

    /**
     * 递归删除目录及其全部内容（先删子后删父）。
     * null/不存在直接返回；单个文件删不掉不阻断其余删除，整体失败仅告警不抛异常。
     */
    public static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 单个文件删不掉不影响整体
                }
            });
        } catch (IOException e) {
            log.warn("递归删除目录失败: {}", dir, e);
        }
    }

    /**
     * 取路径中的文件名（最后一个 '/' 之后的部分）。
     * null 返回空串；不含分隔符时原样返回。调用方传入的路径均已标准化为 '/' 分隔。
     */
    public static String extractFileName(String path) {
        if (path == null) {
            return "";
        }
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }
}

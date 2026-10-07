package com.qqmu.jargus.service;

import com.qqmu.jargus.checker.CheckContext;
import com.qqmu.jargus.util.FileUtils;
import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.checker.CheckerType;
import com.qqmu.jargus.checker.IssueLevel;
import com.qqmu.jargus.llm.AiChatClient;
import com.qqmu.jargus.llm.AiChatRequest;
import com.qqmu.jargus.llm.AiChatResponse;
import com.qqmu.jargus.llm.AiClientFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 评审服务
 *
 * 负责调用 AI 大模型进行代码评审。
 * 扫描期主路径是 {@link #combinedReview}：每文件单次调用覆盖全部启用维度
 * （此前三检查器各调一次，耗时 ×3）；单维度入口保留给检查器独立调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiReviewService {

    /** 合并评审单次输出封顶（token）：与厂商「最大 Token 限制」取小，压住慢模型的单次耗时 */
    private static final int COMBINED_MAX_TOKENS = 4096;

    private final AiClientFactory aiClientFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 语义评审（通用代码质量分析）
     */
    public List<CheckIssue> semanticReview(CheckContext context) {
        return review(context, "semantic");
    }

    /**
     * 安全评审
     */
    public List<CheckIssue> securityReview(CheckContext context) {
        return review(context, "security");
    }

    /**
     * 设计评审
     */
    public List<CheckIssue> designReview(CheckContext context) {
        return review(context, "design");
    }

    /**
     * 合并评审：单次 LLM 调用覆盖多个评审维度（semantic/security/design）。
     *
     * 提示词要求每条问题携带 type 字段标明所属维度，解析时按 type 归入
     * 对应检查器类型；type 缺失或未知时回落到主维度（semantic 优先）。
     *
     * @param reviewTypes 启用的维度列表（非空，取值 semantic/security/design）
     */
    public List<CheckIssue> combinedReview(CheckContext context, List<String> reviewTypes) {
        if (reviewTypes == null || reviewTypes.isEmpty()) {
            return Collections.emptyList();
        }
        AiChatClient client = aiClientFactory.getActiveClient();
        if (client == null) {
            log.debug("AI 未配置，跳过 AI 评审");
            return Collections.emptyList();
        }

        try {
            AiChatRequest request = AiChatRequest.builder()
                    .systemPrompt(buildCombinedSystemPrompt(reviewTypes))
                    .userPrompt(buildUserPrompt(context))
                    .temperature(0.3)
                    // 输出封顶：慢模型下单次调用耗时主要由输出长度决定；
                    // 客户端 effectiveMaxTokens 会与厂商「最大 Token 限制」取小，不会越过用户配置
                    .maxTokens(COMBINED_MAX_TOKENS)
                    .variables(buildVars(context))
                    .build();

            AiChatResponse response = client.chat(request);

            if (!response.isSuccess()) {
                log.warn("AI 合并评审失败: {}", response.getErrorMessage());
                return Collections.emptyList();
            }

            return parseIssues(response.getContent(), reviewTypes, context);

        } catch (Exception e) {
            log.error("AI 合并评审异常: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 执行单维度 AI 评审
     */
    private List<CheckIssue> review(CheckContext context, String reviewType) {
        AiChatClient client = aiClientFactory.getActiveClient();
        if (client == null) {
            log.debug("AI 未配置，跳过 AI 评审");
            return Collections.emptyList();
        }

        try {
            String systemPrompt = buildSystemPrompt(reviewType);
            String userPrompt = buildUserPrompt(context);

            AiChatRequest request = AiChatRequest.builder()
                    .systemPrompt(systemPrompt)
                    .userPrompt(userPrompt)
                    .temperature(0.3)
                    // maxTokens 不在此硬编码：由厂商配置中的「最大 Token 限制」决定（默认不限制）
                    .variables(buildVars(context))
                    .build();

            AiChatResponse response = client.chat(request);

            if (!response.isSuccess()) {
                log.warn("AI 评审失败: {}", response.getErrorMessage());
                return Collections.emptyList();
            }

            return parseIssues(response.getContent(), reviewType, context);

        } catch (Exception e) {
            log.error("AI 评审异常: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private Map<String, String> buildVars(CheckContext context) {
        Map<String, String> vars = new HashMap<>();
        vars.put("code", context.getSourceCode());
        vars.put("file_name", context.getCurrentFilePath());
        vars.put("jdk_version", context.getJdkVersion() != null ? context.getJdkVersion() : "");
        vars.put("spring_boot_version", context.getSpringBootVersion() != null ? context.getSpringBootVersion() : "");
        return vars;
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt(String reviewType) {
        String basePrompt = "你是一位资深的 Java 代码审查专家。请仔细审查以下代码，找出其中的问题。" +
                "请以 JSON 数组格式返回结果，每个问题包含以下字段：" +
                "title（问题标题）、description（问题描述）、level（严重程度，五选一：BLOCKER 阻断/CRITICAL 严重/MAJOR 主要/MINOR 次要/INFO 提示）、" +
                "line（行号）、suggestion（修复建议）。" +
                "只返回 JSON 数组，不要返回其他文字。" +
                "字符串值必须是合法 JSON 字符串：内容里出现的双引号写成 \\\" 或改用「」，字符串内不要裸换行。";

        return switch (reviewType) {
            case "security" -> basePrompt + "\n重点关注：安全漏洞、注入风险、敏感信息泄露、权限问题、加密问题等。";
            case "design" -> basePrompt + "\n重点关注：代码设计、架构模式、SOLID 原则、可扩展性、可维护性、耦合度等。";
            default -> basePrompt + "\n关注：代码质量、可读性、最佳实践、潜在 bug、性能问题等。";
        };
    }

    /**
     * 构建合并评审系统提示词：单次调用覆盖全部启用维度，每条问题带 type 字段。
     * 合并后单条响应更长，加软上限（10 条）降低 maxTokens 截断概率。
     */
    private String buildCombinedSystemPrompt(List<String> reviewTypes) {
        StringBuilder sb = new StringBuilder("你是一位资深的 Java 代码审查专家。请仔细审查以下代码，找出其中的问题。")
                .append("请以 JSON 数组格式返回结果，每个问题包含以下字段：")
                .append("title（问题标题）、description（问题描述）、level（严重程度，五选一：BLOCKER 阻断/CRITICAL 严重/MAJOR 主要/MINOR 次要/INFO 提示）、")
                .append("line（行号）、suggestion（修复建议）、type（评审维度，取值：")
                .append(String.join("/", reviewTypes)).append("）。")
                .append("只返回 JSON 数组，不要返回其他文字。")
                .append("聚焦最重要的问题，单文件合计不超过 5 条；description 与 suggestion 各不超过 50 字、直指要害。")
                .append("字符串值必须是合法 JSON 字符串：内容里出现的双引号写成 \\\" 或改用「」，字符串内不要裸换行。");
        for (String type : reviewTypes) {
            switch (type) {
                case "security" -> sb.append("\ntype=security 重点关注：安全漏洞、注入风险、敏感信息泄露、权限问题、加密问题等。");
                case "design" -> sb.append("\ntype=design 重点关注：代码设计、架构模式、SOLID 原则、可扩展性、可维护性、耦合度等。");
                default -> sb.append("\ntype=semantic 关注：代码质量、可读性、最佳实践、潜在 bug、性能问题等。");
            }
        }
        return sb.toString();
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(CheckContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件名: ").append(context.getCurrentFilePath()).append("\n");
        if (context.getJdkVersion() != null) {
            sb.append("JDK 版本: ").append(context.getJdkVersion()).append("\n");
        }
        sb.append("\n代码内容:\n```java\n");
        // 只发送前 300 行，避免 token 超限
        List<String> lines = context.getSourceLines();
        int maxLines = Math.min(lines.size(), 300);
        for (int i = 0; i < maxLines; i++) {
            sb.append(String.format("%4d | ", i + 1)).append(lines.get(i)).append("\n");
        }
        if (lines.size() > maxLines) {
            sb.append("... (共 ").append(lines.size()).append(" 行，已截断)\n");
        }
        sb.append("```\n");
        return sb.toString();
    }

    /**
     * 解析 AI 返回的问题（单维度入口，检查器独立调用时使用）
     */
    private List<CheckIssue> parseIssues(String responseContent, String reviewType, CheckContext context) {
        return parseIssues(responseContent, List.of(reviewType), context);
    }

    /**
     * 解析 AI 返回的问题（多维度合并入口）。
     *
     * 每条问题按 type 字段归入对应维度；type 缺失或不在启用维度内时回落主维度。
     * 单维度调用时响应本就不含 type 字段，回落行为与旧版完全一致。
     */
    List<CheckIssue> parseIssues(String responseContent, List<String> allowedTypes, CheckContext context) {
        List<CheckIssue> issues = new ArrayList<>();
        if (responseContent == null || responseContent.isEmpty()) {
            return issues;
        }
        String primaryType = allowedTypes.contains("semantic") ? "semantic" : allowedTypes.get(0);

        try {
            // 尝试提取 JSON 数组
            String jsonStr = extractJsonArray(responseContent);
            if (jsonStr == null) {
                // 如果解析不出 JSON，把整个响应作为一个 INFO 级别的建议
                issues.add(fallbackIssue(responseContent, primaryType, context));
                return issues;
            }

            List<Map<String, Object>> issueList = readIssueList(jsonStr);
            if (issueList == null) {
                // 三级容错都救不回：整份响应降级为一条 INFO 建议，不再静默丢弃
                log.warn("AI 响应 JSON 无法修复，降级为整条建议: {}", abbreviate(responseContent));
                issues.add(fallbackIssue(responseContent, primaryType, context));
                return issues;
            }

            for (Map<String, Object> item : issueList) {
                try {
                    String levelStr = String.valueOf(item.getOrDefault("level", "INFO")).toUpperCase();
                    IssueLevel level = IssueLevel.fromCode(levelStr);

                    String title = String.valueOf(item.getOrDefault("title", "AI 发现的问题"));
                    String description = String.valueOf(item.getOrDefault("description", ""));
                    String suggestion = item.get("suggestion") != null
                            ? String.valueOf(item.get("suggestion")) : null;

                    int line = 1;
                    if (item.get("line") != null) {
                        try {
                            line = Integer.parseInt(String.valueOf(item.get("line")));
                        } catch (NumberFormatException ignored) {
                        }
                    }

                    String reviewType = resolveType(item.get("type"), allowedTypes);

                    CheckIssue issue = CheckIssue.builder()
                            .level(level)
                            .checkerType(getCheckerType(reviewType))
                            .checkerName(getCheckerName(reviewType))
                            .ruleCode("AI_" + reviewType.toUpperCase())
                            .title(title)
                            .description(description)
                            .suggestion(suggestion)
                            .filePath(context.getCurrentFilePath())
                            .fileName(FileUtils.extractFileName(context.getCurrentFilePath()))
                            .lineStart(line)
                            .lineEnd(line)
                            .aiGenerated(true)
                            .severity(SeverityCatalog.rank(level))
                            .build();

                    issues.add(issue);
                } catch (Exception e) {
                    log.debug("解析 AI 问题条目失败: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("解析 AI 响应失败: {}", e.getMessage());
        }

        return issues;
    }

    /** 整份响应无法结构化解析时的降级建议条目 */
    private CheckIssue fallbackIssue(String responseContent, String reviewType, CheckContext context) {
        return CheckIssue.builder()
                .level(IssueLevel.INFO)
                .checkerType(getCheckerType(reviewType))
                .checkerName(getCheckerName(reviewType))
                .ruleCode("AI_REVIEW_SUGGESTION")
                .title("AI 评审建议")
                .description(responseContent)
                .filePath(context.getCurrentFilePath())
                .lineStart(1)
                .aiGenerated(true)
                .severity(SeverityCatalog.rank(IssueLevel.INFO))
                .build();
    }

    /** type 字段归一化：小写后必须落在启用维度内，否则回落主维度（semantic 优先） */
    private String resolveType(Object raw, List<String> allowedTypes) {
        String t = raw == null ? "" : String.valueOf(raw).trim().toLowerCase();
        if (allowedTypes.contains(t)) {
            return t;
        }
        return allowedTypes.contains("semantic") ? "semantic" : allowedTypes.get(0);
    }

    /**
     * 解析 AI 返回的问题数组，三级容错：
     * 严格解析 → 转义字符串内嵌引号后重解 → 截掉尾部坏条目抢救前面完整条目。
     * 大模型不保证严格 JSON（建议文案里带未转义双引号很常见），直接 readValue
     * 会把整批结果丢掉；全部失败返回 null，由调用方降级。
     */
    List<Map<String, Object>> readIssueList(String json) {
        TypeReference<List<Map<String, Object>>> listType = new TypeReference<>() {};
        try {
            return objectMapper.readValue(json, listType);
        } catch (Exception ignored) {
            // 落入修复流程
        }
        String repaired = escapeInnerQuotes(json);
        try {
            List<Map<String, Object>> list = objectMapper.readValue(repaired, listType);
            log.info("AI 响应含未转义引号，修复后解析成功（{} 条）", list.size());
            return list;
        } catch (Exception ignored) {
            // 落入抢救流程
        }
        String truncated = json;
        for (int i = 0; i < 32; i++) {
            int cut = truncated.lastIndexOf('}');
            if (cut < 0) {
                break;
            }
            truncated = truncated.substring(0, cut + 1) + "]";
            try {
                List<Map<String, Object>> list = objectMapper.readValue(truncated, listType);
                log.warn("AI 响应 JSON 残缺，截掉尾部坏条目后抢救出 {} 条", list.size());
                return list;
            } catch (Exception ignored) {
                // 继续往前截
            }
        }
        return null;
    }

    /**
     * 转义 JSON 字符串值里未转义的双引号：引号后跟 , } ] : 或结尾才认为是
     * 真正的结束引号，否则视为内容引号补一个反斜杠。
     */
    String escapeInnerQuotes(String json) {
        StringBuilder sb = new StringBuilder(json.length() + 16);
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (!inString) {
                if (c == '"') {
                    inString = true;
                }
                sb.append(c);
                continue;
            }
            if (c == '\\') {
                sb.append(c);
                if (i + 1 < json.length()) {
                    i++;
                    sb.append(json.charAt(i));
                }
                continue;
            }
            if (c == '"') {
                int j = i + 1;
                while (j < json.length() && Character.isWhitespace(json.charAt(j))) {
                    j++;
                }
                char next = j < json.length() ? json.charAt(j) : 0;
                if (next == ',' || next == '}' || next == ']' || next == ':' || next == 0) {
                    inString = false;
                    sb.append(c);
                } else {
                    sb.append('\\').append(c);
                }
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
    }

    /**
     * 从 AI 响应中提取 JSON 数组
     */
    private String extractJsonArray(String content) {
        if (content == null) return null;

        // 尝试直接解析
        content = content.trim();

        // 找第一个 [ 和最后一个 ]
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');

        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }

        return null;
    }

    private CheckerType getCheckerType(String reviewType) {
        return switch (reviewType) {
            case "security" -> CheckerType.AI_SECURITY;
            case "design" -> CheckerType.AI_DESIGN;
            default -> CheckerType.AI_SEMANTIC;
        };
    }

    private String getCheckerName(String reviewType) {
        return switch (reviewType) {
            case "security" -> "AI安全评审";
            case "design" -> "AI设计评审";
            default -> "AI语义评审";
        };
    }

}

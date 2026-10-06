package com.qqmu.jargus.llm;

import com.qqmu.jargus.entity.AiProviderConfig;
import com.qqmu.jargus.llm.impl.AnthropicClient;
import com.qqmu.jargus.llm.impl.CustomHttpClient;
import com.qqmu.jargus.llm.impl.GeminiClient;
import com.qqmu.jargus.llm.impl.OpenAiCompatibleClient;
import com.qqmu.jargus.llm.impl.QianfanClient;
import com.qqmu.jargus.service.ProviderConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 客户端工厂
 *
 * 根据厂商配置创建对应的客户端实例。
 * 按激活配置的 id 缓存客户端单例：WebClient 内部持有 Reactor Netty 连接池与 EventLoop，
 * 每次 chat 都 new 会反复建连、FD 泄漏。配置保存/删除时通过 evict 失效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiClientFactory {

    private final ProviderConfigService providerConfigService;

    /** 已建客户端缓存：key=config.id，value=client 实例。配置变更时 evict。 */
    private final ConcurrentHashMap<Long, AiChatClient> clientCache = new ConcurrentHashMap<>();

    /** 清除全部客户端缓存（配置保存/删除/激活切换时调用，下次取用会重建）。 */
    public void evictAll() {
        clientCache.clear();
    }

    /** 失效单个配置对应的客户端（按 id 删除）。 */
    public void evict(Long configId) {
        if (configId != null) clientCache.remove(configId);
    }

    /**
     * 获取当前激活的 AI 客户端
     *
     * @return AI 客户端，如果没有配置则返回 null
     */
    public AiChatClient getActiveClient() {
        AiProviderConfig activeConfig = providerConfigService.getActive();
        if (activeConfig == null) {
            log.debug("没有激活的 AI 厂商配置");
            return null;
        }
        // 缓存命中：同一激活配置直接复用（WebClient 连接池保留）
        return clientCache.computeIfAbsent(activeConfig.getId(), id -> createClient(activeConfig));
    }

    /**
     * 根据配置创建客户端（不经过缓存，仅供测试连接/后台即时使用）。
     */
    public AiChatClient createClient(AiProviderConfig config) {
        if (config == null) return null;

        String protocol = config.getProtocolType();
        if (protocol == null) {
            protocol = "OPENAI_COMPATIBLE";
        }

        try {
            return switch (protocol.toUpperCase()) {
                case "OPENAI_COMPATIBLE", "OPENAI" -> new OpenAiCompatibleClient(config);
                case "ANTHROPIC", "CLAUDE" -> new AnthropicClient(config);
                case "QIANFAN" -> new QianfanClient(config);
                case "GEMINI" -> new GeminiClient(config);
                case "CUSTOM_HTTP", "CUSTOM" -> new CustomHttpClient(config);
                default -> {
                    log.warn("未知的协议类型: {}，默认使用 OpenAI 兼容", protocol);
                    yield new OpenAiCompatibleClient(config);
                }
            };
        } catch (Exception e) {
            log.error("创建 AI 客户端失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 检查是否已配置 AI
     */
    public boolean isAiConfigured() {
        return providerConfigService.getActive() != null;
    }
}

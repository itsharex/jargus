package com.qqmu.jargus.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 响应 JSON 解析容错回归测试。
 *
 * 大模型返回未转义引号 / 尾部残缺的 JSON 是常态（线上真实故障：整批结果被
 * 静默丢弃），这里钉住三级容错：严格解析 → 引号修复 → 尾部截断抢救。
 * 纯字符串进出，不碰任何 AI 厂商。
 */
class AiReviewServiceParseTest {

    private final AiReviewService service = new AiReviewService(null);

    @Test
    void wellFormedArrayParsesDirectly() {
        String json = "[{\"title\":\"t1\",\"level\":\"MAJOR\",\"line\":3,\"suggestion\":\"s1\"},"
                + "{\"title\":\"t2\",\"level\":\"MINOR\",\"line\":7,\"suggestion\":\"s2\"}]";
        List<Map<String, Object>> list = service.readIssueList(json);
        assertEquals(2, list.size());
        assertEquals("t1", list.get(0).get("title"));
        assertEquals(7, list.get(1).get("line"));
    }

    @Test
    void unescapedQuotesInsideSuggestionAreRepaired() {
        // 复现线上故障形态：suggestion 文案里带未转义双引号
        String json = "[\n"
                + "  {\"title\":\"t1\",\"level\":\"MAJOR\",\"line\":13,\"suggestion\":\"补充 @Column(nullable = false) 约束\"},\n"
                + "  {\"title\":\"t2\",\"level\":\"MINOR\",\"line\":15,\"suggestion\":\"可声明 @Column(length = 500) 或使用 @Column(columnDefinition = \"text\") 显式长文本\"}\n"
                + "]";
        List<Map<String, Object>> list = service.readIssueList(json);
        assertEquals(2, list.size(), "修复后应保留全部条目");
        String suggestion = String.valueOf(list.get(1).get("suggestion"));
        assertTrue(suggestion.contains("\"text\""), "内嵌引号应还原为值内容: " + suggestion);
    }

    @Test
    void truncatedTailIsSalvaged() {
        // 复现 maxTokens 截断形态：第二个对象写到一半断开
        String json = "[\n"
                + "  {\"title\":\"t1\",\"level\":\"INFO\",\"line\":1,\"suggestion\":\"s\"},\n"
                + "  {\"title\":\"t2\",\"descri";
        List<Map<String, Object>> list = service.readIssueList(json);
        assertEquals(1, list.size(), "尾部坏条目截掉后应抢救出前面的完整条目");
        assertEquals("t1", list.get(0).get("title"));
    }

    @Test
    void escapeInnerQuotesKeepsStructuralQuotes() {
        String repaired = service.escapeInnerQuotes("{\"a\": \"say \"hi\" ok\", \"b\": 1}");
        assertEquals("{\"a\": \"say \\\"hi\\\" ok\", \"b\": 1}", repaired);
    }

    @Test
    void unrecoverableContentReturnsNull() {
        assertNull(service.readIssueList("完全不是 JSON 的散文响应"));
    }
}

package org.offblink.spore.tools;

/**
 * 桌面 `tools.js` 的 TOOLS 定义 → OpenAI function tools JSON。
 * 描述文本逐字照抄（模型看到的工具语义不能变）；引擎经
 * {@link #toolsJson()} 直接塞进请求体。
 */
public final class ToolSchemas {

    private ToolSchemas() {
    }

    private static final String TOOLS_JSON = "["
            + "{\"type\":\"function\",\"function\":{"
            + "\"name\":\"web_search\","
            + "\"description\":\"联网检索（DuckDuckGo → Bing）。返回编号的标题、URL、摘要。"
            + "ERROR: 表示各引擎都被节流或不可达——稍后重试或换措辞；"
            + "(no results ...) 表示引擎有响应但确实没有命中。"
            + "用于核实事实、年份、术语、数据、最新信息。\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{"
            + "\"query\":{\"type\":\"string\",\"description\":\"检索词，一次聚焦一个关键点\"}},"
            + "\"required\":[\"query\"]}}},"
            + "{\"type\":\"function\",\"function\":{"
            + "\"name\":\"web\","
            + "\"description\":\"抓取某个网页的正文纯文本（在搜索结果的基础上深读）。\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{"
            + "\"url\":{\"type\":\"string\",\"description\":\"http(s) 链接\"}},"
            + "\"required\":[\"url\"]}}}"
            + "]";

    /** OpenAI tools 数组 JSON 字符串 */
    public static String toolsJson() {
        return TOOLS_JSON;
    }
}

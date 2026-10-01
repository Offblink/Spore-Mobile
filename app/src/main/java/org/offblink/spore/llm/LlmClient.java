package org.offblink.spore.llm;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import okio.BufferedSource;

/**
 * OpenAI 兼容的 SSE 流式客户端。语义照抄桌面 `src/lib/llm.js`：
 * <ul>
 *   <li>tool_calls 按 index 装配（并行多路也正确）</li>
 *   <li>只对瞬时错误重试（网络/429/5xx/流中断），且<b>只在尚未吐字时重试</b>——
 *       避免把已渲染给用户的文本重放一遍</li>
 *   <li>空闲看门狗 60s（OkHttp readTimeout 承担：每块 chunk 都重置计时）</li>
 *   <li>{@link #cancel()} 即中止，抛 {@link AbortedException}</li>
 * </ul>
 * 调用方保证：{@link #streamChat} 跑在后台线程（阻塞式）。
 */
public final class LlmClient {

    public static final class AbortedException extends RuntimeException {
        public AbortedException() {
            super("aborted");
        }
    }

    /** 不可重试的失败（HTTP 4xx 除 429、请求体被拒等）：直接抛给调用方 */
    public static final class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }
    }

    /** 可重试的瞬时失败：仅在「尚未吐字」时由 streamChat 内部重试 */
    private static final class CallFail extends RuntimeException {
        CallFail(String message) {
            super(message);
        }
    }

    public static final class ToolCall {
        public String id;
        public String name;
        public String args;
    }

    /** 一次调用的累积结果（onDelta 每次都拿到这份活对象） */
    public static final class Result {
        public String content = "";
        public String reasoning = "";
        public final List<ToolCall> toolCalls = new ArrayList<>();
    }

    public interface DeltaListener {
        /** kind ∈ text | reasoning | tool；acc 为累积态 */
        void onDelta(String kind, String chunk, Result acc);
    }

    public static final class ChatRequest {
        public String endpoint;
        public String apiKey;
        public String model;
        public JSONArray messages;
        /** OpenAI tools 数组 JSON；null = 无工具 */
        public String toolsJson;
        public Integer maxTokens;
        /** 「直接作答」模式：reasoning_effort=none + thinking=disabled（桌面实测可归零思考） */
        public boolean noThink;
        public DeltaListener onDelta;
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int RETRY_LIMIT = 3;
    private static final long IDLE_TIMEOUT_MS = 60_000;
    private static final long RETRY_PAUSE_MS = 700;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build();

    private volatile Call active;

    /** 中止在途调用（面板「停止」按钮的落地） */
    public void cancel() {
        Call c = active;
        if (c != null) {
            c.cancel();
        }
    }

    /**
     * 一次流式调用（带重试）。抛 {@link AbortedException}/{@link LlmException}。
     */
    public Result streamChat(ChatRequest req) {
        CallFail last = null;
        for (int attempt = 1; attempt <= RETRY_LIMIT; attempt++) {
            try {
                return once(req);
            } catch (AbortedException a) {
                throw a;
            } catch (LlmException hard) {
                throw hard;
            } catch (CallFail soft) {
                last = soft;
                if (attempt < RETRY_LIMIT) {
                    try {
                        Thread.sleep(RETRY_PAUSE_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new AbortedException();
                    }
                }
            }
        }
        throw new LlmException(last != null ? last.getMessage() : "stream failed");
    }

    private Result once(ChatRequest req) {
        Result acc = new Result();
        JSONObject body = new JSONObject();
        try {
            body.put("model", req.model);
            body.put("messages", req.messages);
            body.put("stream", true);
            body.put("stream_options", new JSONObject().put("include_usage", false));
            if (req.noThink) {
                body.put("reasoning_effort", "none");
                body.put("thinking", new JSONObject().put("type", "disabled"));
            }
            if (req.maxTokens != null) {
                body.put("max_tokens", req.maxTokens.intValue());
            }
            if (req.toolsJson != null) {
                body.put("tools", new JSONArray(req.toolsJson));
            }
        } catch (JSONException e) {
            throw new LlmException("build body failed: " + e);
        }

        okhttp3.Request httpReq = new okhttp3.Request.Builder()
                .url(req.endpoint)
                .post(RequestBody.create(body.toString(), JSON))
                .addHeader("Content-Type", "application/json")
                .build();
        if (req.apiKey != null && !req.apiKey.trim().isEmpty()) {
            httpReq = httpReq.newBuilder()
                    .addHeader("Authorization", "Bearer " + req.apiKey.trim())
                    .build();
        }

        Call call = HTTP.newCall(httpReq);
        active = call;
        try (Response resp = execute(call)) {
            if (!resp.isSuccessful()) {
                int code = resp.code();
                // 只有 429/5xx 是「瞬时错误」；其余 4xx 请求本身有问题，重试无意义
                if (code == 429 || code >= 500) {
                    throw new CallFail("HTTP " + code);
                }
                String snippet = "";
                try (ResponseBody rb = resp.body()) {
                    if (rb != null) {
                        snippet = rb.string();
                    }
                } catch (IOException ignored) {
                    // 读错误体失败不影响状态码判定
                }
                throw new LlmException("HTTP " + code + " " + snippet);
            }
            ResponseBody rb = resp.body();
            if (rb == null) {
                throw new CallFail("empty body");
            }
            readStream(rb, req, acc);
            return acc;
        } catch (AbortedException | LlmException e) {
            throw e;
        } catch (CallFail e) {
            throw e;
        } catch (IOException io) {
            if (call.isCanceled()) {
                throw new AbortedException();
            }
            // 网络类失败：未吐字可重试，吐过字就只能带着已有内容收场（桌面同款策略）
            if (acc.content.isEmpty()) {
                throw new CallFail(String.valueOf(io.getMessage()));
            }
            throw new LlmException("stream interrupted: " + io.getMessage());
        } finally {
            active = null;
        }
    }

    private Response execute(Call call) throws IOException {
        return call.execute();
    }

    private void readStream(ResponseBody rb, ChatRequest req, Result acc) throws IOException, CallFail {
        Map<Integer, ToolCall> slots = new LinkedHashMap<>();
        boolean done = false;
        BufferedSource src = rb.source();
        while (true) {
            String line;
            try {
                line = src.readUtf8Line();
            } catch (IOException io) {
                if (active != null && active.isCanceled()) {
                    throw new AbortedException();
                }
                if (acc.content.isEmpty()) {
                    // 流中断在吐字前 = 可重试
                    throw new CallFail("stream interrupted: " + io.getMessage());
                }
                throw io;
            }
            if (line == null) {
                break;
            }
            if (line.isEmpty() || !line.startsWith("data:")) {
                continue;
            }
            String data = line.substring(5).trim();
            if ("[DONE]".equals(data)) {
                done = true;
                break;
            }
            applyDelta(data, req, acc, slots);
        }
        if (!done && acc.content.isEmpty()) {
            throw new CallFail("stream ended");
        }
        acc.toolCalls.clear();
        for (Map.Entry<Integer, ToolCall> e : slots.entrySet()) {
            ToolCall t = e.getValue();
            if (t.name != null && !t.name.isEmpty()) {
                if (t.id == null || t.id.isEmpty()) {
                    t.id = "call_" + e.getKey();
                }
                acc.toolCalls.add(t);
            }
        }
    }

    /**
     * 线上帧字段读取：只有真字符串才算数，JSON null / 数字 / 缺失一律当空。
     * <b>别改回 {@code optString}</b>——Android 的 org.json 会把 JSON null 变成字面量
     * {@code "null"}（{@code JSON.toString} 对非 String 走 {@code String.valueOf}，
     * 而 {@code JSONObject.NULL.toString()} = "null"），桌面 JS 的真值判断天然跳过 null。
     * 直接用 optString 会让核实区刷满 "null"、并把垃圾文本回灌给模型
     * （2026-10-01 第四轮反馈「核实右侧满屏 null + 崩悬浮窗」根因）。
     * 包内可见是为 {@code LlmClientTest} 钉契约。
     */
    static String wireStr(JSONObject o, String key) {
        Object v = o.opt(key);
        return v instanceof String ? (String) v : "";
    }

    private void applyDelta(String data, ChatRequest req, Result acc, Map<Integer, ToolCall> slots) {
        JSONObject delta;
        try {
            JSONArray choices = new JSONObject(data).optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return;
            }
            JSONObject ch = choices.optJSONObject(0);
            delta = ch != null ? ch.optJSONObject("delta") : null;
        } catch (JSONException e) {
            return; // 单帧坏 JSON 不打断整条流
        }
        if (delta == null) {
            return;
        }

        String text = wireStr(delta, "content");
        if (!text.isEmpty()) {
            acc.content += text;
            if (req.onDelta != null) {
                req.onDelta.onDelta("text", text, acc);
            }
        }
        String think = wireStr(delta, "reasoning_content");
        if (think.isEmpty()) {
            think = wireStr(delta, "reasoning");
        }
        if (!think.isEmpty()) {
            acc.reasoning += think;
            if (req.onDelta != null) {
                req.onDelta.onDelta("reasoning", think, acc);
            }
        }

        JSONArray tcs = delta.optJSONArray("tool_calls");
        if (tcs == null) {
            return;
        }
        for (int i = 0; i < tcs.length(); i++) {
            JSONObject tc = tcs.optJSONObject(i);
            if (tc == null) {
                continue;
            }
            int index = tc.optInt("index", i);
            ToolCall slot = slots.get(index);
            if (slot == null) {
                slot = new ToolCall();
                slots.put(index, slot);
            }
            String id = wireStr(tc, "id");
            if (!id.isEmpty()) {
                slot.id = id;
            }
            JSONObject fn = tc.optJSONObject("function");
            if (fn != null) {
                String name = wireStr(fn, "name");
                if (!name.isEmpty()) {
                    slot.name = name;
                }
                String args = wireStr(fn, "arguments");
                if (!args.isEmpty()) {
                    slot.args = (slot.args == null ? "" : slot.args) + args;
                    if (req.onDelta != null) {
                        req.onDelta.onDelta("tool", args, acc);
                    }
                }
            }
        }
    }
}

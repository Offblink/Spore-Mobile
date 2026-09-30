package org.offblink.spore.agent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.offblink.spore.SporeSettings;
import org.offblink.spore.llm.LlmClient;
import org.offblink.spore.tools.SearchChain;
import org.offblink.spore.tools.ToolSchemas;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 两阶段作答引擎——移植桌面 `src/lib/agent.js`（先快再准）：
 * 阶段A 读题初答（五行协议 + <<ok>> 守卫 + 极性自检）→ 异步起名（零调用）→
 * 阶段B 联网核实（工具循环，三道闸检索）→ FIX 覆盖答案。
 * 追问 = 纯文本对话分支（带历史）。
 *
 * 线程模型：回合跑在单线程 executor（阻塞式 SSE），事件经 main Handler 回主线程；
 * {@link Listener#onEvent} 保证在主线程。会话是唯一事实源（内存，handoff §9⑤ 接 Room）。
 */
public final class AgentEngine {

    public interface Listener {
        /** 主线程回调；ev 必带 type 字段（协议对齐桌面 emit 的 ev 族） */
        void onEvent(JSONObject ev);
    }

    private final Context app;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spore-agent");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LlmClient llm = new LlmClient();
    private final Session session = new Session();

    private Listener listener;
    private volatile boolean busy;
    private volatile boolean abort;

    public AgentEngine(Context app) {
        this.app = app.getApplicationContext();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public Session session() {
        return session;
    }

    public boolean isBusy() {
        return busy;
    }

    /** 截图提问：新 user(带图) + answer 占位，跑两阶段 */
    public void newCaptureTurn(String imagePath, String supplement) {
        submit(() -> runImageTurn(imagePath, supplement == null ? "" : supplement));
    }

    /** 追问（纯文本，最快分支） */
    public void sendFollowup(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return;
        }
        submit(() -> runChatTurn(t));
    }

    /** 「核实一下」按钮：对最后一条回答单独跑阶段B（与自动核实同一实现） */
    public void verifyOnly() {
        submit(this::runVerifyOnly);
    }

    /** 停止：中止在途流与回合（面板 ■ 按钮） */
    public void cancel() {
        abort = true;
        llm.cancel();
    }

    public void shutdown() {
        cancel();
        exec.shutdownNow();
    }

    private void submit(Runnable turn) {
        if (busy) {
            return;
        }
        busy = true;
        abort = false;
        exec.execute(() -> {
            try {
                turn.run();
            } catch (RuntimeException e) {
                // 兜底：回合方法内部已各自 try/catch，这里只防漏网
                emitError(-1, String.valueOf(e.getMessage() == null ? e : e.getMessage()));
            } finally {
                busy = false;
            }
        });
    }

    // ================================================================ 阶段分支

    private void runImageTurn(String imagePath, String supplement) {
        SporeSettings s = SporeSettings.load(app);
        if (!s.isConfigured()) {
            emitTurnEnd(-1, true, "未配置端点或 API Key，请先到设置页填写");
            return;
        }
        Session.Msg user = new Session.Msg();
        user.role = "user";
        user.hasImage = true;
        user.imagePath = imagePath;
        user.text = supplement;
        user.ts = System.currentTimeMillis();
        session.messages.add(user);

        Session.Msg ans = new Session.Msg();
        ans.role = "assistant";
        ans.kind = "answer";
        ans.ts = System.currentTimeMillis();
        session.messages.add(ans);
        int idx = session.messages.size() - 1;

        session.status = "answering";
        emit("status", "status", "answering", "text", "读题中…");
        emit("answer-start", "idx", idx);

        try {
            String image = imageDataUrl(imagePath);
            String extra = supplement.isEmpty() ? "" : "\n\n用户补充：" + supplement;

            // ---- 阶段A：读题 + 初答（桌面注意：不带 system）----
            LlmClient.ChatRequest r = new LlmClient.ChatRequest();
            fillApi(r, s);
            r.messages = phaseAMessages(image, extra);
            r.noThink = s.fastNoThink;
            r.onDelta = (kind, chunk, acc) -> {
                if ("reasoning".equals(kind)) {
                    if (!acc.reasoning.equals(ans.think)) {
                        ans.think = acc.reasoning;
                        emit("think-delta", "idx", idx, "kind", "answer", "think", ans.think);
                    }
                    return;
                }
                if (!"text".equals(kind) || chunk.isEmpty()) {
                    return;
                }
                Phases.PhaseA p = Phases.parsePhaseA(acc.content);
                if (!p.no.equals(ans.no) || !p.title.equals(ans.title)
                        || !p.ans.equals(ans.ans) || !p.why.equals(ans.why)) {
                    ans.no = p.no;
                    ans.title = p.title;
                    ans.ans = p.ans;
                    ans.why = p.why;
                    emitAnswerDelta(idx, ans);
                }
            };
            LlmClient.Result resA = llm.streamChat(r);
            String rawA = resA.content == null ? "" : resA.content;
            Phases.PhaseA parsedA = Phases.parsePhaseA(rawA);
            if (!parsedA.no.isEmpty()) {
                ans.no = parsedA.no;
            }
            if (!parsedA.title.isEmpty()) {
                ans.title = parsedA.title;
            }
            if (!parsedA.ans.isEmpty()) {
                ans.ans = parsedA.ans;
            }
            if (!parsedA.why.isEmpty()) {
                ans.why = parsedA.why;
            }
            emitAnswerDelta(idx, ans);

            // 起名立刻发出（纯本地、零额外调用），不等核实
            applyNaming(ans);

            // ---- 守卫：<<ok>> 跳核实；但答案行与解析打架时必须复核 ----
            boolean certain = Phases.isSelfCertain(rawA);
            boolean fighting = Phases.contradicts(ans.ans, ans.why);
            if (certain && !fighting) {
                ans.verifyRan = false;
                ans.verifySkipped = true;
                ans.verifyVerdict = "OK";
                ans.verifyNote = "初答自评「确定」（<<ok>> 守卫），已跳过联网核实。";
                emitVerifyDone(idx, ans, true, false, false);
                session.status = "done";
                emitTurnEnd(idx, false, null);
                return;
            }

            // ---- 阶段B：联网核实；设置关了自动核实就留「核实一下」按钮 ----
            if (!s.autoVerify) {
                ans.verifyPending = true;
                ans.verifyNote = "已关闭自动核实 · 点「核实一下」开始核实";
                emitVerifyDone(idx, ans, false, false, true);
            } else {
                verifyPhase(s, ans, idx, image, extra);
            }
            session.status = "done";
            emitTurnEnd(idx, false, null);
        } catch (AbortedTurn a) {
            session.status = "aborted";
            emitTurnEnd(idx, false, null, "aborted", true);
        } catch (Exception e) {
            failTurn(e, idx);
        }
    }

    private void runChatTurn(String text) {
        SporeSettings s = SporeSettings.load(app);
        if (!s.isConfigured()) {
            emitTurnEnd(-1, true, "未配置端点或 API Key，请先到设置页填写");
            return;
        }
        Session.Msg user = new Session.Msg();
        user.role = "user";
        user.text = text;
        user.ts = System.currentTimeMillis();
        session.messages.add(user);

        Session.Msg chat = new Session.Msg();
        chat.role = "assistant";
        chat.kind = "chat";
        chat.ts = System.currentTimeMillis();
        session.messages.add(chat);
        int idx = session.messages.size() - 1;

        session.status = "answering";
        emit("chat-start", "idx", idx);
        emit("status", "status", "answering", "text", "回答中…");
        try {
            LlmClient.ChatRequest r = new LlmClient.ChatRequest();
            fillApi(r, s);
            JSONArray msgs = new JSONArray();
            msgs.put(jo("role", "system", "content", Prompts.SYSTEM));
            JSONArray hist = historyMessages(s);
            for (int i = 0; i < hist.length(); i++) {
                msgs.put(hist.get(i));
            }
            r.messages = msgs;
            r.noThink = s.fastNoThink;
            r.onDelta = (kind, chunk, acc) -> {
                if ("reasoning".equals(kind)) {
                    if (!acc.reasoning.equals(chat.think)) {
                        chat.think = acc.reasoning;
                        emit("think-delta", "idx", idx, "kind", "chat", "think", chat.think);
                    }
                    return;
                }
                if ("text".equals(kind) && !chunk.isEmpty()) {
                    chat.text += chunk;
                    emit("chat-delta", "idx", idx, "text", chunk, "total", chat.text);
                }
            };
            LlmClient.Result res = llm.streamChat(r);
            if (chat.text.isEmpty() && res.content != null) {
                chat.text = res.content;
            }
            session.status = "done";
            emitTurnEnd(idx, false, null);
        } catch (AbortedTurn a) {
            session.status = "aborted";
            emitTurnEnd(idx, false, null, "aborted", true);
        } catch (Exception e) {
            failTurn(e, idx);
        }
    }

    private void runVerifyOnly() {
        SporeSettings s = SporeSettings.load(app);
        if (!s.isConfigured()) {
            emitTurnEnd(-1, true, "未配置端点或 API Key，请先到设置页填写");
            return;
        }
        int idx = -1;
        Session.Msg ans = null;
        for (int i = session.messages.size() - 1; i >= 0; i--) {
            Session.Msg m = session.messages.get(i);
            if ("answer".equals(m.kind)) {
                ans = m;
                idx = i;
                break;
            }
        }
        if (ans == null) {
            emitTurnEnd(-1, true, "没有可核实的回答");
            return;
        }
        Session.Msg lastUser = null;
        for (int i = session.messages.size() - 1; i >= 0; i--) {
            Session.Msg m = session.messages.get(i);
            if ("user".equals(m.role)) {
                lastUser = m;
                break;
            }
        }
        String image = null;
        if (lastUser != null && lastUser.hasImage) {
            try {
                image = imageDataUrl(lastUser.imagePath);
            } catch (IOException e) {
                image = null; // 图丢了 → 下面统一按「截图缺失」报错
            }
        }
        String extra = lastUser != null && !lastUser.text.isEmpty()
                ? "\n\n用户补充：" + lastUser.text : "";

        ans.verifyPending = false; // 摘掉按钮
        ans.verifyRan = false;
        session.status = "verifying";
        emit("verify-delta", "idx", idx, "ran", false, "note", "", "pending", false);
        emit("status", "status", "verifying", "text", "核实中…");
        try {
            if (image == null) {
                throw new IOException("截图缺失，无法核实");
            }
            verifyPhase(s, ans, idx, image, extra);
            session.status = "done";
            emitTurnEnd(idx, false, null);
        } catch (AbortedTurn a) {
            session.status = "aborted";
            emitTurnEnd(idx, false, null, "aborted", true);
        } catch (Exception e) {
            failTurn(e, idx);
        }
    }

    // ================================================================ 阶段B

    private void verifyPhase(SporeSettings s, Session.Msg ans, int idx,
                             String image, String extra) throws Exception {
        int maxRounds = Math.max(0, s.maxToolRounds);
        if (maxRounds <= 0) {
            return; // 桌面同款：0 轮 = 不核实直接收尾
        }
        SearchChain.setProxy(s.proxy);
        session.status = "verifying";
        emit("status", "status", "verifying", "text", "核实中…");

        JSONArray msgs = verifyMessages(image, extra, ans, maxRounds);
        Phases.PhaseB verify = null;

        for (int round = 0; round <= maxRounds; round++) {
            checkAbort();
            LlmClient.ChatRequest r = new LlmClient.ChatRequest();
            fillApi(r, s);
            r.messages = msgs;
            r.toolsJson = ToolSchemas.toolsJson();
            r.noThink = false; // 核实阶段要思考，且思考内容会显示出来
            r.onDelta = (kind, chunk, acc) -> {
                if ("reasoning".equals(kind)) {
                    if (!acc.reasoning.equals(ans.verifyThink)) {
                        ans.verifyThink = acc.reasoning;
                        emit("think-delta", "idx", idx, "kind", "verify", "think", ans.verifyThink);
                    }
                    return;
                }
                if ("text".equals(kind) && !chunk.isEmpty()) {
                    ans.verifyStreamNote = acc.content;
                    emit("verify-delta", "idx", idx, "note", acc.content, "verdict", "");
                }
            };
            LlmClient.Result res = llm.streamChat(r);
            if (res.toolCalls.isEmpty()) {
                verify = Phases.parsePhaseB(res.content == null ? "" : res.content);
                break;
            }
            session.status = "searching";
            emit("status", "status", "searching", "text", "检索中…");

            JSONObject assistant = jo("role", "assistant");
            try {
                assistant.put("content", (res.content == null || res.content.isEmpty())
                        ? JSONObject.NULL : res.content);
                JSONArray tcs = new JSONArray();
                for (LlmClient.ToolCall t : res.toolCalls) {
                    tcs.put(jo("id", t.id, "type", "function", "function",
                            jo("name", t.name, "arguments", t.args == null ? "{}" : t.args)));
                }
                assistant.put("tool_calls", tcs);
            } catch (JSONException ignored) {
                // 字面量 key，不会发生
            }
            msgs.put(assistant);

            for (LlmClient.ToolCall t : res.toolCalls) {
                checkAbort();
                JSONObject args = parseArgs(t.args);
                String briefRaw = !args.optString("query").isEmpty()
                        ? args.optString("query") : args.optString("url");
                String brief = briefRaw.length() > 80 ? briefRaw.substring(0, 80) : briefRaw;
                String chip = "web".equals(t.name) ? "读取 " + brief : "检索 " + brief;
                ans.tools.add(chip);
                emit("tool", "idx", idx, "name", t.name, "brief", brief);
                String out = SearchChain.dispatch(t.name, t.args == null ? "{}" : t.args);
                msgs.put(jo("role", "tool", "tool_call_id", t.id, "content", out));
            }
        }

        if (verify == null) {
            // 检索轮次用光：停手，强制收口成严格三行
            checkAbort();
            msgs.put(jo("role", "user", "content", Prompts.PHASE_B_FINAL));
            LlmClient.ChatRequest r = new LlmClient.ChatRequest();
            fillApi(r, s);
            r.messages = msgs;
            r.maxTokens = 300;
            r.noThink = false;
            r.onDelta = (kind, chunk, acc) -> {
                if ("text".equals(kind) && !chunk.isEmpty()) {
                    emit("verify-delta", "idx", idx, "note", acc.content, "verdict", "");
                }
            };
            LlmClient.Result fin = llm.streamChat(r);
            verify = Phases.parsePhaseB(fin.content == null ? "" : fin.content);
        }

        if (verify.verdict.isEmpty() && verify.note.isEmpty()) {
            verify.note = "核实失败（网络或模型异常），可点 ↻ 重试。";
        }

        // 判 FIX 且给出修正答案 → 覆盖答案行（否则「答案说对、说明说错」并排摆着）
        String fixed = verify.ans.trim();
        if ("FIX".equals(verify.verdict) && !fixed.isEmpty()
                && !("无".equals(fixed) || "none".equalsIgnoreCase(fixed) || "-".equals(fixed))
                && !fixed.equals(ans.ans)) {
            ans.ans = fixed;
            emitAnswerDelta(idx, ans);
        }

        ans.verifyRan = true;
        ans.verifyVerdict = verify.verdict;
        ans.verifyNote = verify.note;
        ans.verifyPending = false;
        emitVerifyDone(idx, ans, false, false, false);
    }

    private static JSONObject parseArgs(String argsJson) {
        try {
            return new JSONObject(argsJson);
        } catch (JSONException e) {
            // 尾逗号等常见小毛病：去掉再试一次；再不行按无参处理（桌面同款容错）
            String cleaned = argsJson.replaceAll(",\\s*([}\\]])", "$1");
            try {
                return new JSONObject(cleaned);
            } catch (JSONException e2) {
                return new JSONObject();
            }
        }
    }

    // ================================================================ 消息构造

    private JSONArray phaseAMessages(String image, String extra) {
        JSONArray content = new JSONArray();
        content.put(jo("type", "image_url", "image_url", jo("url", image)));
        content.put(jo("type", "text", "text", Prompts.PHASE_A + extra));
        JSONArray msgs = new JSONArray();
        msgs.put(jo("role", "user", "content", content));
        return msgs;
    }

    private JSONArray verifyMessages(String image, String extra, Session.Msg ans, int maxRounds) {
        JSONArray msgs = new JSONArray();
        msgs.put(jo("role", "system", "content", Prompts.SYSTEM));
        JSONArray content = new JSONArray();
        content.put(jo("type", "image_url", "image_url", jo("url", image)));
        content.put(jo("type", "text", "text", Prompts.PHASE_A + extra));
        msgs.put(jo("role", "user", "content", content));
        String no = ans.no.isEmpty() ? "无" : ans.no;
        msgs.put(jo("role", "assistant",
                "content", "NO: " + no + "\nANS: " + ans.ans + "\nWHY: " + ans.why));
        msgs.put(jo("role", "user",
                "content", Prompts.PHASE_B.replace("$R$", String.valueOf(maxRounds))));
        return msgs;
    }

    /** 追问的历史上下文（桌面 historyMessages）：只保留最近一张图，超限截尾 */
    private JSONArray historyMessages(SporeSettings s) {
        int lastImageIdx = -1;
        for (int i = 0; i < session.messages.size(); i++) {
            Session.Msg m = session.messages.get(i);
            if ("user".equals(m.role) && m.hasImage) {
                lastImageIdx = i;
            }
        }
        String imgUrl = null;
        if (lastImageIdx >= 0) {
            try {
                imgUrl = imageDataUrl(session.messages.get(lastImageIdx).imagePath);
            } catch (Exception e) {
                imgUrl = null; // 图丢了就降级成文本占位
            }
        }
        JSONArray msgs = new JSONArray();
        for (int i = 0; i < session.messages.size(); i++) {
            Session.Msg m = session.messages.get(i);
            if ("user".equals(m.role)) {
                String text = !m.text.isEmpty() ? m.text : (m.hasImage ? "（题目截图）" : "");
                if (imgUrl != null && i == lastImageIdx) {
                    JSONArray content = new JSONArray();
                    content.put(jo("type", "image_url", "image_url", jo("url", imgUrl)));
                    content.put(jo("type", "text", "text", text));
                    msgs.put(jo("role", "user", "content", content));
                } else {
                    msgs.put(jo("role", "user", "content", text));
                }
            } else if ("answer".equals(m.kind)) {
                StringBuilder sb = new StringBuilder();
                sb.append("NO: ").append(m.no.isEmpty() ? "无" : m.no)
                        .append("\nANS: ").append(m.ans)
                        .append("\nWHY: ").append(m.why);
                if (m.verifyRan) {
                    sb.append("\nVERDICT: ").append(m.verifyVerdict.isEmpty() ? "OK" : m.verifyVerdict)
                            .append("\nNOTE: ").append(m.verifyNote);
                }
                msgs.put(jo("role", "assistant", "content", sb.toString()));
            } else if (m.kind != null && !m.text.isEmpty()) {
                msgs.put(jo("role", "assistant", "content", m.text));
            }
        }
        int limit = Math.max(2, s.historyLimit);
        if (msgs.length() > limit) {
            JSONArray trimmed = new JSONArray();
            try {
                for (int i = msgs.length() - limit; i < msgs.length(); i++) {
                    trimmed.put(msgs.get(i));
                }
            } catch (JSONException ignored) {
                // 索引刚量过长度，合法；防御性兜底
            }
            return trimmed;
        }
        return msgs;
    }

    // ================================================================ 收尾与事件

    private void applyNaming(Session.Msg ans) {
        String title = Phases.namingTitle(ans.no, ans.title, ans.ans, ans.why);
        session.title = title;
        emit("title", "title", title);
    }

    private void failTurn(Exception e, int idx) {
        boolean aborted = e instanceof AbortedTurn
                || e instanceof LlmClient.AbortedException
                || e instanceof InterruptedException;
        String msg = String.valueOf(e.getMessage() == null ? e : e.getMessage());
        if (msg.length() > 200) {
            msg = msg.substring(0, 200);
        }
        if (aborted) {
            session.status = "aborted";
            emitTurnEnd(idx, false, null, "aborted", true);
        } else {
            session.status = "error";
            emit("error", "idx", idx, "message", msg);
            emitTurnEnd(idx, false, null, "error", true);
        }
    }

    private void emitAnswerDelta(int idx, Session.Msg ans) {
        emit("answer-delta", "idx", idx,
                "no", ans.no, "title", ans.title, "ans", ans.ans, "why", ans.why,
                "preview", Phases.formatAnswerPreview(ans.no, ans.ans, ans.why));
    }

    private void emitVerifyDone(int idx, Session.Msg ans,
                                boolean skipped, boolean ran, boolean pending) {
        emit("verify-delta", "idx", idx,
                "note", ans.verifyNote,
                "verdict", ans.verifyVerdict,
                "done", true,
                "skipped", skipped,
                "ran", ran,
                "pending", pending);
    }

    private void emitTurnEnd(int idx, boolean error, String errorMessage, Object... extra) {
        JSONObject ev = jo("idx", idx);
        if (error && errorMessage != null) {
            try {
                ev.put("error", true);
                ev.put("message", errorMessage);
            } catch (JSONException ignored) {
            }
        }
        for (int i = 0; i + 1 < extra.length; i += 2) {
            try {
                ev.put((String) extra[i], extra[i + 1]);
            } catch (JSONException ignored) {
            }
        }
        emit("turn-end", ev);
    }

    private void emitError(int idx, String message) {
        emit("error", "idx", idx, "message", message);
        emitTurnEnd(idx, true, message);
    }

    // ================================================================ 工具

    private void fillApi(LlmClient.ChatRequest r, SporeSettings s) {
        r.endpoint = s.endpoint.trim();
        r.apiKey = s.apiKey;
        r.model = s.model.trim();
    }

    private String imageDataUrl(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) {
            throw new IOException("截图文件缺失: " + f.getName());
        }
        byte[] buf = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
        }
        return "data:image/jpeg;base64," + Base64.encodeToString(buf, Base64.NO_WRAP);
    }

    private void checkAbort() {
        if (abort) {
            throw new AbortedTurn();
        }
    }

    /** 回合中止（面板停止按钮 / 服务销毁） */
    private static final class AbortedTurn extends RuntimeException {
        AbortedTurn() {
            super("aborted");
        }
    }

    private void emit(String type, Object... kv) {
        emit(type, jo(kv));
    }

    private void emit(String type, JSONObject ev) {
        try {
            ev.put("type", type);
        } catch (JSONException ignored) {
        }
        Listener l = listener;
        if (l != null) {
            main.post(() -> l.onEvent(ev));
        }
    }

    private static JSONObject jo(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                o.put((String) kv[i], kv[i + 1]);
            }
        } catch (JSONException ignored) {
            // key 都是字面量字符串，不会发生
        }
        return o;
    }
}

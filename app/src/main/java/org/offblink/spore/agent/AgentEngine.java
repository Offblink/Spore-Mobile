package org.offblink.spore.agent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.offblink.spore.CrashLog;
import org.offblink.spore.SporeSettings;
import org.offblink.spore.llm.LlmClient;
import org.offblink.spore.tools.SearchChain;
import org.offblink.spore.tools.ToolSchemas;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 两阶段作答引擎——移植桌面 `src/lib/agent.js`（先快再准）：
 * 阶段A 读题初答（五行协议 + <<ok>> 守卫 + 极性自检）→ 异步起名（零调用）→
 * 阶段B 联网核实（工具循环，三道闸检索）→ FIX 覆盖答案。
 * 追问 = 纯文本对话分支（带历史）。
 *
 * <h3>线程与并行模型（第九轮用户拍板：生成中照样能操作，「并行」要看得见）</h3>
 * <ul>
 *   <li>回合跑在 <b>3 线程池</b>上（阻塞式 SSE），事件经 main Handler 回主线程；
 *       {@link Listener#onEvent} 保证在主线程。</li>
 *   <li><b>一会话一回合</b>：同一个会话里不并发（两条流写同一个消息列表会互相踩），
 *       <b>不同会话各跑各的</b>——第一题还在生成时截第二题，两条流并行。</li>
 *   <li>回合用 {@link #curSession()} 取自己绑定的 Session，<b>绝不碰「正在查看的会话」</b>；
 *       所以生成中途切会话/收藏/改名/删除都不会打断在跑的那条流，也不会写串数据。</li>
 *   <li>事件带 {@code sid}（属于哪个会话）：面板只渲染正在看的那个，其余会话的流式增量
 *       直接忽略（列表红点靠回合结束后的 state 刷新）。</li>
 *   <li>列表顺序的键是 {@code Session.updated}，<b>只有内容（消息）变了才抬</b>
 *       （{@link SessionStore#saveActive}）；改名/收藏走 {@link SessionStore#save} 不抬 ——
 *       否则「收藏完/改名完该条就跳到最前」。</li>
 * </ul>
 */
public final class AgentEngine {

    public interface Listener {
        /** 主线程回调；ev 必带 type 字段（协议对齐桌面 emit 的 ev 族） */
        void onEvent(JSONObject ev);
    }

    private final Context app;
    private final ExecutorService exec = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "spore-agent-" + AGENT_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger AGENT_SEQ = new AtomicInteger();

    private final Handler main = new Handler(Looper.getMainLooper());

    /** 正在查看的会话（面板/记录页显示的那个）；切它不打断任何在途回合 */
    private volatile Session session = new Session();
    /** 在途回合：会话 id → 回合上下文。同一会话不并行，不同会话各跑各的 */
    private final Map<String, Turn> turns = new ConcurrentHashMap<>();
    /** 已删除的会话 id：在途回合收尾不许把文件写回来（防「删了又复活」） */
    private final Set<String> deleted = ConcurrentHashMap.newKeySet();
    /**
     * 回合线程绑定的上下文。回合方法（runXxx/verifyPhase/historyMessages/emit…）只在
     * 回合线程上被调用，读它拿「这个回合属于谁」；桥线程/服务线程调的那些（emit session-new、
     * rename…）拿到 null → 回落到「正在查看的会话」。
     */
    private final ThreadLocal<Turn> cur = new ThreadLocal<>();

    private Listener listener;

    /** 一个在途回合的私有状态：自己的会话对象、自己的 LLM 客户端（■ 只掐自己那条流）、自己的中止开关 */
    private static final class Turn {
        final Session session;
        final LlmClient llm = new LlmClient();
        volatile boolean abort;

        Turn(Session session) {
            this.session = session;
        }
    }

    public AgentEngine(Context app) {
        this.app = app.getApplicationContext();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public Session session() {
        return session;
    }

    /** 该会话是否在生成（面板只用它：输入框只在**当前会话**生成中才禁用） */
    public boolean isBusy(String id) {
        return id != null && turns.containsKey(id);
    }

    /** 在生成中的会话 id（面板列表给这些行打「生成中」标） */
    public List<String> runningIds() {
        return new ArrayList<>(turns.keySet());
    }

    /** 会话在跑 → 返回它的内存活对象（记录详情页轮询用，别读会被归一成 done 的旧文件） */
    public Session liveSession(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        Turn t = turns.get(id);
        return t == null ? null : t.session;
    }

    // ================================================================ 开回合

    /**
     * 截图提问：新 user(带图) + answer 占位，跑两阶段。
     * <b>上一题还在生成也照开</b>（用户拍板：并行要能体现）——这条会落到一个新会话里，
     * 与在跑的那条各写各的。
     */
    public void newCaptureTurn(String imagePath, String supplement) {
        Session prev = session;
        Session next = nextCaptureSession(); // 只定归属 + 换视图，不在主线程写盘
        String text = supplement == null ? "" : supplement;
        submit(next, () -> {
            if (prev != next && !prev.messages.isEmpty()) {
                // 旧会话归档（内容落盘、抬 updated → 它排到列表最前，因为**确实有新内容**）；
                // 放工作线程做：主线路是面板开合，不背文件 IO
                SessionStore.saveActive(app, prev);
            }
            runImageTurn(imagePath, text);
        });
    }

    /**
     * 这次截图落在哪个会话：当前会话空着且没在跑 → 直接复用（面板刚开还没问过），否则开新的。
     */
    private Session nextCaptureSession() {
        if (session.messages.isEmpty() && !turns.containsKey(session.id)) {
            return session;
        }
        Session fresh = new Session();
        session = fresh;
        emit("session-new", "title", fresh.title);
        return fresh;
    }

    /** 内容落盘（回合结束 / 新会话归档）：抬 updated，列表按最新活动排前 */
    private void persist(Session cur) {
        if (deleted.contains(cur.id)) {
            return; // 回合期间被删掉了：不许把文件写回来
        }
        SessionStore.saveActive(app, cur);
    }

    /**
     * 记录/会话列表点入：换视图，<b>不动任何时间戳</b>——第九轮拍板「点进去不要置顶」。
     * 点的是在途回合 → 直接换到它的内存活对象（读文件会看到还没更新的旧数据）。
     */
    public boolean loadSession(String id) {
        Session target = id == null || id.isEmpty() ? null : resolve(id);
        if (target == null) {
            return false;
        }
        if (target.id.equals(session.id)) {
            return true;
        }
        session = target;
        emit("session-new", "title", target.title);
        return true;
    }

    /** 找会话对象：正在看的 → 在途回合的（活的）→ 磁盘。三处都只读元数据用途 */
    private Session resolve(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        if (id.equals(session.id)) {
            return session;
        }
        Turn t = turns.get(id);
        if (t != null) {
            return t.session;
        }
        return SessionStore.load(app, id);
    }

    /**
     * 重命名：当前 / 历史 / <b>在途回合</b>都能改（第九轮：生成中不再拦操作）。
     * 只改标题走 save（不抬 updated）→ 列表顺序保持原位。
     */
    public boolean renameSession(String id, String name) {
        Session s = resolve(id);
        if (s == null) {
            return false;
        }
        s.title = name;
        SessionStore.save(app, s);
        if (s.id.equals(session.id)) {
            emit("title", "title", name);
        }
        return true;
    }

    /**
     * 删除会话：先掐它的在途回合（否则回合结束 persist 会把文件又写回来），再删文件。
     * 删的是当前视图 → 换成全新空会话。
     */
    public boolean deleteSession(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        deleted.add(id);
        abortTurn(id);
        SessionStore.delete(app, id);
        if (id.equals(session.id)) {
            session = new Session();
            emit("session-new", "title", session.title);
        }
        return true;
    }

    /** 收藏切换：当前 / 历史 / 在途回合都能点；只改 fav，不抬 updated（列表不跳位） */
    public boolean toggleFav(String id) {
        Session s = resolve(id);
        if (s == null) {
            return false;
        }
        s.fav = !s.fav;
        SessionStore.save(app, s);
        return true;
    }

    /**
     * 移入/移出科目：改 subjectId + <b>抬 updated</b>（handoff §2「改字段 + updated 戳」——
     * 同步游标靠它，不抬则这次移动永远同步不出去）。与改名/收藏不抬 updated 不同，
     * 这是同步契约不是疏漏；副作用是被移动的会话会跳到列表最前。
     * subjectId 空 = 移出（回未分组）。目标科目存在性由 CaptureService 校验。
     */
    public boolean assignSubject(String id, String subjectId) {
        Session s = resolve(id);
        if (s == null) {
            return false;
        }
        String target = (subjectId == null || subjectId.isEmpty()) ? null : subjectId;
        if (java.util.Objects.equals(s.subjectId, target)) {
            return true; // 已在该科目：不抬 updated、不重排（点「当前科目」是 no-op）
        }
        s.subjectId = target;
        SessionStore.saveActive(app, s);
        return true;
    }

    /**
     * 删科目清引用：先清内存、再扫磁盘（{@link SessionStore#clearSubject} 排除活会话——
     * 它们的文件在回合期间是陈旧的，读旧写旧会回滚刚 persist 的消息；活会话这里清完，
     * 下次 persist 自然写回 null）。磁盘只动非活会话。顺序保证任何交错下最终都是 null。
     */
    public void clearSubjectRefs(String subjectId) {
        if (subjectId == null || subjectId.isEmpty()) {
            return;
        }
        Set<String> live = new HashSet<>();
        live.add(session.id);
        if (subjectId.equals(session.subjectId)) {
            session.subjectId = null;
            SessionStore.save(app, session);
        }
        for (Turn t : turns.values()) {
            live.add(t.session.id);
            if (subjectId.equals(t.session.subjectId)) {
                t.session.subjectId = null;
                SessionStore.save(app, t.session);
            }
        }
        SessionStore.clearSubject(app, subjectId, live);
    }

    /**
     * 同步下行回写（SyncEngine.pull → CaptureService.applyFromSync）：
     * 不在内存里的会话直接落盘；<b>正在查看的</b>整个换成下行版本（面板下次事件即刷）；
     * <b>在途回合的会话本轮跳过</b>（回合线程正在 append messages，换列表会互相踩——
     * 返回 false 让同步计数如实少一条；该会话等下轮，服务端行还在游标后面不会丢）。
     *
     * @return 是否套用
     */
    public boolean applyPulled(Session incoming) {
        if (incoming == null || incoming.id == null || incoming.id.isEmpty()) {
            return false;
        }
        if (turns.containsKey(incoming.id)) {
            return false;
        }
        if (incoming.id.equals(session.id)) {
            session = incoming;
            return true;
        }
        SessionStore.saveQuiet(app, incoming);
        return true;
    }

    /**
     * 追问（纯文本）：落到**面板当前会话**上。返回 false = 该会话已有回合在跑
     * （同一会话不并行；别的会话在跑不影响这里）。
     */
    public boolean sendFollowup(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return false;
        }
        Session cur = session;
        return submit(cur, () -> runChatTurn(t));
    }

    /** 「核实一下」按钮：对**当前会话**最后一条回答单独跑阶段B */
    public boolean verifyOnly() {
        Session cur = session;
        return submit(cur, () -> runVerifyOnly());
    }

    /** 停止：只掐**当前会话**那条流（面板 ■）；其它会话的并行回合照跑 */
    public void cancel() {
        abortTurn(session == null ? null : session.id);
    }

    private void abortTurn(String id) {
        if (id == null) {
            return;
        }
        Turn t = turns.get(id);
        if (t != null) {
            t.abort = true;
            t.llm.cancel();
        }
    }

    public void shutdown() {
        for (Turn t : turns.values()) {
            t.abort = true;
            t.llm.cancel();
        }
        exec.shutdownNow();
    }

    /**
     * 提交回合：同一会话已在跑 → 拒绝（返回 false，调用方给提示）；否则绑定上下文入池。
     * 三个线程同时最多三条流；同一会话永远不会同时两条。
     */
    private boolean submit(Session owner, Runnable turn) {
        Turn t = new Turn(owner);
        if (turns.putIfAbsent(owner.id, t) != null) {
            return false;
        }
        exec.execute(() -> {
            cur.set(t);
            try {
                turn.run();
            } catch (RuntimeException e) {
                // 兜底：回合方法内部已各自 try/catch，这里只防漏网
                emitError(-1, String.valueOf(e.getMessage() == null ? e : e.getMessage()));
            } finally {
                cur.remove();
                turns.remove(owner.id, t);
            }
        });
        return true;
    }

    /** 当前线程绑定的会话（回合线程 = 该回合的会话；其它线程 = 正在查看的会话） */
    private Session curSession() {
        Turn t = cur.get();
        return t != null ? t.session : session;
    }

    /** 当前回合的 LLM 客户端：每回合一个 → ■ 停止只 cancel 自己那条流 */
    private LlmClient llm() {
        Turn t = cur.get();
        return t != null ? t.llm : new LlmClient();
    }

    /** 当前回合的中止开关（非回合线程永远 false） */
    private boolean aborted() {
        Turn t = cur.get();
        return t != null && t.abort;
    }

    // ================================================================ 阶段分支

    private void runImageTurn(String imagePath, String supplement) {
        final Session cur = curSession();
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
        cur.messages.add(user);

        Session.Msg ans = new Session.Msg();
        ans.role = "assistant";
        ans.kind = "answer";
        ans.ts = System.currentTimeMillis();
        cur.messages.add(ans);
        int idx = cur.messages.size() - 1;

        cur.status = "answering";
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
            LlmClient.Result resA = llm().streamChat(r);
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
                cur.status = "done";
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
            cur.status = "done";
            emitTurnEnd(idx, false, null);
        } catch (AbortedTurn a) {
            cur.status = "aborted";
            emitTurnEnd(idx, false, null, "aborted", true);
        } catch (Exception e) {
            failTurn(e, idx);
        } catch (Throwable t) {
            // Error（OOM 等）不许无声穿死进程：先落盘再放行（进程死但下次开屏有栈可查）
            CrashLog.write(app, t, "turn");
            throw (t instanceof RuntimeException) ? (RuntimeException) t : new RuntimeException(t);
        }
    }

    private void runChatTurn(String text) {
        final Session cur = curSession();
        SporeSettings s = SporeSettings.load(app);
        if (!s.isConfigured()) {
            emitTurnEnd(-1, true, "未配置端点或 API Key，请先到设置页填写");
            return;
        }
        Session.Msg user = new Session.Msg();
        user.role = "user";
        user.text = text;
        user.ts = System.currentTimeMillis();
        cur.messages.add(user);

        Session.Msg chat = new Session.Msg();
        chat.role = "assistant";
        chat.kind = "chat";
        chat.ts = System.currentTimeMillis();
        cur.messages.add(chat);
        int idx = cur.messages.size() - 1;

        cur.status = "answering";
        emit("chat-start", "idx", idx);
        emit("status", "status", "answering", "text", "回答中…");
        try {
            JSONArray msgs = new JSONArray();
            msgs.put(jo("role", "system", "content", Prompts.SYSTEM));
            JSONArray hist = historyMessages(s);
            for (int i = 0; i < hist.length(); i++) {
                msgs.put(hist.get(i));
            }
            // 第五轮（用户拍板）：检索 = agent 随时可调的工具——追问同样挂工具环，
            // 「核实触发检索」之外，记录页继续提问也能当场联网（也方便测试检索崩溃路径）。
            int maxRounds = Math.max(0, s.maxToolRounds);
            SearchChain.setProxy(s.proxy);
            for (int round = 0; ; round++) {
                checkAbort();
                LlmClient.ChatRequest r = new LlmClient.ChatRequest();
                fillApi(r, s);
                r.messages = msgs;
                r.noThink = s.fastNoThink;
                if (maxRounds > 0) {
                    r.toolsJson = ToolSchemas.toolsJson();
                }
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
                LlmClient.Result res = llm().streamChat(r);
                if (chat.text.isEmpty() && res.content != null) {
                    chat.text = res.content;
                }
                if (maxRounds <= 0 || round >= maxRounds || res.toolCalls.isEmpty()) {
                    break;
                }
                cur.status = "searching";
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
                    chat.tools.add(chip);
                    emit("tool", "idx", idx, "name", t.name, "brief", brief);
                    String out = SearchChain.dispatch(t.name, t.args == null ? "{}" : t.args);
                    msgs.put(jo("role", "tool", "tool_call_id", t.id, "content", out));
                }
                // 工具轮的半截正文是过程稿：清掉，最终轮输出才是回答（panel 的 total 重绘生效）
                if (!chat.text.isEmpty() || !chat.think.isEmpty()) {
                    chat.text = "";
                    chat.think = "";
                    emit("chat-delta", "idx", idx, "text", "", "total", "");
                }
            }
            cur.status = "done";
            emitTurnEnd(idx, false, null);
        } catch (AbortedTurn a) {
            cur.status = "aborted";
            emitTurnEnd(idx, false, null, "aborted", true);
        } catch (Exception e) {
            failTurn(e, idx);
        } catch (Throwable t) {
            // Error（OOM 等）不许无声穿死进程：先落盘再放行（进程死但下次开屏有栈可查）
            CrashLog.write(app, t, "turn");
            throw (t instanceof RuntimeException) ? (RuntimeException) t : new RuntimeException(t);
        }
    }

    private void runVerifyOnly() {
        final Session cur = curSession();
        SporeSettings s = SporeSettings.load(app);
        if (!s.isConfigured()) {
            emitTurnEnd(-1, true, "未配置端点或 API Key，请先到设置页填写");
            return;
        }
        int idx = -1;
        Session.Msg ans = null;
        for (int i = cur.messages.size() - 1; i >= 0; i--) {
            Session.Msg m = cur.messages.get(i);
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
        for (int i = cur.messages.size() - 1; i >= 0; i--) {
            Session.Msg m = cur.messages.get(i);
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
        cur.status = "verifying";
        emit("verify-delta", "idx", idx, "ran", false, "note", "", "pending", false);
        emit("status", "status", "verifying", "text", "核实中…");
        try {
            if (image == null) {
                throw new IOException("截图缺失，无法核实");
            }
            verifyPhase(s, ans, idx, image, extra);
            cur.status = "done";
            emitTurnEnd(idx, false, null);
        } catch (AbortedTurn a) {
            cur.status = "aborted";
            emitTurnEnd(idx, false, null, "aborted", true);
        } catch (Exception e) {
            failTurn(e, idx);
        } catch (Throwable t) {
            // Error（OOM 等）不许无声穿死进程：先落盘再放行（进程死但下次开屏有栈可查）
            CrashLog.write(app, t, "turn");
            throw (t instanceof RuntimeException) ? (RuntimeException) t : new RuntimeException(t);
        }
    }

    // ================================================================ 阶段B

    private void verifyPhase(SporeSettings s, Session.Msg ans, int idx,
                             String image, String extra) throws Exception {
        final Session cur = curSession();
        int maxRounds = Math.max(0, s.maxToolRounds);
        if (maxRounds <= 0) {
            return; // 桌面同款：0 轮 = 不核实直接收尾
        }
        SearchChain.setProxy(s.proxy);
        cur.status = "verifying";
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
            LlmClient.Result res = llm().streamChat(r);
            if (res.toolCalls.isEmpty()) {
                verify = Phases.parsePhaseB(res.content == null ? "" : res.content);
                break;
            }
            cur.status = "searching";
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
            LlmClient.Result fin = llm().streamChat(r);
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
        final Session cur = curSession();
        int lastImageIdx = -1;
        for (int i = 0; i < cur.messages.size(); i++) {
            Session.Msg m = cur.messages.get(i);
            if ("user".equals(m.role) && m.hasImage) {
                lastImageIdx = i;
            }
        }
        String imgUrl = null;
        if (lastImageIdx >= 0) {
            try {
                imgUrl = imageDataUrl(cur.messages.get(lastImageIdx).imagePath);
            } catch (Exception e) {
                imgUrl = null; // 图丢了就降级成文本占位
            }
        }
        JSONArray msgs = new JSONArray();
        for (int i = 0; i < cur.messages.size(); i++) {
            Session.Msg m = cur.messages.get(i);
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
        curSession().title = title;
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
            curSession().status = "aborted";
            emitTurnEnd(idx, false, null, "aborted", true);
        } else {
            curSession().status = "error";
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
        persist(curSession()); // 回合结束即落盘（done/aborted/error 都走这里）
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
        String url = SessionStore.imageDataUrl(path);
        if (url.isEmpty()) {
            throw new IOException("截图文件缺失: " + new File(path).getName());
        }
        return url;
    }

    private void checkAbort() {
        if (aborted()) {
            throw new AbortedTurn();
        }
    }

    /** 回合中止（面板停止按钮 / 服务销毁 / 删除该会话） */
    private static final class AbortedTurn extends RuntimeException {
        AbortedTurn() {
            super("aborted");
        }
    }

    private void emit(String type, Object... kv) {
        emit(type, jo(kv));
    }

    private void emit(String type, JSONObject ev) {
        Session owner = curSession();
        try {
            ev.put("type", type);
            // sid = 这条事件属于哪个会话：面板只渲染**正在看的**那个，别的会话的流式增量
            // 直接忽略（否则切了会话会把别的会话的字打进当前屏）
            ev.put("sid", owner == null ? "" : owner.id);
        } catch (JSONException ignored) {
        }
        Listener l = listener;
        if (l != null) {
            // 单个事件的渲染失败只记日志，不许拖垮悬浮窗（悬浮窗进程一死，球+面板全消失）。
            // 这是兜底不是根治：线程侧根因已由 LlmClient.wireStr 修掉，此处留证据链。
            main.post(() -> {
                try {
                    l.onEvent(ev);
                } catch (Throwable t) {
                    // Throwable 也接：Error 在主线程直接杀进程，悬浮窗不能死（第五轮）；落盘留证
                    CrashLog.write(app, t, "onEvent " + ev.optString("type"));
                }
            });
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

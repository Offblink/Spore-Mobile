package org.offblink.spore.agent;

import androidx.annotation.Keep;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话模型 ↔ JSON 的唯一实现（Gson）。{@link SessionStore} 的落盘、同步上行与 web bridge
 * 都从这里过一道，org.json 留在协议帧/SSE 那侧（逐事件取字段，绑不上 DTO）。
 *
 * <p>为什么是 DTO 而不是给 {@link Session}/{@link Session.Msg} 直接挂注解：模型里有
 * <b>不该落盘的 UI 瞬态</b>（{@code verifyStreamNote}、{@code thinkOpen}），DTO 把它们天然
 * 挡在序列化边界外；模型本身也不必背序列化注解。
 *
 * <p><b>字段名就是四端共享的 wire 契约</b>（kit design/03）：DTO 改字段 = 改协议，
 * 必须同时过 {@code SessionStoreTest} 与 SyncEngine 上行形状测试。
 */
final class SessionJson {

    /**
     * 两个配置都是契约，不是口味：
     * <ul>
     *   <li>{@code disableHtmlEscaping}：Gson 默认是 HTML 安全模式，把 {@code < > & = '}
     *       写成 {@code \u003c} 等——回答里全是 markdown 标签，会让落盘文件体积与 org.json
     *       输出不再同形。关掉才与旧实现逐字节对齐。</li>
     *   <li>{@code Strictness.LENIENT}：org.json 是宽容解析器（单引号/无引号键/尾逗号都吃），
     *       读端保留同等宽容度——老文件和手工编辑不会变成「以前能读、现在读不了」而被跳过。</li>
     * </ul>
     * Gson 实例线程安全：引擎线程写、bridge 线程读共用同一个。
     */
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setStrictness(Strictness.LENIENT)
            .create();

    private SessionJson() {
    }

    // ---------------------------------------------------------------- 编码

    static String encode(Session s) {
        SessionDto d = new SessionDto();
        d.id = s.id;
        d.created = s.created;
        d.updated = s.updated;
        d.touched = s.touched;
        d.fav = s.fav;
        // null → 键缺席（Gson 默认不写 null）：与旧实现 put(key, null) 删键同形，
        // 「键缺席 = 未分组」的兼容约定原样保留。
        d.subjectId = s.subjectId;
        d.title = s.title;
        d.status = s.status;
        List<MsgDto> msgs = new ArrayList<>();
        // 快照拷贝：messages 只 append，进场取定长再逐个 get——回合线程正在 append 也不会
        // 撞到失效下标（旧实现对并发扩容有专门注释，口径不变）。
        int n = s.messages.size();
        for (int i = 0; i < n; i++) {
            msgs.add(msgOf(s.messages.get(i)));
        }
        d.messages = msgs;
        return GSON.toJson(d);
    }

    private static MsgDto msgOf(Session.Msg m) {
        MsgDto d = new MsgDto();
        d.role = m.role;                        // null → 键缺席（旧实现 put(null) 也是删键）
        d.kind = m.kind == null ? "" : m.kind;  // 旧实现显式把 null kind 写成 ""
        d.text = m.text;
        d.think = m.think;
        d.no = m.no;
        d.title = m.title;
        d.ans = m.ans;
        d.why = m.why;
        d.hasImage = m.hasImage;
        d.imagePath = m.imagePath;              // null → 键缺席
        d.ts = m.ts;
        d.verifyRan = m.verifyRan;
        d.verifySkipped = m.verifySkipped;
        d.verifyPending = m.verifyPending;
        d.verifyVerdict = m.verifyVerdict;
        d.verifyNote = m.verifyNote;
        d.verifyThink = m.verifyThink;
        d.tools = new ArrayList<>(m.tools);     // 恒写数组（空 → []），与旧 JSONArray 同形
        return d;
    }

    // ---------------------------------------------------------------- 解码

    /**
     * 解码一个会话。坏 JSON 抛 {@link JsonSyntaxException}（非受检），
     * 由调用方决定「跳过该文件」还是「报错」——{@link SessionStore} 的口径是跳过。
     */
    static Session decode(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new JsonSyntaxException("empty session json");
        }
        JsonElement root = JsonParser.parseString(raw);
        if (!root.isJsonObject()) {
            throw new JsonSyntaxException("session json is not an object");
        }
        JsonObject obj = root.getAsJsonObject();
        JsonElement msgs = obj.get("messages");
        if (msgs != null && msgs.isJsonArray()) {
            // 老口径：messages 里的非对象行单条跳过，不拖垮整会话（GUI session.py 同口径）。
            // 反射绑定遇到非对象元素会整档失败，所以先过一道筛，再交给 Gson。
            JsonArray kept = new JsonArray();
            for (JsonElement el : msgs.getAsJsonArray()) {
                if (el.isJsonObject()) {
                    kept.add(el);
                }
            }
            obj.add("messages", kept);
        }
        SessionDto d = GSON.fromJson(obj, SessionDto.class);
        if (d == null) {
            throw new JsonSyntaxException("session json is null");
        }
        return modelOf(d);
    }

    private static Session modelOf(SessionDto d) {
        Session s = new Session();
        if (d.id != null) {
            s.id = d.id;
        }
        if (d.created != null) {
            s.created = d.created;
        }
        if (d.updated != null) {
            s.updated = d.updated;
        }
        // 老 JSON 无 touched → 回落 updated（同步上行游标兼容，SessionStoreTest 钉住）
        s.touched = d.touched != null ? d.touched : s.updated;
        s.fav = d.fav;
        // 缺键 / JSON null / 空串 三态在协议里都算「未分组」：一次收敛，别让 "" 漏到同步层
        s.subjectId = d.subjectId == null || d.subjectId.isEmpty() ? null : d.subjectId;
        if (d.title != null) {
            s.title = d.title;
        }
        // 进行中态归一（answering → done）由 SessionStore.fromJson 统一做，这里只做原样搬运
        s.status = d.status == null ? "" : d.status;
        if (d.messages != null) {
            for (MsgDto md : d.messages) {
                if (md != null) {
                    s.messages.add(msgOf(md));
                }
            }
        }
        return s;
    }

    private static Session.Msg msgOf(MsgDto d) {
        Session.Msg m = new Session.Msg();
        // 缺键一律回落 Session.Msg 的构造默认（role=user、其余空串/0/false）——
        // 对齐旧 optString(key, 默认值) 的逐字段口径。
        m.role = d.role == null ? "user" : d.role;
        m.kind = d.kind == null ? "" : d.kind;
        m.text = d.text == null ? "" : d.text;
        m.think = d.think == null ? "" : d.think;
        m.no = d.no == null ? "" : d.no;
        m.title = d.title == null ? "" : d.title;
        m.ans = d.ans == null ? "" : d.ans;
        m.why = d.why == null ? "" : d.why;
        m.hasImage = d.hasImage;
        m.imagePath = d.imagePath == null ? "" : d.imagePath;
        m.ts = d.ts;
        m.verifyRan = d.verifyRan;
        m.verifySkipped = d.verifySkipped;
        m.verifyPending = d.verifyPending;
        m.verifyVerdict = d.verifyVerdict == null ? "" : d.verifyVerdict;
        m.verifyNote = d.verifyNote == null ? "" : d.verifyNote;
        m.verifyThink = d.verifyThink == null ? "" : d.verifyThink;
        if (d.tools != null) {
            for (String t : d.tools) {
                if (t != null) {
                    m.tools.add(t);
                }
            }
        }
        return m;
    }

    // ---------------------------------------------------------------- DTO（wire 形状）
    //
    // 字段名 = JSON 键名，顺序 = 落盘顺序（与旧 org.json 插入序一致，便于 diff 与人工比对）。
    // @Keep：R8 当前关着（app/build.gradle optimization.enable=false），反射绑定不受影响；
    // 哪天开 R8，这里就是必须留下的类，否则字段被裁/被改名后 Gson 静默产出空对象。

    /** 会话头 + 消息数组；UI 瞬态（thinkOpen 等）不在 DTO 里 = 不落盘 */
    @Keep
    static final class SessionDto {
        String id;
        Long created;
        Long updated;
        Long touched;
        @JsonAdapter(FlexBool.class)
        boolean fav;
        String subjectId;   // null → 键缺席（未分组）
        String title;
        String status;
        List<MsgDto> messages;
    }

    /** 一条消息；字段名与 kit design/03 的 messages[] 逐键一致 */
    @Keep
    static final class MsgDto {
        String role;
        String kind;
        String text;
        String think;
        String no;
        String title;
        String ans;
        String why;
        @JsonAdapter(FlexBool.class)
        boolean hasImage;
        String imagePath;
        long ts;
        @JsonAdapter(FlexBool.class)
        boolean verifyRan;
        @JsonAdapter(FlexBool.class)
        boolean verifySkipped;
        @JsonAdapter(FlexBool.class)
        boolean verifyPending;
        String verifyVerdict;
        String verifyNote;
        String verifyThink;
        List<String> tools;
    }

    /**
     * 宽容布尔：服务端（Jackson）把 {@code fav} 发成数字 {@code 0/1}，桌面可能发字符串
     * {@code "true"}——旧 {@code optBoolean} 全部能吃下（数字一律按 false），Gson 默认
     * 遇到 {@code fav:1} 直接抛 {@code Expected BOOLEAN but was NUMBER}。
     * 这里把 boolean / 0|1 / "true"|"false" 三种形态都收成 boolean，缺键与 null 落 false
     * （与 optBoolean(key, false) 的默认值口径一致）。写端永远输出 boolean。
     */
    @Keep
    static final class FlexBool extends TypeAdapter<Boolean> {
        @Override
        public void write(JsonWriter out, Boolean value) throws IOException {
            out.value(value != null && value);
        }

        @Override
        public Boolean read(JsonReader in) throws IOException {
            JsonToken t = in.peek();
            switch (t) {
                case BOOLEAN:
                    return in.nextBoolean();
                case NUMBER:
                    return in.nextDouble() != 0;
                case STRING:
                    return Boolean.parseBoolean(in.nextString());
                case NULL:
                    in.nextNull();
                    return false;
                default:
                    throw new JsonSyntaxException("boolean 字段只认 boolean/0|1/\"true\": " + t);
            }
        }
    }
}

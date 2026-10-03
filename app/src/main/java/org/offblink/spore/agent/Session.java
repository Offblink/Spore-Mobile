package org.offblink.spore.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 移动端会话模型（桌面 spore.sess.&lt;id&gt; 的移动版）。
 * 持久化 = {@link SessionStore}（一会话一 JSON，记录页数据源）。
 * 标题契约照搬：占位「新会话」永不含日期；起名 = 题号 + 大意（零模型调用）。
 */
public final class Session {

    /** 文件名即 ID（时间戳格式，字典序 = 时间序）；记录页/详情页按它取 */
    public String id = newId();
    public long created = System.currentTimeMillis();
    public long updated = created;
    /**
     * 最后一次落盘变更（内容与元数据都算）。同步上行游标用它，不复用 {@code updated}——
     * {@code updated} 只抬内容（第九轮拍板：改名/收藏不许把该条顶到列表最前），
     * 而同步必须看见改名/收藏 → 元数据写抬 {@code touched}，列表排序照旧只看 {@code updated}。
     */
    public long touched = created;
    /** 收藏（桌面 fav 字段；记录页星标切换） */
    public boolean fav = false;
    /** 归属科目（subjects.json 的 id）；null = 未分组（kit design/03 §三，老 JSON 无此字段 → null） */
    public String subjectId = null;

    public String title = "新会话";
    /** answering / verifying / searching / done / error / aborted / "" */
    public String status = "";
    public final List<Msg> messages = new ArrayList<>();

    public static String newId() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                "yyyyMMdd-HHmmssSSS", java.util.Locale.US);
        return f.format(new java.util.Date());
    }

    public static final class Msg {
        /** user | assistant */
        public String role;
        /** assistant 消息：answer（两阶段作答）| chat（追问） */
        public String kind;
        /** user 文本（补充/追问）或 chat 回答正文 */
        public String text = "";
        /** 模型思考（阶段A 正文前的 reasoning） */
        public String think = "";
        // —— 两阶段作答字段（answer）——
        public String no = "";
        public String title = "";
        public String ans = "";
        public String why = "";
        // —— 截图（user）——
        public boolean hasImage;
        public String imagePath;
        public long ts;
        // —— 核实状态（桌面 answer.verify 拍平）——
        public boolean verifyRan;
        public boolean verifySkipped;
        public boolean verifyPending;
        public String verifyVerdict = "";
        public String verifyNote = "";
        public String verifyThink = "";
        /** 核实流式进行中的 NOTE 累积（done 前的展示用，UI 瞬态） */
        public String verifyStreamNote = "";
        /** 阶段B 检索动作的小票（「检索 xxx」），展示在回答行里 */
        public final List<String> tools = new ArrayList<>();
        // —— UI 瞬态 ——
        public boolean thinkOpen;
    }
}

package org.offblink.spore.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 移动端会话模型（桌面 spore.sess.&lt;id&gt; 的内存版；持久化在 handoff §9 顺序的第⑤块接 Room）。
 * 标题契约照搬：占位「新会话」永不含日期；起名 = 题号 + 大意（零模型调用）。
 */
public final class Session {

    public String title = "新会话";
    /** answering / verifying / searching / done / error / aborted / "" */
    public String status = "";
    public final List<Msg> messages = new ArrayList<>();

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

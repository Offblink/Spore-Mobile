package org.offblink.spore.agent;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阶段A/B 协议解析 + 初答极性自检 + 起名——逐语义移植桌面 `src/lib/agent.js`。
 * 权威契约：桌面 `tests/answer.test.mjs`（Java 侧镜像测试见 `PhasesTest`）。
 */
public final class Phases {

    private Phases() {
    }

    /** 阶段A 五行协议的解析结果 */
    public static final class PhaseA {
        public String no = "";
        public String title = "";
        public String ans = "";
        public String why = "";
    }

    /** 阶段B 三行协议的解析结果；verdict 为空串 = 模型没守格式 */
    public static final class PhaseB {
        public String verdict = "";
        public String note = "";
        public String ans = "";
    }

    private static final Pattern SELF_CERTAIN = Pattern.compile("<<ok>>", Pattern.CASE_INSENSITIVE);
    private static final Pattern POL_FALSE =
            Pattern.compile("(错误|不对|不正确|不成立|不属实|有误|选错|并非|不满足)");
    private static final Pattern POL_TRUE =
            Pattern.compile("(正确|无误|成立|属实|选对|满足)");
    private static final Pattern ALONE_FALSE = Pattern.compile("^(错|否)$");
    private static final Pattern ALONE_TRUE = Pattern.compile("^(对|是)$");
    private static final Pattern ANS_PAREN =
            Pattern.compile("（([^）]{1,8})）|\\(([^)]{1,8})\\)");
    private static final Pattern TITLE_JUNK =
            Pattern.compile("[\"“”'‘’《》\\[\\]{}（）()<>：:；;，,。.!！？?、\\s]+");
    private static final Pattern GUARD_LINES =
            Pattern.compile("<<ok>>|<<check>>|^CERT:.*$",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern FIELD_LINES =
            Pattern.compile("^(NO|TITLE|ANS|WHY|CERT):.*$", Pattern.MULTILINE);
    private static final Pattern FIELD_LINES_B =
            Pattern.compile("^(VERDICT|NOTE|ANS):.*$", Pattern.MULTILINE);
    private static final Pattern NOTE_ANS_LINES =
            Pattern.compile("^\\s*ANS:.*$", Pattern.MULTILINE);
    private static final Pattern VERDICT =
            Pattern.compile("^VERDICT:\\s*(FIX|OK)\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern B_ANS =
            Pattern.compile("^ANS:\\s*(.*)$",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern B_NOTE =
            Pattern.compile("^NOTE:\\s*([\\s\\S]*)$",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern NOT_WORD = Pattern.compile("[^\\dA-Za-z]");
    private static final Pattern WHY_SPLIT =
            Pattern.compile("[，,。；;！？!、\\s]+");

    // ---------------------------------------------------------------- 解析

    /** 初答里带 <<ok>> 就跳过联网核实（CERT 行或正文里出现都算） */
    public static boolean isSelfCertain(String raw) {
        return raw != null && SELF_CERTAIN.matcher(raw).find();
    }

    private static String grab(String raw, String key) {
        Matcher m = Pattern.compile("^" + key + ":\\s*(.*)$", Pattern.MULTILINE).matcher(raw);
        return m.find() ? m.group(1).trim() : "";
    }

    public static PhaseA parsePhaseA(String raw) {
        String src = raw == null ? "" : raw;
        String no = grab(src, "NO");
        String title = grab(src, "TITLE");
        String ans = grab(src, "ANS");
        String why = grab(src, "WHY");
        if (ans.isEmpty()) {
            // 模型没写 ANS 行（或写成别的标签/空行）：剥掉已知字段行，剩下整段当答案
            String leftover = FIELD_LINES.matcher(src).replaceAll("").trim();
            if (!leftover.isEmpty()) {
                ans = leftover;
            } else if (why.isEmpty() && !no.isEmpty()) {
                ans = no;
                no = "";
            }
            // 只写了 WHY：正文即答案，不能让答案栏空着（展示都靠它）
            if (ans.isEmpty() && !why.isEmpty()) {
                ans = why;
                why = "";
            }
        }
        ans = stripGuard(ans);
        why = stripGuard(why);
        no = WS.matcher(no).replaceAll("");
        if ("无".equals(no) || "none".equalsIgnoreCase(no) || "-".equals(no)) {
            no = "";
        }
        title = sanitizeTitle(title);
        if ("无".equals(title) || "none".equalsIgnoreCase(title) || "-".equals(title)) {
            title = "";
        }
        PhaseA r = new PhaseA();
        r.no = no;
        r.title = title;
        r.ans = ans.trim();
        r.why = why.trim();
        return r;
    }

    private static String stripGuard(String t) {
        String s = t == null ? "" : t;
        return GUARD_LINES.matcher(s).replaceAll("").trim();
    }

    public static PhaseB parsePhaseB(String raw) {
        String src = raw == null ? "" : raw;
        PhaseB r = new PhaseB();
        Matcher v = VERDICT.matcher(src);
        if (v.find()) {
            r.verdict = v.group(1).toUpperCase(Locale.ROOT);
        }
        // FIX 时模型会把修正后的答案写在 ANS 行：NOTE 的 [\s\S]* 会吃遍文末，所以单独再抓一次
        Matcher a = B_ANS.matcher(src);
        if (a.find()) {
            r.ans = a.group(1).trim();
        }
        Matcher n = B_NOTE.matcher(src);
        String note = n.find() ? n.group(1).trim() : "";
        if (note.isEmpty()) {
            note = FIELD_LINES_B.matcher(src).replaceAll("").trim();
        }
        // 模型把 ANS 排在 NOTE 后面时会混进说明
        note = NOTE_ANS_LINES.matcher(note).replaceAll("").trim();
        if (note.isEmpty()) {
            note = "（模型没给出核实说明，建议自己再看一眼来源）";
        }
        r.note = note;
        return r;
    }

    // ---------------------------------------------------------------- 初答自检（极性）

    /** 一句话里的结论极性：false=判错/不成立，true=判对/成立；看不出极性回 null */
    private static Boolean polarityOf(String seg) {
        String s = seg == null ? "" : seg.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (POL_FALSE.matcher(s).find()) {
            return Boolean.FALSE;
        }
        if (ALONE_FALSE.matcher(s).find()) {
            return Boolean.FALSE;
        }
        if (POL_TRUE.matcher(s).find()) {
            return Boolean.TRUE;
        }
        if (ALONE_TRUE.matcher(s).find()) {
            return Boolean.TRUE;
        }
        return null;
    }

    /** 解析的极性：从最后一个分句往前找，第一个带极性词的分句说了算（「故该说法错误」都在句尾） */
    private static Boolean polarityOfWhy(String why) {
        String[] parts = WHY_SPLIT.split(why == null ? "" : why);
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].isEmpty()) {
                continue;
            }
            Boolean p = polarityOf(parts[i]);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    /** 答案行的极性：判断题是「A（对）/ B（错）」；括号里是数值/字母（普通选择题）就不判 */
    private static Boolean polarityOfAns(String ans) {
        String text = ans == null ? "" : ans.trim();
        Matcher m = ANS_PAREN.matcher(text);
        String key = text;
        if (m.find()) {
            String g1 = m.group(1);
            String g2 = m.group(2);
            key = g1 != null ? g1 : (g2 != null ? g2 : text);
        }
        return polarityOf(key);
    }

    /**
     * 答案行与解析结论互相打架：`第17题 A（对）` + `…故该说法错误`。
     * 调用方据此**不许**走 `<<ok>>` 跳过核实的捷径 —— 否则用户看到的就是自相矛盾的答案。
     * 极性判定是启发式，误伤的代价只是「多跑一次核实」，但漏判会把矛盾答案端给用户。
     */
    public static boolean contradicts(String ans, String why) {
        Boolean a = polarityOfAns(ans);
        Boolean w = polarityOfWhy(why);
        return a != null && w != null && !a.equals(w);
    }

    // ---------------------------------------------------------------- 展示与起名

    /** 流式阶段展示用：把 NO:/ANS:/WHY: 的字段拍成人话 */
    public static String formatAnswerPreview(String no, String ans, String why) {
        String head = (no == null || no.isEmpty())
                ? "" : "第" + NOT_WORD.matcher(no).replaceAll("") + "题";
        String parts = joinNonEmpty(" ", head, ans);
        return joinNonEmpty("\n", parts, why);
    }

    /**
     * 题号头（原生面板用）：把 `no` 拍成「第N题」，不掺正文。
     * 与 {@link #formatAnswerPreview} 分开是为了 markdown 渲染——头若与正文同串拼接，
     * 「第3题 ## 标题」会把行首块元素（ATX 标题等）顶掉，渲染不出来。
     */
    public static String answerHead(String no) {
        return (no == null || no.isEmpty())
                ? "" : "第" + NOT_WORD.matcher(no).replaceAll("") + "题";
    }

    /** 正文（原生面板用）：ANS + WHY 各自成段（空行分隔，markdown 段落语义） */
    public static String answerBody(String ans, String why) {
        return joinNonEmpty("\n\n", ans, why);
    }

    /** 原生面板整体：头单独一段 + 正文（交给 markdown 渲染前先做分隔符归一化） */
    public static String answerMarkdown(String no, String ans, String why) {
        String head = answerHead(no);
        String body = answerBody(ans, why);
        return head.isEmpty() ? body : joinNonEmpty("\n\n", head, body);
    }

    /** 桌面 sanitizeTitle：去标点取前 20 字（标题粒度） */
    public static String sanitizeTitle(String text) {
        String s = text == null ? "" : text;
        String cleaned = TITLE_JUNK.matcher(s).replaceAll(" ").trim();
        if (cleaned.length() > 20) {
            cleaned = cleaned.substring(0, 20);
        }
        return cleaned;
    }

    /**
     * 标题 = 题号（加分项）+ 题目大意（阶段A 顺带吐的 TITLE 行）。
     * 模型失效（TITLE 缺失/没守格式）→ 回退答案前 14 个字；再不济「截图问答」。零额外模型调用。
     */
    public static String namingTitle(String no, String title, String ans, String why) {
        String n = no == null ? "" : WS.matcher(no).replaceAll("");
        String head = (!n.isEmpty() && !"无".equals(n))
                ? "第" + NOT_WORD.matcher(n).replaceAll("") + "题" : "";
        String gist = sanitizeTitle(title);
        if (gist.isEmpty()) {
            gist = sanitizeTitle(ans);
        }
        if (gist.isEmpty()) {
            gist = sanitizeTitle(why);
        }
        String tail = gist.isEmpty() ? "截图问答" : gist;
        tail = tail.substring(0, Math.min(14, tail.length()));
        return joinNonEmpty(" ", head, tail);
    }

    private static String joinNonEmpty(String sep, String a, String b) {
        StringBuilder sb = new StringBuilder();
        if (a != null && !a.isEmpty()) {
            sb.append(a);
        }
        if (b != null && !b.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(b);
        }
        return sb.toString();
    }
}

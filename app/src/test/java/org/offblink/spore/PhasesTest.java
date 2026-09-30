package org.offblink.spore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.offblink.spore.agent.Phases;

/**
 * 桌面 `tests/answer.test.mjs` 的 Java 镜像——两阶段协议语义的回归钉。
 * 桌面改契约时这里同步改；两边断言必须一致（handoff §2：移动端重写后语义要对得上）。
 */
public class PhasesTest {

    private static final String WHY_BAD =
            "制定软件质量计划属于SQA活动中的计划与实施范畴，故该说法错误。";

    @Test
    public void 答案行与解析结论打架时检出() {
        assertTrue(Phases.contradicts("A（对）", WHY_BAD));
        assertTrue(Phases.contradicts("A（对）", "A 不对，应选 B。"));
        assertTrue(Phases.contradicts("错", "该说法成立。"));
    }

    @Test
    public void 答案与解析一致时不检出() {
        assertFalse(Phases.contradicts("B（错）", WHY_BAD));
        assertFalse(Phases.contradicts("A（对）", "说法成立，选 A。"));
        assertFalse(Phases.contradicts("对", "定义如此，没有例外。"));
    }

    @Test
    public void 括号里是数值或字母时绝不误伤() {
        assertFalse(Phases.contradicts("B（-1）", "求导得 -1。"));
        assertFalse(Phases.contradicts("42", "两边同乘 42 再移项。"));
        assertFalse(Phases.contradicts("B", "应选 B。"));
        assertFalse(Phases.contradicts("", ""));
    }

    @Test
    public void 解析里读不出结论极性时不判() {
        assertFalse(Phases.contradicts("A（对）", "应选 B"));
        assertFalse(Phases.contradicts("A（对）", ""));
    }

    @Test
    public void 阶段B_FIX时ANS单独解析不混进NOTE() {
        Phases.PhaseB v = Phases.parsePhaseB(
                "VERDICT: FIX\nANS: B（错）\nNOTE: 教材：SQA 含计划活动，原说法错误（来源：软件工程教材）。");
        assertEquals("FIX", v.verdict);
        assertEquals("B（错）", v.ans);
        assertFalse(v.note.contains("ANS:"));
    }

    @Test
    public void 阶段B_OK与缺行的ANS为空或原样() {
        assertEquals("无", Phases.parsePhaseB("VERDICT: OK\nANS: 无\nNOTE: 与初答一致。").ans);
        assertEquals("", Phases.parsePhaseB("VERDICT: OK\nNOTE: 与初答一致。").ans);
        // 模型把 ANS 排到 NOTE 后面：NOTE 的 [\s\S]* 会把它吞掉，ans 仍要拿到、note 里不能留
        Phases.PhaseB v = Phases.parsePhaseB("VERDICT: FIX\nNOTE: 原说法错误。\nANS: B（错）");
        assertEquals("B（错）", v.ans);
        assertFalse(v.note.contains("ANS:"));
    }

    @Test
    public void 阶段A五行协议解析且守卫标记不漏进正文() {
        String raw = "NO: 17\n"
                + "TITLE: SQA活动范围\n"
                + "WHY: 制定软件质量计划属于SQA活动，故说法错误。\n"
                + "ANS: B（错）\n"
                + "CERT: <<check>>";
        Phases.PhaseA p = Phases.parsePhaseA(raw);
        assertEquals("17", p.no);
        assertEquals("B（错）", p.ans);
        assertTrue(p.why.contains("说法错误"));
        assertFalse(Phases.isSelfCertain(raw));
        assertTrue(Phases.isSelfCertain(raw.replace("<<check>>", "<<ok>>")));
    }
}

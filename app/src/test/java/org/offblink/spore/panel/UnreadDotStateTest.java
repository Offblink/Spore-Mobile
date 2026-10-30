package org.offblink.spore.panel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.offblink.spore.agent.SessionStore;

import java.util.Arrays;
import java.util.List;

/**
 * 未读红点状态机契约（web panel.js renderUnread 的 JVM 镜像）。
 *
 * <p>口径（2026-10-25 重写：逐会话已读水位）：点亮 = 存在「非当前会话」、比首帧基线新、
 * 且比它自己已读水位新的会话；水位只在**点开会话**时抬高该条 —— 打开列表不清、
 * 回合结束抬当前会话 updated 不清、点开一条不连带清其它未读。
 */
public class UnreadDotStateTest {

    private static SessionStore.Meta m(String id, long updated) {
        return new SessionStore.Meta(id, "t" + id, updated, updated, false, null, "done", 0);
    }

    private static List<SessionStore.Meta> all(SessionStore.Meta... rows) {
        return Arrays.asList(rows);
    }

    // ---------------------------------------------------------------- 整体级（evaluate）

    /** 有比当前会话更新的其它会话 → 点亮 */
    @Test
    public void lightsWhenOtherSessionIsNewer() {
        UnreadDotState st = new UnreadDotState();
        assertTrue(st.evaluate("A", 1000, all(m("A", 1000), m("B", 2000))));
    }

    /** 当前会话自己就是最新 → 不许必亮 */
    @Test
    public void staysDarkWhenCurrentSessionIsNewest() {
        UnreadDotState st = new UnreadDotState();
        assertFalse(st.evaluate("A", 3000, all(m("A", 3000), m("B", 2000))));
    }

    /**
     * 回合结束把当前会话 updated 抬到全库最新，红点不许无读自灭。
     * 旧版靠「水位不动」实现；新版当前会话本就不参与判定，天然成立。
     */
    @Test
    public void turnEndTimestampBumpOfCurrentDoesNotClearUnread() {
        UnreadDotState st = new UnreadDotState();
        assertTrue(st.evaluate("A", 1000, all(m("A", 1000), m("B", 2000))));
        // 回合结束：A.updated 3000（== 全库最新），随后的刷新不许把点打灭
        assertTrue(st.evaluate("A", 3000, all(m("A", 3000), m("B", 2000))));
    }

    /** 打开列表不是已读：状态机没有「开列表」入口，任何次数的刷新都必须保持点亮 */
    @Test
    public void listRefreshAloneNeverClears() {
        UnreadDotState st = new UnreadDotState();
        for (int i = 0; i < 5; i++) {
            assertTrue(st.evaluate("A", 1000, all(m("A", 1000), m("B", 2000))));
        }
    }

    /** 点开更新的那条 → 它转为当前会话、整块转暗 */
    @Test
    public void openingTheNewerSessionClearsDot() {
        UnreadDotState st = new UnreadDotState();
        assertTrue(st.evaluate("A", 1000, all(m("A", 1000), m("B", 2000))));
        st.onSessionOpened("B", 2000); // 用户点开 B
        assertFalse(st.evaluate("B", 2000, all(m("A", 1000), m("B", 2000))));
    }

    /** 点开的是一条更旧的会话 → 更新的那条仍未读，红点保持 */
    @Test
    public void openingOlderSessionKeepsUnread() {
        UnreadDotState st = new UnreadDotState();
        assertTrue(st.evaluate("A", 1000, all(m("A", 1000), m("B", 2000), m("C", 3000))));
        st.onSessionOpened("B", 2000); // 用户点开 B（不是最新的那条）
        assertTrue(st.evaluate("B", 2000, all(m("A", 1000), m("B", 2000), m("C", 3000))));
    }

    /** 熄灭后来了新的会话 → 红点要能再亮（水位只到已读，不封死检测） */
    @Test
    public void newSessionAfterClearingLightsAgain() {
        UnreadDotState st = new UnreadDotState();
        st.onSessionOpened("B", 2000);
        assertFalse(st.evaluate("B", 2000, all(m("B", 2000))));
        assertTrue(st.evaluate("B", 2000, all(m("B", 2000), m("D", 4000))));
    }

    /** 边界：updated 与已读水位相等不算更新 */
    @Test
    public void equalTimestampDoesNotLight() {
        UnreadDotState st = new UnreadDotState();
        st.onSessionOpened("B", 2000);
        assertFalse(st.evaluate("A", 1000, all(m("A", 1000), m("B", 2000))));
    }

    /**
     * 切换会话时「刚在看的那个」也一并算已读：A 看着 A 的回合结束（updated 抬到 3000），
     * 然后点开 B —— A 不许因为自己时间戳最新而回头点亮红点。
     */
    @Test
    public void previousSessionCountsAsReadWhenSwitching() {
        UnreadDotState st = new UnreadDotState();
        assertTrue(st.evaluate("A", 1000, all(m("A", 1000), m("B", 2000)))); // B 未读
        assertTrue(st.evaluate("A", 3000, all(m("A", 3000), m("B", 2000)))); // A 回合结束，点仍在
        st.onSessionOpened("A", 3000); // 调用方先抬「刚在看的 A」
        st.onSessionOpened("B", 2000); // 再抬「打开的 B」
        assertFalse(st.evaluate("B", 2000, all(m("A", 3000), m("B", 2000))));
    }

    // ---------------------------------------------------------------- 回归：读一条不灭一片（2026-10-25）

    /**
     * 核心回归：点开最新那条未读，其它未读会话的点<b>不许</b>跟着灭。
     * 旧版单一全局水位会把水位抬到 3000，让从未点开的 B（2000）也被判已读 —— 这正是
     * 用户反馈的「读一条未读会话，再进列表其它红点也消失了」。
     */
    @Test
    public void openingOneUnreadDoesNotClearOtherUnread() {
        UnreadDotState st = new UnreadDotState();
        // A 当前(1000)，B(2000)、C(3000) 都未读
        assertTrue(st.evaluate("A", 1000, all(m("A", 1000), m("B", 2000), m("C", 3000))));
        // 用户点开最新的 C —— 旧版此时把全局水位抬到 3000，B 被误灭
        st.onSessionOpened("A", 1000);
        st.onSessionOpened("C", 3000);
        // B 从未被点开 → 必须仍判未读（整体 + 行级都要亮）
        assertTrue(st.evaluate("C", 3000, all(m("A", 1000), m("B", 2000), m("C", 3000))));
        assertTrue(st.isUnread("C", 3000, m("B", 2000)));
        // 逐条点开后才依次熄灭
        st.onSessionOpened("C", 3000);
        st.onSessionOpened("B", 2000);
        assertFalse(st.evaluate("B", 2000, all(m("A", 1000), m("B", 2000), m("C", 3000))));
    }

    /**
     * 逐条点开的顺序无关性：先点旧的再点新的，每一步只灭自己那条。
     */
    @Test
    public void openingSessionsOneByOneClearsOnlyEachOwn() {
        UnreadDotState st = new UnreadDotState();
        assertTrue(st.evaluate("A", 1000, all(m("A", 1000), m("B", 2000), m("C", 3000))));
        // 用户点开旧的 B：调用方抬「刚在看的 A」再抬「打开的 B」
        st.onSessionOpened("A", 1000);
        st.onSessionOpened("B", 2000);
        assertTrue(st.isUnread("B", 2000, m("C", 3000)));  // C 仍亮（从未点开）
        assertFalse(st.isUnread("B", 2000, m("A", 1000))); // A 比基线旧/已读 → 不亮
        // 再点开 C
        st.onSessionOpened("B", 2000);
        st.onSessionOpened("C", 3000);
        assertFalse(st.isUnread("C", 3000, m("B", 2000))); // B 已读 → 灭
        assertFalse(st.isUnread("C", 3000, m("A", 1000))); // A 仍不亮
    }

    // ---------------------------------------------------------------- 行级判定（isUnread）

    /** 行级与整体级同一水位：顶栏点亮时，「更新的那条」行必须被指认 */
    @Test
    public void rowDotMarksTheNewerOtherSession() {
        UnreadDotState st = new UnreadDotState();
        assertTrue(st.evaluate("A", 1000, all(m("A", 1000), m("B", 2000))));
        assertFalse(st.isUnread("A", 1000, m("A", 1000))); // 当前会话永不标行
        assertTrue(st.isUnread("A", 1000, m("B", 2000)));  // 更新的那条 → 行内点
    }

    /** 整体口径「回合结束抬当前会话不清点」在行级同样成立：B 的行点不许灭 */
    @Test
    public void rowDotSurvivesCurrentSessionTimestampBump() {
        UnreadDotState st = new UnreadDotState();
        assertTrue(st.isUnread("A", 1000, m("B", 2000)));
        // 回合结束：A.updated 抬到 3000，B 的行点仍在
        assertTrue(st.isUnread("A", 3000, m("B", 2000)));
    }

    /** 点开未读会话 = 该行熄灭（该条已读水位抬到其 updated） */
    @Test
    public void rowDotClearsAfterOpeningThatSession() {
        UnreadDotState st = new UnreadDotState();
        assertTrue(st.isUnread("A", 1000, m("B", 2000)));
        st.onSessionOpened("B", 2000);
        assertFalse(st.isUnread("B", 2000, m("A", 1000)));
        assertFalse(st.isUnread("B", 2000, m("B", 2000)));
    }

    /** 时间戳相等不算未读（与整体口径 equalTimestampDoesNotLight 一致） */
    @Test
    public void rowDotIgnoresEqualTimestamp() {
        UnreadDotState st = new UnreadDotState();
        assertFalse(st.isUnread("A", 2000, m("B", 2000)));
    }

    /** 无当前会话（curId null）→ 任何行都不标（与 evaluate 的 null 分支一致） */
    @Test
    public void rowDotNeverMarksWithoutCurrentSession() {
        UnreadDotState st = new UnreadDotState();
        assertFalse(st.isUnread(null, 1000, m("B", 2000)));
    }

    /**
     * 首帧基线：早于首帧当前会话的历史会话一律按已读，不许一进面板就全点亮。
     * 旧版用全局水位实现，新版用 baseline 地板实现，语义必须保持。
     */
    @Test
    public void olderSessionsThanBaselineStayDarkOnFirstFrame() {
        UnreadDotState st = new UnreadDotState();
        // 首帧：当前会话 A(3000)；B(2000)、C(1000) 都比它旧 → 都算已读
        assertFalse(st.evaluate("A", 3000, all(m("A", 3000), m("B", 2000), m("C", 1000))));
    }
}

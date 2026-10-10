package org.offblink.spore.panel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.offblink.spore.agent.Session;

import java.util.Arrays;
import java.util.List;

/**
 * 未读红点状态机契约（web panel.js renderUnread 的 JVM 镜像）。
 *
 * <p>口径（2026-10-10 用户拍板）：点亮 = 存在「非当前会话」且比**已读水位**更新的会话；
 * 水位只在**点开会话**时抬高 —— 打开列表不清、回合结束抬当前会话 updated 不清。
 */
public class UnreadDotStateTest {

    private static Session sess(String id, long updated) {
        Session s = new Session();
        s.id = id;
        s.updated = updated;
        return s;
    }

    private static List<Session> all(Session... ss) {
        return Arrays.asList(ss);
    }

    /** 当前会话 A 停在旧时间戳、B 更新在后 → 未读该亮 */
    @Test
    public void lightsWhenOtherSessionIsNewer() {
        UnreadDotState st = new UnreadDotState();
        assertTrue(st.evaluate("A", 1000, all(sess("A", 1000), sess("B", 2000))));
    }

    /** 当前会话自己就是最新 → 不许必亮 */
    @Test
    public void staysDarkWhenCurrentSessionIsNewest() {
        UnreadDotState st = new UnreadDotState();
        assertFalse(st.evaluate("A", 3000, all(sess("A", 3000), sess("B", 2000))));
    }

    /**
     * 缺陷钉子：回合结束（emitTurnEnd → saveActive）把当前会话 updated 抬到全库最新，
     * 随后状态刷新按旧口径「有比当前会话更新的其它会话」评估就不成立了 —— 那只是时间戳
     * 在动、用户没读，红点不许被清。
     */
    @Test
    public void turnEndTimestampBumpOfCurrentDoesNotClearUnread() {
        UnreadDotState st = new UnreadDotState();
        assertTrue(st.evaluate("A", 1000, all(sess("A", 1000), sess("B", 2000))));
        // 回合结束：A.updated 3000（== 全库最新），随后的刷新不许把点打灭
        assertTrue(st.evaluate("A", 3000, all(sess("A", 3000), sess("B", 2000))));
    }

    /** 打开列表不是已读：状态机没有「开列表」入口，任何次数的刷新都必须保持点亮 */
    @Test
    public void listRefreshAloneNeverClears() {
        UnreadDotState st = new UnreadDotState();
        for (int i = 0; i < 5; i++) {
            assertTrue(st.evaluate("A", 1000, all(sess("A", 1000), sess("B", 2000))));
        }
    }

    /** 点开那条更新的会话 = 已读：红点熄灭 */
    @Test
    public void openingTheNewerSessionClearsDot() {
        UnreadDotState st = new UnreadDotState();
        assertTrue(st.evaluate("A", 1000, all(sess("A", 1000), sess("B", 2000))));
        st.onSessionOpened(2000); // 用户点开 B
        assertFalse(st.evaluate("B", 2000, all(sess("A", 1000), sess("B", 2000))));
    }

    /** 点开的是一条更旧的会话 → 更新的那条仍未读，红点保持 */
    @Test
    public void openingOlderSessionKeepsUnread() {
        UnreadDotState st = new UnreadDotState();
        assertTrue(st.evaluate("A", 1000, all(sess("A", 1000), sess("B", 2000), sess("C", 3000))));
        st.onSessionOpened(2000); // 用户点开 B（不是最新的那条）
        assertTrue(st.evaluate("B", 2000, all(sess("A", 1000), sess("B", 2000), sess("C", 3000))));
    }

    /** 熄灭后来了新的会话 → 红点要能再亮（水位只到已读，不封死检测） */
    @Test
    public void newSessionAfterClearingLightsAgain() {
        UnreadDotState st = new UnreadDotState();
        st.onSessionOpened(2000);
        assertFalse(st.evaluate("B", 2000, all(sess("B", 2000))));
        assertTrue(st.evaluate("B", 2000, all(sess("B", 2000), sess("D", 4000))));
    }

    /** 边界：updated 与水位相等不算更新 */
    @Test
    public void equalTimestampDoesNotLight() {
        UnreadDotState st = new UnreadDotState();
        st.onSessionOpened(2000);
        assertFalse(st.evaluate("A", 1000, all(sess("A", 1000), sess("B", 2000))));
    }

    /**
     * 切换会话时「刚在看的那个」也一并算已读：A 看着 A 的回合结束（updated 抬到 3000），
     * 然后点开 B —— A 不许因为自己时间戳最新而回头点亮红点。
     */
    @Test
    public void previousSessionCountsAsReadWhenSwitching() {
        UnreadDotState st = new UnreadDotState();
        assertTrue(st.evaluate("A", 1000, all(sess("A", 1000), sess("B", 2000)))); // B 未读
        assertTrue(st.evaluate("A", 3000, all(sess("A", 3000), sess("B", 2000)))); // A 回合结束，点仍在
        st.onSessionOpened(3000); // 调用方先抬「刚在看的 A」
        st.onSessionOpened(2000); // 再抬「打开的 B」
        assertFalse(st.evaluate("B", 2000, all(sess("A", 3000), sess("B", 2000))));
    }
}

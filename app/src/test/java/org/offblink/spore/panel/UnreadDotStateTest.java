package org.offblink.spore.panel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.offblink.spore.agent.Session;

import java.util.Arrays;
import java.util.List;

/**
 * 未读红点状态机契约（web panel.js renderUnread 的 JVM 镜像）：
 * 点亮 = 列表没打开过 && 有比当前会话更新的其它会话；
 * 清除唯一触发 = 打开列表；回合结束抬高当前会话 updated 不许清点。
 */
public class UnreadDotStateTest {

    private static Session sess(String id, long updated) {
        Session s = new Session();
        s.id = id;
        s.created = updated;
        s.updated = updated;
        s.touched = updated;
        return s;
    }

    /** 当前会话 A 还停在旧时间戳、B 更新在后 → 未读该亮 */
    @Test
    public void lightsWhenOtherSessionIsNewer() {
        UnreadDotState dot = new UnreadDotState();
        List<Session> all = Arrays.asList(sess("B", 2000), sess("A", 1000));
        dot.refresh("A", 1000, all);
        assertTrue("有比当前会话更新的其它会话 → 红点亮", dot.visible());
    }

    /** 当前会话自己就是最新 → 没有更旧基准之外的动静，不许必亮 */
    @Test
    public void staysDarkWhenCurrentSessionIsNewest() {
        UnreadDotState dot = new UnreadDotState();
        List<Session> all = Arrays.asList(sess("A", 3000), sess("B", 2000));
        dot.refresh("A", 3000, all);
        assertFalse("当前会话最新且列表没动静 → 红点不亮", dot.visible());
    }

    /**
     * 回合结束：AgentEngine.emitTurnEnd → SessionStore.saveActive 把 A.updated 抬到 3000，
     * AnswerPanel/NativeAnswerPanel 随后的全量刷新按新基准评估「更新的其它会话」不再成立 ——
     * 这只是时间戳在动，用户没开列表，红点不许被清（缺陷复现的钉子）。
     */
    @Test
    public void turnEndTimestampBumpDoesNotClearUnread() {
        UnreadDotState dot = new UnreadDotState();
        dot.refresh("A", 1000, Arrays.asList(sess("B", 2000), sess("A", 1000)));
        assertTrue(dot.visible());

        dot.refresh("A", 3000, Arrays.asList(sess("A", 3000), sess("B", 2000)));
        assertTrue("回合结束抬高当前会话 updated 后红点仍亮", dot.visible());

        // 再刷多少次都不回落（状态刷新路径永不清除）
        dot.refresh("A", 4000, Arrays.asList(sess("A", 4000), sess("B", 2000)));
        assertTrue(dot.visible());
    }

    /** 打开列表 = 已读：清红点，且此后同样的数据不再回亮 */
    @Test
    public void openListClearsAndBlocksRelight() {
        UnreadDotState dot = new UnreadDotState();
        List<Session> stale = Arrays.asList(sess("B", 2000), sess("A", 1000));
        dot.refresh("A", 1000, stale);
        assertTrue(dot.visible());

        dot.onListOpened();
        assertFalse("打开列表清红点", dot.visible());

        dot.refresh("A", 1000, stale);
        assertFalse("列表开着检测闸门关死，同数据不许回亮", dot.visible());
    }

    /** 打开列表后的状态刷新路径也不得把点打回来（清点之后任何 refresh 都保持熄灭） */
    @Test
    public void refreshAfterOpenNeverTurnsDotBackOn() {
        UnreadDotState dot = new UnreadDotState();
        dot.onListOpened();
        dot.refresh("A", 1000, Arrays.asList(sess("B", 9999), sess("A", 1000)));
        assertFalse(dot.visible());
    }

    /** 切换/新建会话 = 列表有动静：重新武装检测闸门，后续更新的其它会话能再点亮 */
    @Test
    public void sessionChangeRearmsDetectionAfterListSeen() {
        UnreadDotState dot = new UnreadDotState();
        dot.onListOpened();

        dot.refresh("A", 1000, Arrays.asList(sess("B", 2000), sess("A", 1000)));
        assertFalse("没重新武装前不许点亮", dot.visible());

        dot.onSessionChanged(); // native 的 session-new / web 的当前会话切换
        dot.refresh("C", 1500, Arrays.asList(sess("B", 2000), sess("C", 1500)));
        assertTrue("重新武装后有更新的其它会话 → 红点再亮", dot.visible());
    }
}

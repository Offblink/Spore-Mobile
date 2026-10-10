package org.offblink.spore.panel;

import java.util.List;
import org.offblink.spore.agent.Session;

/**
 * 面板会话列表「未读红点」的状态机（纯 JVM 逻辑，单测直测；
 * web 侧 panel.js 的 renderUnread 是同一份口径的 JS 镜像）。
 *
 * <p>语义（2026-10-10 用户拍板：红点在「<b>点开会话</b>」时熄灭，不是「点开列表」）：
 * <ol>
 *   <li><b>已读水位</b> {@code seenThrough} = 用户显式点开过的会话 {@code updated} 最大值；
 *       面板首次显示时正在看的那个会话按已读起算（否则一进面板就误报）；
 *       点开会话时「刚在看的那个」也一并算已读（它回合结束会把自己抬成全库最新，
 *       不许在切走之后回头点亮红点）。</li>
 *   <li><b>点亮</b>：存在「不是当前会话」且 {@code updated > seenThrough} 的会话——
 *       即「有我没点开过、又比我看过的东西更新」的会话。</li>
 *   <li>水位只被「点开会话」抬高：回合结束（{@code AgentEngine.emitTurnEnd} →
 *       {@code SessionStore.saveActive}）把当前会话 {@code updated} 抬到最新只动时间戳、
 *       不动水位，红点不会无读自灭；打开列表也不抬水位、不清红点。</li>
 * </ol>
 */
public final class UnreadDotState {

    /** 已读水位（epoch ms）；0 = 还没起算（面板首帧评估时按当前会话起算） */
    private long seenThrough;

    /** 用户点开会话（列表行 / 记录页点入）= 唯一抬高水位的动作 */
    public void onSessionOpened(long updated) {
        seenThrough = Math.max(seenThrough, updated);
    }

    /**
     * 状态刷新时评估一次（含首帧起算：正在看的会话按已读）。
     *
     * @param curId      正在查看的会话 id
     * @param curUpdated 正在查看的会话的 {@code Session.updated}
     * @param all        全库会话（{@code SessionStore.loadAll} 的返回，顺序无关）
     * @return 红点该显示吗
     */
    public boolean evaluate(String curId, long curUpdated, List<Session> all) {
        if (seenThrough == 0) {
            seenThrough = Math.max(seenThrough, curUpdated);
        }
        if (curId == null) {
            return false;
        }
        for (Session s : all) {
            if (s.id != null && !s.id.equals(curId) && s.updated > seenThrough) {
                return true;
            }
        }
        return false;
    }
}

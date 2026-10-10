package org.offblink.spore.panel;

import java.util.List;
import org.offblink.spore.agent.Session;

/**
 * 面板会话列表「未读红点」的状态机（纯 JVM 逻辑，单测直测；
 * web 侧 panel.js 的 renderUnread 是同一份口径的 JS 镜像）。
 *
 * <p>语义两条：
 * <ol>
 *   <li><b>点亮</b>：列表没打开过，且存在「比当前会话更新的其它会话」
 *       （不是「存在其它会话」——库里 2 条就必亮会误报，第四轮实测抓过）。</li>
 *   <li><b>清除的唯一触发 = 用户打开列表</b>。回合结束
 *       （{@code AgentEngine.emitTurnEnd} → {@code SessionStore.saveActive} 把当前会话
 *       {@code updated} 抬到最新）与随后的全量 state 刷新都只是时间戳在动，
 *       用户没开列表就没读 —— 红点是<b>闩锁</b>：只点亮、不随刷新回落。</li>
 * </ol>
 */
public final class UnreadDotState {

    /** 列表自上次打开后有没有新动静（打开过 = 检测闸门关死，不许再点亮） */
    private boolean listSeen;
    /** 未读闩锁：点亮后只在「打开列表」时清零 */
    private boolean unread;

    /** 用户打开会话列表 = 已读：唯一清除点 */
    public void onListOpened() {
        listSeen = true;
        unread = false;
    }

    /** 新会话/切换会话 = 列表有动静：重新武装检测闸门（不碰已点亮的未读） */
    public void onSessionChanged() {
        listSeen = false;
    }

    /**
     * 状态刷新时评估一次：只点亮、不清除。
     * 回合结束会把当前会话 {@code updated} 抬到全库最新，「更旧基准」随之一时失效，
     * 那正是不能拿来清点的原因。
     *
     * @param curId      正在查看的会话 id
     * @param curUpdated 正在查看的会话的 {@code Session.updated}
     * @param all        全库会话（{@code SessionStore.loadAll} 的返回，顺序无关）
     */
    public void refresh(String curId, long curUpdated, List<Session> all) {
        if (listSeen || unread) {
            return; // 已读 → 检测闸门关死；已点亮 → 闩锁保持
        }
        for (Session s : all) {
            if (s.id != null && !s.id.equals(curId) && s.updated > curUpdated) {
                unread = true;
                return;
            }
        }
    }

    /** 红点该显示吗 */
    public boolean visible() {
        return unread;
    }
}

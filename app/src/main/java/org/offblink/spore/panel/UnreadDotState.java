package org.offblink.spore.panel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.offblink.spore.agent.SessionStore;

/**
 * 面板会话列表「未读红点」的状态机（纯 JVM 逻辑，单测直测；
 * web 侧 panel.js 的 renderUnread 是同一份口径的 JS 镜像）。
 *
 * <p>语义（2026-10-25 重写：逐会话已读水位，修「读一条灭一片」）：
 * <ol>
 *   <li><b>首帧基线</b> {@code baseline}：面板首帧把「正在看的那个会话」的 {@code updated}
 *       定为地板——比它旧的会话一律按已读（否则一进面板就把历史会话全点亮）。
 *       只设一次，后续刷新不再动。</li>
 *   <li><b>逐会话已读水位</b> {@code readThrough}：id → 用户点开过它的那一刻的 {@code updated}。
 *       点开会话时「刚在看的那个」也一并算已读（它回合结束会把自己抬成全库最新，
 *       不许在切走之后回头点亮红点）。</li>
 *   <li><b>点亮</b>：存在「不是当前会话」且同时高于 {@code baseline} 与它<b>自己</b>
 *       已读水位的会话。每条只跟自己的水位比——点开最新那条不会顺带把其它未读会话的点
 *       也灭掉（旧版单一全局水位的 bug，2026-10-25 用户反馈）。</li>
 *   <li>水位只被「点开会话」抬高：回合结束把当前会话 {@code updated} 抬到最新只动时间戳、
 *       不抬它自己的已读水位（当前会话本就不参与判定），红点不会无读自灭；
 *       打开列表也不抬水位、不清红点。</li>
 * </ol>
 */
public final class UnreadDotState {

    /** 首帧基线（地板）：首帧正在看的会话 {@code updated}，比它旧的一律算已读；只设一次 */
    private long baseline;

    /** 逐会话已读水位：会话 id → 用户点开过它的那一刻的 {@code updated} */
    private final Map<String, Long> readThrough = new HashMap<>();

    /**
     * 用户点开会话（列表行 / 记录页点入）= 唯一抬高该会话已读水位的动作。
     * 只抬这一条，不碰其它会话——点开最新一条不会连带清掉其它未读。
     *
     * @param id      被点开的会话 id
     * @param updated 该会话此刻的 {@code Session.updated}
     */
    public void onSessionOpened(String id, long updated) {
        if (id == null) {
            return;
        }
        readThrough.put(id, Math.max(readThrough.getOrDefault(id, 0L), updated));
    }

    /**
     * 状态刷新时评估一次（含首帧起算：正在看的会话按已读、并定基线）。
     *
     * @param curId      正在查看的会话 id
     * @param curUpdated 正在查看的会话的 {@code Session.updated}
     * @param all        全库元数据（{@code SessionStore.loadMeta} 的返回，顺序无关）
     * @return 红点该显示吗
     */
    public boolean evaluate(String curId, long curUpdated, List<SessionStore.Meta> all) {
        if (curId == null) {
            return false;
        }
        seed(curId, curUpdated);
        for (SessionStore.Meta m : all) {
            if (isUnread(curId, curUpdated, m)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 行级判定：这条会话在列表里该画未读点吗（web renderList 的 {@code unread} 同口径）。
     * 顶栏红点只说「有未读」，行内点才指认是哪个会话——与 {@link #evaluate} 共用同一份水位。
     *
     * @param curId      正在查看的会话 id
     * @param curUpdated 正在查看的会话的 {@code Session.updated}（首帧基线起算用）
     * @param m          待判定的会话元数据行
     */
    public boolean isUnread(String curId, long curUpdated, SessionStore.Meta m) {
        if (curId == null || m == null || m.id == null) {
            return false;
        }
        seed(curId, curUpdated);
        if (m.id.equals(curId)) {
            return false; // 当前会话永不标未读
        }
        // 双重地板：比首帧基线旧的算已读；比自己已读水位高的才叫未读
        return m.updated > baseline && m.updated > readThrough.getOrDefault(m.id, 0L);
    }

    /** 首帧起算：基线 = 正在看的会话 updated（只设一次）；当前会话本身也按已读登记 */
    private void seed(String curId, long curUpdated) {
        if (baseline == 0) {
            baseline = curUpdated;
        }
        readThrough.putIfAbsent(curId, curUpdated);
    }
}

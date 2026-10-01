package org.offblink.spore.panel;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;
import org.offblink.spore.R;
import org.offblink.spore.agent.AgentEngine;
import org.offblink.spore.agent.Session;
import org.offblink.spore.agent.SessionStore;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.noties.markwon.Markwon;

/**
 * 浮动作答面板 —— MV3 桌面抽屉的移动复刻（白抽屉语言）：
 * 顶栏 💬(未读点) ★ 标题 … 状态 ■ —；listpop 会话弹层（行含 收藏/重命名/删除）；
 * 删除确认与重命名两个 in-panel 模态（桌面 #confirm/#rename 同构）；
 * 进出场 = MV3 的 .42s 滑动 + pop 弹层动效。
 * 窗口可聚焦（否则输入法拉不起来）；顶栏拖动、返回键收起、球长按唤回。
 */
public final class NativeAnswerPanel implements Panel, AnswerAdapter.Host {

    private final Context ctx;
    private final WindowManager wm;
    private final AgentEngine engine;
    private final Markwon markwon;
    private final AnswerAdapter adapter;

    private View panel;
    private WindowManager.LayoutParams params;
    private TextView title;
    private TextView status;
    private EditText input;
    private View send;
    private RecyclerView list;

    private View sessionsBtn;
    private View unreadDot;
    private TextView favBtn;
    private View listpop;
    private LinearLayout listrows;
    private View listempty;
    private View confirmBox;
    private TextView confirmBody;
    private View renameBox;
    private EditText renameInput;

    private boolean visible;
    /** 列表自上次打开后有没有新会话（MV3 has-unread 语义） */
    private boolean listSeen;

    /** 模态操作的目标会话（确认/重命名期间暂存） */
    private Session target;

    public NativeAnswerPanel(Context ctx, WindowManager wm, AgentEngine engine) {
        this.ctx = ctx;
        this.wm = wm;
        this.engine = engine;
        this.markwon = Markwon.builder(ctx).build();
        this.adapter = new AnswerAdapter(engine.session(), markwon, this);
    }

    /** Panel 契约：与 web 面板同语义（原生无 WebView，收起即释放） */
    public void destroy() {
        close();
    }

    // ---------------------------------------------------------------- 生命周期

    /** 截图落盘后调用：开面板 + 起两阶段回合 */
    public void openWithCapture(File crop) {
        show();
        engine.newCaptureTurn(crop.getAbsolutePath(), "");
    }

    /** 记录页/会话列表点入：换入已存会话继续对话（桌面点列表行语义） */
    public void openSession(String sessionId) {
        if (!engine.loadSession(sessionId)) {
            Toast.makeText(ctx, R.string.session_gone, Toast.LENGTH_SHORT).show();
            return;
        }
        show();
    }

    /** 球长按 = 面板开/关（第八轮拍板：不再连带弹会话列表，列表由面板内 💬 手动开） */
    @Override
    public void toggle() {
        if (!visible) {
            show();
            return;
        }
        close(); // 内部已 closeList()
    }

    public void show() {
        if (visible) {
            return;
        }
        panel = LayoutInflater.from(ctx).inflate(R.layout.panel_answer, null);
        title = panel.findViewById(R.id.panel_title);
        status = panel.findViewById(R.id.panel_status);
        input = panel.findViewById(R.id.panel_input);
        send = panel.findViewById(R.id.panel_send);
        list = panel.findViewById(R.id.panel_list);
        sessionsBtn = panel.findViewById(R.id.panel_sessions);
        unreadDot = panel.findViewById(R.id.panel_unread);
        favBtn = panel.findViewById(R.id.panel_fav);
        listpop = panel.findViewById(R.id.panel_listpop);
        listrows = panel.findViewById(R.id.panel_listrows);
        listempty = panel.findViewById(R.id.panel_listempty);
        confirmBox = panel.findViewById(R.id.panel_confirm);
        confirmBody = panel.findViewById(R.id.panel_confirm_body);
        renameBox = panel.findViewById(R.id.panel_rename);
        renameInput = panel.findViewById(R.id.panel_rename_input);

        list.setLayoutManager(new LinearLayoutManager(ctx));
        list.setAdapter(adapter);

        // 返回键：输入法先吃掉；空闲时收起面板
        panel.setFocusableInTouchMode(true);
        panel.requestFocus();
        panel.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                close();
                return true;
            }
            return false;
        });

        adapter.setSession(engine.session());
        title.setText(engine.session().title);
        status.setText("");
        setInputEnabled(!engine.isBusy(engine.session().id));
        if (engine.isBusy(engine.session().id)) {
            status.setText(sessionDisplayStatus());
        }

        panel.findViewById(R.id.panel_stop).setOnClickListener(v -> engine.cancel());
        panel.findViewById(R.id.panel_min).setOnClickListener(v -> close());
        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty() || !input.isEnabled()) {
                return;
            }
            input.setText("");
            if (!engine.sendFollowup(text)) {
                // 同一会话已有回合在跑（别的会话并行不拦这里）
                Toast.makeText(ctx, R.string.busy_wait, Toast.LENGTH_SHORT).show();
            }
        });

        // 💬 会话列表弹层 / ★ 收藏当前会话
        sessionsBtn.setOnClickListener(v -> toggleList());
        favBtn.setOnClickListener(v -> toggleFav());
        // 点面板「空白背景」收起弹层：消息区 / 头部背景 / 输入条背景
        // （功能键各自消费点击不受影响；v1 全屏 scrim 会偷走头部按键，撤掉）
        list.setOnClickListener(v -> closeList());
        panel.findViewById(R.id.panel_header).setOnClickListener(v -> closeList());
        panel.findViewById(R.id.panel_input).setOnClickListener(v -> closeList());
        wireModals();

        Point sz = displaySize();
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                Math.round(sz.y * 0.55f),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 可聚焦（否则输入法拉不起来）；KEEP_SCREEN_ON = 面板开着就锁不了屏——
                // 锁屏/闲置会触发系统收回投屏 → 杀服务 → 悬浮窗全灭（第五轮根因链）
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = 0;
        params.y = Math.round(sz.y * 0.45f);

        attachHeaderDrag();
        wm.addView(panel, params);
        visible = true;
        paintFav();
        paintUnread();
        refresh(true);

        // MV3 进场：面板上滑 + 淡入 .42s
        panel.setTranslationY(params.height * 0.3f);
        panel.setAlpha(0f);
        panel.animate().translationY(0f).alpha(1f)
                .setDuration(420)
                .setInterpolator(AnimationUtils.loadInterpolator(
                        ctx, android.R.interpolator.fast_out_slow_in))
                .start();
    }

    public void close() {
        if (!visible) {
            return;
        }
        final View dying = panel;
        panel = null;
        visible = false;
        closeList();
        if (dying != null) {
            dying.animate().translationY(params.height * 0.3f).alpha(0f)
                    .setDuration(240)
                    .setInterpolator(AnimationUtils.loadInterpolator(
                            ctx, android.R.interpolator.fast_out_slow_in))
                    .withEndAction(() -> {
                        try {
                            wm.removeView(dying);
                        } catch (Exception ignored) {
                            // 窗口可能已被系统移除
                        }
                    })
                    .start();
        }
    }

    // ---------------------------------------------------------------- 会话列表（MV3 listpop）

    private void toggleList() {
        boolean emptyShown = listempty != null
                && listempty.getVisibility() == View.VISIBLE;
        if ((listpop != null && listpop.getVisibility() == View.VISIBLE) || emptyShown) {
            closeList();
            return;
        }
        buildRows();
        listSeen = true;
        paintUnread();
    }

    private void closeList() {
        if (listpop != null) {
            listpop.setVisibility(View.GONE);
        }
        if (listempty != null) {
            listempty.setVisibility(View.GONE);
        }
    }

    private void buildRows() {
        List<Session> all = SessionStore.loadAll(ctx);
        listrows.removeAllViews();
        if (all.isEmpty()) {
            listpop.setVisibility(View.GONE);
            listempty.setVisibility(View.VISIBLE);
            listempty.startAnimation(AnimationUtils.loadAnimation(ctx, R.anim.spore_pop_in));
            return;
        }
        String currentId = engine.session().id;
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        for (Session s : all) {
            View row = LayoutInflater.from(ctx).inflate(R.layout.item_session, listrows, false);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
            lp.leftMargin = 0;
            lp.rightMargin = 0;
            lp.topMargin = 0;
            lp.bottomMargin = 0;
            row.setLayoutParams(lp);
            bindRow(row, s, fmt, currentId);
            listrows.addView(row);
        }
        listpop.setVisibility(View.VISIBLE);
        listpop.startAnimation(AnimationUtils.loadAnimation(ctx, R.anim.spore_pop_in));
        // 高度上限 64% 屏高（桌面 max-height:64vh）
        listpop.post(() -> {
            int max = (int) (displaySize().y * 0.64f);
            if (listpop.getHeight() > max) {
                ViewGroup.LayoutParams lp = listpop.getLayoutParams();
                lp.height = max;
                listpop.setLayoutParams(lp);
            }
        });
    }

    /** 行绑定：标题/时间/星标/✎/✕/当前态 —— 与记录页同一行组件 */
    private void bindRow(View row, Session s, SimpleDateFormat fmt, String currentId) {
        TextView t = row.findViewById(R.id.item_title);
        TextView ts = row.findViewById(R.id.item_time);
        TextView fav = row.findViewById(R.id.item_fav);
        View bar = row.findViewById(R.id.item_bar);

        t.setText(s.title);
        ts.setText(fmt.format(new Date(s.updated)));
        paintRowFav(fav, s.fav);
        boolean active = s.id.equals(currentId);
        row.setSelected(active);
        if (bar != null) {
            bar.setVisibility(active ? View.VISIBLE : View.GONE);
        }

        row.setOnClickListener(v -> {
            if (!s.id.equals(engine.session().id)) {
                if (!engine.loadSession(s.id)) {
                    Toast.makeText(ctx, R.string.session_gone, Toast.LENGTH_SHORT).show();
                    return;
                }
                adapter.setSession(engine.session());
                title.setText(engine.session().title);
                status.setText("");
                refresh(true);
            }
            closeList();
        });
        View ren = row.findViewById(R.id.item_rename);
        View del = row.findViewById(R.id.item_delete);
        if (ren != null) {
            ren.setOnClickListener(v -> showRename(s));
        }
        if (del != null) {
            del.setOnClickListener(v -> showConfirm(s));
        }
        fav.setOnClickListener(v -> {
            s.fav = !s.fav;
            SessionStore.save(ctx, s);
            paintRowFav(fav, s.fav);
            if (s.id.equals(engine.session().id)) {
                paintFav();
            }
            // 第六轮回灌：收藏要有反馈（第四轮 web 版 toast 实测过）
            Toast.makeText(ctx, s.fav ? "收藏成功" : "已取消收藏", Toast.LENGTH_SHORT).show();
            if (listpop != null && listpop.getVisibility() == View.VISIBLE) {
                buildRows(); // 原地刷新列表、不退出
            }
        });
    }

    private void paintRowFav(TextView fav, boolean on) {
        fav.setTextColor(on ? 0xFFEC4899 : 0xFFB3B8CD);
    }

    private void toggleFav() {
        Session cur = engine.session();
        cur.fav = !cur.fav;
        SessionStore.save(ctx, cur);
        paintFav();
    }

    private void paintFav() {
        if (favBtn != null) {
            favBtn.setTextColor(engine.session().fav ? 0xFFEC4899 : 0xFFB3B8CD);
        }
    }

    private void paintUnread() {
        if (unreadDot == null) {
            return;
        }
        // 第六轮回灌 web 版语义：红点 = 有比当前会话**更新**的其它会话
        // （原「存在其它会话」在库里 2+ 条时必亮，第四轮实测抓过）
        String curId = engine.session().id;
        long curUpdated = engine.session().updated;
        boolean newer = false;
        for (Session s : SessionStore.loadAll(ctx)) {
            if (!s.id.equals(curId) && s.updated > curUpdated) {
                newer = true;
                break;
            }
        }
        unreadDot.setVisibility(!listSeen && newer ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- in-panel 模态

    private void wireModals() {
        confirmBox.findViewById(R.id.panel_confirm_no).setOnClickListener(v ->
                confirmBox.setVisibility(View.GONE));
        confirmBox.findViewById(R.id.panel_confirm_yes).setOnClickListener(v -> {
            confirmBox.setVisibility(View.GONE);
            if (target != null) {
                if (!engine.deleteSession(target.id)) {
                    Toast.makeText(ctx, R.string.session_gone, Toast.LENGTH_SHORT).show();
                } else {
                    adapter.setSession(engine.session());
                    title.setText(engine.session().title);
                    status.setText("");
                    refresh(true);
                    Toast.makeText(ctx, "删除成功", Toast.LENGTH_SHORT).show();
                    target = null;
                    buildRows(); // 原地刷新列表、不退出（第六轮回灌 web 行为）
                    return;
                }
                target = null;
            }
        });
        renameBox.findViewById(R.id.panel_rename_no).setOnClickListener(v -> {
            renameBox.setVisibility(View.GONE);
            hideKeyboard();
        });
        renameBox.findViewById(R.id.panel_rename_yes).setOnClickListener(v -> {
            String name = renameInput.getText().toString().trim();
            renameBox.setVisibility(View.GONE);
            hideKeyboard();
            if (target != null && !name.isEmpty()) {
                if (!engine.renameSession(target.id, name)) {
                    Toast.makeText(ctx, R.string.session_gone, Toast.LENGTH_SHORT).show();
                } else {
                    title.setText(engine.session().title);
                    refresh(false);
                    Toast.makeText(ctx, "重命名成功", Toast.LENGTH_SHORT).show();
                    target = null;
                    buildRows(); // 原地刷新列表、不退出（第六轮回灌 web 行为）
                    return;
                }
                target = null;
            }
        });
    }

    private void showConfirm(Session s) {
        target = s;
        confirmBody.setText(ctx.getString(R.string.delete_body, s.title));
        confirmBox.bringToFront(); // 模态压在 listpop 之上，别再互相遮挡（第六轮回灌）
        confirmBox.setVisibility(View.VISIBLE);
        popIn(confirmBox);
    }

    private void showRename(Session s) {
        target = s;
        renameInput.setText(s.title);
        renameInput.setSelection(s.title.length());
        renameBox.bringToFront(); // 模态压在 listpop 之上，别再互相遮挡（第六轮回灌）
        renameBox.setVisibility(View.VISIBLE);
        popIn(renameBox);
    }

    /** 桌面 dialog pop .24s 同款 */
    private void popIn(View v) {
        v.setAlpha(0f);
        v.setScaleX(0.94f);
        v.setScaleY(0.94f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(240)
                .setInterpolator(AnimationUtils.loadInterpolator(
                        ctx, android.R.interpolator.fast_out_slow_in))
                .start();
    }

    private void hideKeyboard() {
        if (input != null) {
            input.clearFocus();
        }
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && panel != null) {
            imm.hideSoftInputFromWindow(panel.getWindowToken(), 0);
        }
    }

    // ---------------------------------------------------------------- 拖动

    private void attachHeaderDrag() {
        View header = panel.findViewById(R.id.panel_header);
        final int[] startRaw = new int[2];
        final int[] startParam = new int[2];
        header.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startRaw[0] = (int) event.getRawX();
                    startRaw[1] = (int) event.getRawY();
                    startParam[0] = params.x;
                    startParam[1] = params.y;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    Point sz = displaySize();
                    params.x = clamp(startParam[0] + ((int) event.getRawX() - startRaw[0]),
                            0, Math.max(0, sz.x - panel.getWidth()));
                    params.y = clamp(startParam[1] + ((int) event.getRawY() - startRaw[1]),
                            0, Math.max(0, sz.y - params.height));
                    wm.updateViewLayout(panel, params);
                    return true;
                }
                default:
                    return true;
            }
        });
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---------------------------------------------------------------- 引擎事件（主线程）

    @Override
    public void onEvent(JSONObject ev) {
        if (!visible) {
            return; // 收起期间只丢刷新，状态本体都在 session 里，重开时全量重绘
        }
        String type = ev.optString("type");
        // 并行回合（第九轮）：别的会话的流式事件不进当前屏；但回合结束意味着列表有动静
        // （标题/时间戳落盘），这里补一次行刷新（未读点随之更新）。
        String sid = ev.optString("sid");
        if (!sid.isEmpty() && !sid.equals(engine.session().id)) {
            if ("turn-end".equals(type)) {
                refresh(true);
            }
            return;
        }
        switch (type) {
            case "session-new":
                // 每次截屏搜题新开会话：重绑消息源、清标题与状态
                adapter.setSession(engine.session());
                title.setText(engine.session().title);
                status.setText("");
                listSeen = false; // 新会话 = 列表有动静 → 未读点
                paintUnread();
                closeList();
                refresh(true);
                break;
            case "title":
                title.setText(ev.optString("title"));
                break;
            case "status":
                status.setText(ev.optString("text"));
                break;
            case "answer-start":
            case "chat-start":
                setInputEnabled(false);
                refresh(true);
                break;
            case "turn-end":
                status.setText(sessionDisplayStatus());
                setInputEnabled(true);
                if (ev.optBoolean("error")) {
                    Toast.makeText(ctx, ev.optString("message"), Toast.LENGTH_LONG).show();
                }
                refresh(true);
                break;
            case "error":
                Toast.makeText(ctx, ev.optString("message"), Toast.LENGTH_LONG).show();
                refresh(false);
                break;
            default:
                refresh(true);
                break;
        }
    }

    private String sessionDisplayStatus() {
        String s = engine.session().status;
        if ("aborted".equals(s)) {
            return ctx.getString(R.string.panel_status_aborted);
        }
        if ("error".equals(s)) {
            return "";
        }
        return ctx.getString(R.string.panel_status_done);
    }

    private void setInputEnabled(boolean enabled) {
        input.setEnabled(enabled);
        send.setEnabled(enabled);
        send.setAlpha(enabled ? 1f : 0.45f);
    }

    // ---------------------------------------------------------------- Host（适配器回调）

    @Override
    public void onToggleThink(Session.Msg m) {
        m.thinkOpen = !m.thinkOpen;
        refresh(false);
    }

    @Override
    public void onVerifyNow() {
        setInputEnabled(false);
        engine.verifyOnly();
    }

    @Override
    public String statusText() {
        return engine.session().status;
    }

    // ---------------------------------------------------------------- 渲染

    private void refresh(boolean followBottom) {
        if (!visible || list == null) {
            return;
        }
        boolean atBottom = !list.canScrollVertically(1);
        adapter.notifyDataSetChanged();
        if (followBottom && atBottom) {
            int last = adapter.getItemCount() - 1;
            if (last >= 0) {
                list.scrollToPosition(last);
            }
        }
    }

    private Point displaySize() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            return new Point(b.width(), b.height());
        }
        Point p = new Point();
        //noinspection deprecation
        wm.getDefaultDisplay().getSize(p);
        return p;
    }
}

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
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;
import org.offblink.spore.R;
import org.offblink.spore.agent.AgentEngine;
import org.offblink.spore.agent.Session;

import java.io.File;

import io.noties.markwon.Markwon;

/**
 * 浮动作答面板（handoff §9.2：可拖半屏卡 + 收起回球）。
 * 窗口可聚焦（不带 FLAG_NOT_FOCUSABLE，否则 EditText 拉不起输入法）——
 * 顶栏拖动、✕ 以外的收起按钮、返回键收起都在这里；球长按再唤回。
 * 事件驱动：实现 {@link AgentEngine.Listener}（主线程），全量 rebind + 贴底跟随。
 */
public final class AnswerPanel implements AgentEngine.Listener, AnswerAdapter.Host {

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
    private boolean visible;

    public AnswerPanel(Context ctx, WindowManager wm, AgentEngine engine) {
        this.ctx = ctx;
        this.wm = wm;
        this.engine = engine;
        this.markwon = Markwon.builder(ctx).build();
        this.adapter = new AnswerAdapter(engine.session(), markwon, this);
    }

    // ---------------------------------------------------------------- 生命周期

    /** 截图落盘后调用：开面板 + 起两阶段回合 */
    public void openWithCapture(File crop) {
        show();
        engine.newCaptureTurn(crop.getAbsolutePath(), "");
    }

    /** 球长按 = 面板开/收切换（§9 补充：收起后唯一的唤回入口） */
    public void toggle() {
        if (visible) {
            close();
        } else {
            show();
        }
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

        list.setLayoutManager(new LinearLayoutManager(ctx));
        list.setAdapter(adapter);

        // 返回键：输入法先吃掉；空闲时收起面板（回到答题 App 的返回语义）
        panel.setFocusableInTouchMode(true);
        panel.requestFocus();
        panel.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                close();
                return true;
            }
            return false;
        });

        title.setText(engine.session().title);
        status.setText("");

        panel.findViewById(R.id.panel_stop).setOnClickListener(v -> engine.cancel());
        panel.findViewById(R.id.panel_min).setOnClickListener(v -> close());
        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty() || !input.isEnabled()) {
                return;
            }
            input.setText("");
            engine.sendFollowup(text);
        });

        Point sz = displaySize();
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                Math.round(sz.y * 0.55f),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                0, // 可聚焦：不带 FLAG_NOT_FOCUSABLE，否则输入法拉不起来
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = 0;
        params.y = Math.round(sz.y * 0.45f);

        attachHeaderDrag();
        wm.addView(panel, params);
        visible = true;
        refresh(true);
    }

    public void close() {
        if (!visible) {
            return;
        }
        if (panel != null) {
            try {
                wm.removeView(panel);
            } catch (Exception ignored) {
                // 窗口可能已被系统移除
            }
            panel = null;
        }
        visible = false;
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

    // ---------------------------------------------------------------- 引擎事件（主线程）

    @Override
    public void onEvent(JSONObject ev) {
        if (!visible) {
            return; // 收起期间只丢刷新，状态本体都在 session 里，重开时全量重绘
        }
        String type = ev.optString("type");
        switch (type) {
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

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }
}

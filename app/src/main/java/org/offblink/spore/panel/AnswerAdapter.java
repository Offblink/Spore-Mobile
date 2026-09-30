package org.offblink.spore.panel;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.offblink.spore.R;
import org.offblink.spore.agent.Phases;
import org.offblink.spore.agent.Session;

import java.util.List;

import io.noties.markwon.Markwon;

/**
 * 消息流适配器（单布局多视图型）：user 行 / answer 行（思考+正文+核实块+检索小票）/ chat 行。
 * 全量 rebind（notifyDataSetChanged）：消息量小（<20 行），流式刷新按 token 全量重绑足够快，
 * 换来的是「事件 → 状态 → 展示」零同步 bug（stale-row 家族的教训）。
 */
public final class AnswerAdapter extends RecyclerView.Adapter<AnswerAdapter.VH> {

    /** 面板实现：思考折叠、核实按钮、当前状态文案 */
    public interface Host {
        void onToggleThink(Session.Msg m);

        void onVerifyNow();

        /** 面板级状态（verifying/searching/answering/done…），badge 据此显示进行中态 */
        String statusText();
    }

    private static final int TYPE_USER = 0;
    private static final int TYPE_ANSWER = 1;
    private static final int TYPE_CHAT = 2;

    private final Markwon markwon;
    private final Host host;
    private List<Session.Msg> messages;

    public AnswerAdapter(Session session, Markwon markwon, Host host) {
        this.markwon = markwon;
        this.host = host;
        this.messages = session.messages;
        setHasStableIds(false);
    }

    /** 换会话（每次截屏搜题新开）：重绑消息源并全量刷新 */
    public void setSession(Session s) {
        this.messages = s.messages;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    @Override
    public int getItemViewType(int position) {
        Session.Msg m = messages.get(position);
        if ("user".equals(m.role)) {
            return TYPE_USER;
        }
        return "answer".equals(m.kind) ? TYPE_ANSWER : TYPE_CHAT;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_msg, parent, false);
        return new VH(v, viewType);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Session.Msg m = messages.get(position);
        if (h.viewType == TYPE_USER) {
            bindUser(h, m);
        } else if (h.viewType == TYPE_ANSWER) {
            bindAnswer(h, m);
        } else {
            bindChat(h, m);
        }
    }

    private void bindUser(VH h, Session.Msg m) {
        h.thinkToggle.setVisibility(View.GONE);
        h.think.setVisibility(View.GONE);
        h.verify.setVisibility(View.GONE);
        h.tools.setVisibility(View.GONE);
        String text = m.text;
        if (text.isEmpty() && m.hasImage) {
            text = h.itemView.getContext().getString(R.string.row_capture);
        }
        h.preview.setText(text);
    }

    private void bindChat(VH h, Session.Msg m) {
        h.thinkToggle.setVisibility(View.GONE);
        h.think.setVisibility(View.GONE);
        h.verify.setVisibility(View.GONE);
        h.tools.setVisibility(View.GONE);
        markwon.setMarkdown(h.preview, m.text);
    }

    private void bindAnswer(VH h, Session.Msg m) {
        // 思考块：可折叠（桌面抽屉同款心智）
        if (m.think.isEmpty()) {
            h.thinkToggle.setVisibility(View.GONE);
            h.think.setVisibility(View.GONE);
        } else {
            h.thinkToggle.setVisibility(View.VISIBLE);
            h.thinkToggle.setText(m.thinkOpen ? R.string.panel_think_on : R.string.panel_think_off);
            h.think.setVisibility(m.thinkOpen ? View.VISIBLE : View.GONE);
            h.think.setText(m.think);
            h.thinkToggle.setOnClickListener(v -> host.onToggleThink(m));
        }

        // 正文：NO/ANS/WHY 拍成人话（流式过程中也走同一渲染）
        markwon.setMarkdown(h.preview,
                Phases.formatAnswerPreview(m.no, m.ans, m.why));

        // 核实块
        String status = host.statusText();
        boolean verifying = "verifying".equals(status);
        boolean searching = "searching".equals(status);
        boolean showVerify = m.verifyRan || m.verifySkipped || m.verifyPending
                || verifying || searching || !m.verifyStreamNote.isEmpty();
        if (!showVerify) {
            h.verify.setVisibility(View.GONE);
        } else {
            h.verify.setVisibility(View.VISIBLE);
            if (m.verifyRan) {
                h.badge.setText("FIX".equals(m.verifyVerdict)
                        ? R.string.badge_fix : "OK".equals(m.verifyVerdict)
                        ? R.string.badge_ok : R.string.badge_ran);
                h.note.setText(m.verifyNote);
            } else if (m.verifySkipped) {
                h.badge.setText(R.string.badge_skipped);
                h.note.setText(m.verifyNote);
            } else if (m.verifyPending) {
                h.badge.setText(R.string.badge_pending);
                h.note.setText(m.verifyNote);
            } else if (searching) {
                h.badge.setText(R.string.badge_searching);
                h.note.setText(m.verifyStreamNote);
            } else {
                h.badge.setText(R.string.badge_verifying);
                h.note.setText(m.verifyStreamNote);
            }
            if (m.verifyPending) {
                h.verifyBtn.setVisibility(View.VISIBLE);
                h.verifyBtn.setOnClickListener(v -> host.onVerifyNow());
            } else {
                h.verifyBtn.setVisibility(View.GONE);
            }
        }

        // 检索小票
        if (m.tools.isEmpty()) {
            h.tools.setVisibility(View.GONE);
        } else {
            h.tools.setVisibility(View.VISIBLE);
            StringBuilder sb = new StringBuilder();
            for (String chip : m.tools) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("· ").append(chip);
            }
            h.tools.setText(sb.toString());
        }
    }

    static final class VH extends RecyclerView.ViewHolder {
        final int viewType;
        final TextView thinkToggle;
        final TextView think;
        final TextView preview;
        final View verify;
        final TextView badge;
        final TextView note;
        final android.widget.Button verifyBtn;
        final TextView tools;

        VH(@NonNull View itemView, int viewType) {
            super(itemView);
            this.viewType = viewType;
            thinkToggle = itemView.findViewById(R.id.row_toggle);
            think = itemView.findViewById(R.id.row_think);
            preview = itemView.findViewById(R.id.row_preview);
            verify = itemView.findViewById(R.id.row_verify);
            badge = itemView.findViewById(R.id.verify_badge);
            note = itemView.findViewById(R.id.verify_note);
            verifyBtn = itemView.findViewById(R.id.verify_btn);
            tools = itemView.findViewById(R.id.row_tools);
        }
    }
}

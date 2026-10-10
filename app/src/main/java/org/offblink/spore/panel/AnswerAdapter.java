package org.offblink.spore.panel;

import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
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
        styleUserBubble(h.preview);
        h.preview.setText(text);
    }

    /**
     * MV3 用户文本气泡：userchip 底、10/10/10/3 圆角（左下收成小尾巴）、chip_text 14.5sp。
     * 布局默认的 17.5sp spore_ink 是回答/闲聊正文样式，用户行按类型覆写（VH 按 viewType 分池，
     * 不会串到回答行）。
     */
    private void styleUserBubble(TextView v) {
        float d = v.getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadii(new float[]{
                10 * d, 10 * d, // 左上
                10 * d, 10 * d, // 右上
                10 * d, 10 * d, // 右下
                3 * d, 3 * d}); // 左下（尾巴）
        bg.setColor(ContextCompat.getColor(v.getContext(), R.color.spore_userchip));
        v.setBackground(bg);
        int padH = Math.round(10 * d);
        int padV = Math.round(6 * d);
        v.setPadding(padH, padV, padH, padV);
        v.setTextColor(ContextCompat.getColor(v.getContext(), R.color.spore_chip_text));
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
    }

    private void bindChat(VH h, Session.Msg m) {
        h.thinkToggle.setVisibility(View.GONE);
        h.think.setVisibility(View.GONE);
        h.verify.setVisibility(View.GONE);
        h.tools.setVisibility(View.GONE);
        markwon.setMarkdown(h.preview, LatexDelims.normalizeForMarkwon(m.text));
    }

    private void bindAnswer(VH h, Session.Msg m) {
        // 思考块：可折叠（桌面抽屉同款心智）
        if (m.think.isEmpty()) {
            h.thinkToggle.setVisibility(View.GONE);
            h.think.setVisibility(View.GONE);
        } else {
            h.thinkToggle.setVisibility(View.VISIBLE);
            h.thinkToggle.setText(m.thinkOpen ? R.string.panel_think_on : R.string.panel_think_off);
            // MV3 思考头 = 节标签：spore_label + 宽字距
            h.thinkToggle.setTextColor(
                    ContextCompat.getColor(h.thinkToggle.getContext(), R.color.spore_label));
            h.thinkToggle.setLetterSpacing(0.1f); // MV3 .think-h letter-spacing:.1em
            h.think.setVisibility(m.thinkOpen ? View.VISIBLE : View.GONE);
            h.think.setText(m.think);
            h.thinkToggle.setOnClickListener(v -> host.onToggleThink(m));
        }

        // 正文：题号头单独成段（否则「第3题 ## 标题」会把行首块元素顶掉），
        // ANS/WHY 各自成段后交给 markdown；公式分隔符先归一到 ext-latex 认得的形态
        markwon.setMarkdown(h.preview, LatexDelims.normalizeForMarkwon(
                Phases.answerMarkdown(m.no, m.ans, m.why)));

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
            paintVerifyBadge(h.badge, m);
        }

        // 检索小票
        if (m.tools.isEmpty()) {
            h.tools.setVisibility(View.GONE);
        } else {
            h.tools.setVisibility(View.VISIBLE);
            SpannableStringBuilder sb = new SpannableStringBuilder();
            int pink = ContextCompat.getColor(h.tools.getContext(), R.color.spore_pink);
            for (int i = 0; i < m.tools.size(); i++) {
                if (i > 0) {
                    sb.append('\n');
                }
                int start = sb.length();
                sb.append('⌕').append(' ');
                sb.setSpan(new ForegroundColorSpan(pink), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(m.tools.get(i));
            }
            float d = h.tools.getResources().getDisplayMetrics().density;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(ContextCompat.getColor(h.tools.getContext(), R.color.spore_tool));
            bg.setCornerRadius(Math.round(7 * d));
            h.tools.setBackground(bg);
            int padH = Math.round(8 * d);
            int padV = Math.round(3 * d);
            h.tools.setPadding(padH, padV, padH, padV);
            h.tools.setText(sb, TextView.BufferType.SPANNABLE);
        }
    }

    /**
     * MV3 核实 chip 配色（只换颜色映射，判定条件与文案原样）：
     * 通过（OK）= ok 底/字，纠错（FIX）= danger_tint 底 / fix 字，跳过 = chip 底 muted2 字，
     * 其余（已核实/待核实/进行中）回落到布局里的 seglite 默认。
     * 每个分支都重设背景与字色——badge 视图会被回收复用，防止串色。
     */
    private void paintVerifyBadge(TextView badge, Session.Msg m) {
        float d = badge.getResources().getDisplayMetrics().density;
        int bg;
        int fg;
        if (m.verifyRan && "OK".equals(m.verifyVerdict)) {
            bg = ContextCompat.getColor(badge.getContext(), R.color.spore_ok_bg);
            fg = ContextCompat.getColor(badge.getContext(), R.color.spore_ok_text);
        } else if (m.verifyRan && "FIX".equals(m.verifyVerdict)) {
            bg = ContextCompat.getColor(badge.getContext(), R.color.spore_danger_tint);
            fg = ContextCompat.getColor(badge.getContext(), R.color.spore_fix_text);
        } else if (m.verifySkipped) {
            bg = ContextCompat.getColor(badge.getContext(), R.color.spore_chip);
            fg = ContextCompat.getColor(badge.getContext(), R.color.spore_muted2);
        } else {
            badge.setBackground(
                    ContextCompat.getDrawable(badge.getContext(), R.drawable.spore_seglite));
            int padH = Math.round(8 * d);
            int padV = Math.round(2 * d);
            badge.setPadding(padH, padV, padH, padV);
            badge.setTextColor(ContextCompat.getColor(badge.getContext(), R.color.spore_chip_text));
            return;
        }
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(bg);
        pill.setCornerRadius(999 * d);
        badge.setBackground(pill);
        int padH = Math.round(8 * d);
        int padV = Math.round(2 * d);
        badge.setPadding(padH, padV, padH, padV);
        badge.setTextColor(fg);
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

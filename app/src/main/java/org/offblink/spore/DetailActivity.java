package org.offblink.spore;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.offblink.spore.agent.Phases;
import org.offblink.spore.agent.Session;
import org.offblink.spore.agent.SessionStore;

import java.util.ArrayList;
import java.util.List;

import io.noties.markwon.Markwon;

/**
 * 记录详情（只读）：按会话消息顺序铺块——
 * user 行（截图 + 补充文本）、answer 行（题号/标题/答案走纯文本，解析走 Markwon，
 * 核实与检索小票折叠成小字）、chat 行（追问回答）。
 * 走查用，不做行内操作（追问/核实回面板做）。
 */
public class DetailActivity extends AppCompatActivity {

    public static final String EXTRA_SESSION_ID = "sessionId";

    private Markwon markwon;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        markwon = Markwon.builder(this).build();

        String id = getIntent().getStringExtra(EXTRA_SESSION_ID);
        Session s = SessionStore.load(this, id);
        if (s == null) {
            finish();
            return;
        }

        findViewById(R.id.detail_back).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.detail_title)).setText(s.title);

        LinearLayout msgs = findViewById(R.id.detail_msgs);
        for (Session.Msg m : s.messages) {
            View block = render(m);
            if (block != null) {
                msgs.addView(block);
            }
        }
    }

    private View render(Session.Msg m) {
        if ("user".equals(m.role)) {
            StringBuilder sb = new StringBuilder();
            if (m.hasImage) {
                sb.append("📷 题目截图");
            }
            if (!m.text.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(m.text);
            }
            if (sb.length() == 0) {
                return null;
            }
            return plain(sb.toString(), 14, false, 0xFF6B7280);
        }
        if ("answer".equals(m.kind)) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(0, dp(10), 0, dp(10));

            String head = Phases.formatAnswerPreview(m.no, m.ans, "");
            TextView headTv = plain(head.trim(), 15, true, 0xFF111827);
            box.addView(headTv);
            if (!m.title.isEmpty()) {
                box.addView(plain(m.title, 15, false, 0xFF374151));
            }
            if (!m.why.isEmpty()) {
                TextView why = new TextView(this);
                why.setTextSize(14);
                why.setPadding(0, dp(4), 0, 0);
                markwon.setMarkdown(why, m.why);
                box.addView(why);
            }
            List<String> foot = new ArrayList<>();
            if (m.verifyRan && !m.verifyVerdict.isEmpty()) {
                foot.add("核实：" + m.verifyVerdict
                        + (m.verifyNote.isEmpty() ? "" : " · " + m.verifyNote));
            }
            foot.addAll(m.tools);
            if (!foot.isEmpty()) {
                TextView small = plain(String.join("\n", foot), 12, false, 0xFF9CA3AF);
                small.setPadding(0, dp(6), 0, 0);
                box.addView(small);
            }
            return box;
        }
        // chat 回答
        if (m.text.isEmpty()) {
            return null;
        }
        return plain(m.text, 14, false, 0xFF111827);
    }

    private TextView plain(String text, int sp, boolean bold, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

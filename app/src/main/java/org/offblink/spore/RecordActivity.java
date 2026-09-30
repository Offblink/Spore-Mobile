package org.offblink.spore;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.offblink.spore.agent.Session;
import org.offblink.spore.agent.SessionStore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 搜题记录页（桌面抽屉列表的移动版）：会话行 = 标题 + 时间 + 星标收藏，
 * 顶栏「只看★」筛选，点行进详情。数据源 = SessionStore（回合结束落盘）。
 */
public class RecordActivity extends AppCompatActivity {

    private final List<Session> all = new ArrayList<>();
    private final List<Session> shown = new ArrayList<>();
    private boolean favOnly;
    private Adapter adapter;
    private TextView empty;
    private TextView filter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_record);

        findViewById(R.id.record_back).setOnClickListener(v -> finish());
        filter = findViewById(R.id.record_filter);
        empty = findViewById(R.id.record_empty);
        RecyclerView list = findViewById(R.id.record_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        list.setAdapter(adapter);

        filter.setOnClickListener(v -> {
            favOnly = !favOnly;
            filter.setText(favOnly ? R.string.record_filter_all : R.string.record_filter_fav);
            apply();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        all.clear();
        all.addAll(SessionStore.loadAll(this));
        apply();
    }

    /** 每次 onResume 全量重读：面板回合结束落盘后回本页即所见即所得 */
    private void apply() {
        shown.clear();
        for (Session s : all) {
            if (!favOnly || s.fav) {
                shown.add(s);
            }
        }
        adapter.notifyDataSetChanged();
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_session, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Session s = shown.get(position);
            holder.title.setText(s.title);
            holder.time.setText(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    .format(new Date(s.updated)));
            holder.fav.setText("★");
            holder.fav.setTextColor(s.fav ? 0xFFFFC107 : 0xFF9CA3AF);
            holder.fav.setOnClickListener(v -> {
                s.fav = !s.fav;
                SessionStore.save(RecordActivity.this, s);
                if (favOnly) {
                    apply(); // 收藏筛选开着时取消收藏 → 行消失，重算列表
                } else {
                    notifyItemChanged(holder.getBindingAdapterPosition());
                }
            });
            holder.itemView.setOnClickListener(v -> {
                Intent i = new Intent(RecordActivity.this, DetailActivity.class);
                i.putExtra(DetailActivity.EXTRA_SESSION_ID, s.id);
                startActivity(i);
            });
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView time;
            final TextView fav;

            VH(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.item_title);
                time = itemView.findViewById(R.id.item_time);
                fav = itemView.findViewById(R.id.item_fav);
            }
        }
    }
}

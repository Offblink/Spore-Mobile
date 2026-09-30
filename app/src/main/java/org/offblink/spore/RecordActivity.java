package org.offblink.spore;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
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
 * 搜题记录（MV3 列表语言）：行 = 标题 + 时间 + ✎ + ✕ + ★，
 * 右上 Switch 左「全部」右「收藏」；点行 = 打开面板**接着对话**（桌面点列表行语义）。
 * 改名/删除走 CaptureService 静态桥（防引擎内存态把文件盖回去）。
 */
public class RecordActivity extends AppCompatActivity {

    private final List<Session> all = new ArrayList<>();
    private final List<Session> shown = new ArrayList<>();
    private Adapter adapter;
    private TextView empty;
    private SwitchCompat scope;
    private RecyclerView list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_record);

        findViewById(R.id.record_back).setOnClickListener(v -> finish());
        scope = findViewById(R.id.record_switch);
        empty = findViewById(R.id.record_empty);
        list = findViewById(R.id.record_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setLayoutAnimation(AnimationUtils.loadLayoutAnimation(this, R.anim.row_layout));
        adapter = new Adapter();
        list.setAdapter(adapter);

        scope.setOnCheckedChangeListener((btn, isChecked) -> apply());
    }

    @Override
    protected void onResume() {
        super.onResume();
        all.clear();
        all.addAll(SessionStore.loadAll(this));
        apply();
        list.scheduleLayoutAnimation();
    }

    /** 全量重读 + 收藏筛选（面板回合结束落盘后回本页即所见即所得） */
    private void apply() {
        shown.clear();
        boolean favOnly = scope.isChecked();
        for (Session s : all) {
            if (!favOnly || s.fav) {
                shown.add(s);
            }
        }
        adapter.notifyDataSetChanged();
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void reload() {
        all.clear();
        all.addAll(SessionStore.loadAll(this));
        apply();
    }

    // ---------------------------------------------------------------- 弹窗（MV3 同构）

    private void showRename(Session s) {
        Dialog dlg = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        dlg.setContentView(R.layout.dialog_rename);
        styleDialog(dlg);
        TextView input = dlg.findViewById(R.id.rename_input);
        input.setText(s.title);
        dlg.findViewById(R.id.rename_no).setOnClickListener(v -> dlg.dismiss());
        dlg.findViewById(R.id.rename_yes).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()
                    && CaptureService.renameSession(this, s.id, name)) {
                reload();
            } else if (!name.isEmpty()) {
                toastBusy();
            }
            dlg.dismiss();
        });
        dlg.show();
        popIn(dlg, R.id.rename_input);
    }

    private void showConfirm(Session s) {
        Dialog dlg = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        dlg.setContentView(R.layout.dialog_confirm);
        styleDialog(dlg);
        TextView body = dlg.findViewById(R.id.confirm_body);
        body.setText(getString(R.string.delete_body, s.title));
        dlg.findViewById(R.id.confirm_no).setOnClickListener(v -> dlg.dismiss());
        dlg.findViewById(R.id.confirm_yes).setOnClickListener(v -> {
            if (!CaptureService.deleteSession(this, s.id)) {
                toastBusy();
            } else {
                reload();
            }
            dlg.dismiss();
        });
        dlg.show();
        popIn(dlg, R.id.confirm_body);
    }

    /** 透明窗 + 暗幕全交给布局里的 spore_dialog_overlay（避免系统 dim 叠双层） */
    private void styleDialog(Dialog dlg) {
        //noinspection deprecation
        dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dlg.getWindow().setDimAmount(0f);
    }

    /** 桌面 dialog pop .24s 同款：只弹内盒，暗幕不动 */
    private void popIn(Dialog dlg, int innerId) {
        View inner = ((View) dlg.findViewById(innerId).getParent());
        inner.setAlpha(0f);
        inner.setScaleX(0.94f);
        inner.setScaleY(0.94f);
        inner.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(240)
                .setInterpolator(AnimationUtils.loadInterpolator(
                        this, android.R.interpolator.fast_out_slow_in))
                .start();
    }

    private void toastBusy() {
        android.widget.Toast.makeText(this, R.string.busy_wait,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- 行

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
            paintFav(holder.fav, s.fav);

            // 点行 = 面板打开并接着对话
            holder.itemView.setOnClickListener(v -> {
                CaptureService.openSession(RecordActivity.this, s.id);
                finish();
                overridePendingTransition(R.anim.spore_fade_in, R.anim.spore_fade_out);
            });

            holder.fav.setOnClickListener(v -> {
                s.fav = !s.fav;
                SessionStore.save(RecordActivity.this, s);
                paintFav(holder.fav, s.fav);
                if (scope.isChecked()) {
                    apply(); // 收藏筛选开着时取消收藏 → 行消失
                }
            });
            holder.rename.setOnClickListener(v -> showRename(s));
            holder.delete.setOnClickListener(v -> showConfirm(s));
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView time;
            final TextView fav;
            final TextView rename;
            final TextView delete;

            VH(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.item_title);
                time = itemView.findViewById(R.id.item_time);
                fav = itemView.findViewById(R.id.item_fav);
                rename = itemView.findViewById(R.id.item_rename);
                delete = itemView.findViewById(R.id.item_delete);
            }
        }
    }

    private static void paintFav(TextView fav, boolean on) {
        fav.setTextColor(on ? 0xFFEC4899 : 0xFFB3B8CD);
    }
}

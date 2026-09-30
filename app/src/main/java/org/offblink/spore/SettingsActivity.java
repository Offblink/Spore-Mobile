package org.offblink.spore;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

/**
 * 设置页：读写 SporeSettings（端点/key/模型/检索代理/两阶段开关）。
 * 全部字段经 SporeSettings.load/save 落本机 SharedPreferences；
 * apiKey 红线同 SporeSettings——只活在本机，绝不进日志。
 * isConfigured() 的判定仍归 SporeSettings，这里不另造判据。
 */
public class SettingsActivity extends AppCompatActivity {

    private EditText etEndpoint;
    private EditText etModel;
    private EditText etApiKey;
    private EditText etProxy;
    private EditText etMaxToolRounds;
    private EditText etHistoryLimit;
    private SwitchCompat swFastNoThink;
    private SwitchCompat swAutoVerify;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etEndpoint = findViewById(R.id.et_endpoint);
        etModel = findViewById(R.id.et_model);
        etApiKey = findViewById(R.id.et_api_key);
        etProxy = findViewById(R.id.et_proxy);
        etMaxToolRounds = findViewById(R.id.et_max_tool_rounds);
        etHistoryLimit = findViewById(R.id.et_history_limit);
        swFastNoThink = findViewById(R.id.sw_fast_no_think);
        swAutoVerify = findViewById(R.id.sw_auto_verify);

        // 顶栏 ← 返回
        TextView tvBack = findViewById(R.id.tv_back);
        tvBack.setOnClickListener(v -> finish());

        // 用已存设置回填表单
        SporeSettings s = SporeSettings.load(this);
        etEndpoint.setText(s.endpoint);
        etModel.setText(s.model);
        etApiKey.setText(s.apiKey);
        etProxy.setText(s.proxy);
        etMaxToolRounds.setText(String.valueOf(s.maxToolRounds));
        etHistoryLimit.setText(String.valueOf(s.historyLimit));
        swFastNoThink.setChecked(s.fastNoThink);
        swAutoVerify.setChecked(s.autoVerify);

        Button btnSave = findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> save());
    }

    /** 把 8 个字段写回设置对象并落盘；apiKey 允许空着保存（未配置状态由调用方提示） */
    private void save() {
        SporeSettings s = SporeSettings.load(this);
        s.endpoint = etEndpoint.getText().toString().trim();
        s.model = etModel.getText().toString().trim();
        s.apiKey = etApiKey.getText().toString().trim();
        s.proxy = etProxy.getText().toString().trim();
        s.maxToolRounds = parseIntOr(etMaxToolRounds, s.maxToolRounds);
        s.historyLimit = parseIntOr(etHistoryLimit, s.historyLimit);
        s.fastNoThink = swFastNoThink.isChecked();
        s.autoVerify = swAutoVerify.isChecked();
        s.save(this);
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
    }

    /** 数字解析失败用兜底值（当前已存值，未改过即默认值），坏输入不崩溃 */
    private static int parseIntOr(EditText et, int fallback) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

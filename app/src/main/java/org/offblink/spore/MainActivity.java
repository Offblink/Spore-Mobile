package org.offblink.spore;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 搜题记录主页占位（handoff §2：整页记录 = 左列表 + 右历史 + 追问输入框）。
 * 当前只交付可编译脚手架，功能按 handoff §5 逐步堆叠。
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }
}

package org.offblink.spore;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 启动页：白底 + 应用图标 + 下方主题粉小字 Spore，停 1.2s 进主页。
 * 纯展示，无状态无桥；窗口底色与三边栏全白（Theme.Spore.Splash），
 * 系统 splash 的白底到本页无缝。切页走主题 SporeAnim 的默认平移。
 */
public class SplashActivity extends AppCompatActivity {

    private static final long SHOW_MS = 1200;

    private final Handler host = new Handler(Looper.getMainLooper());
    private final Runnable goMain = () -> {
        startActivity(new Intent(SplashActivity.this, MainActivity.class));
        finish();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        host.postDelayed(goMain, SHOW_MS);
    }

    @Override
    protected void onDestroy() {
        host.removeCallbacks(goMain); // 旋转/销毁不留悬空跳转
        super.onDestroy();
    }
}

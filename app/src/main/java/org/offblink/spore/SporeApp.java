package org.offblink.spore;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

/** 进程入口：崩溃留证必须在任何组件之前装上（第五轮崩溃取证）；主题档位在首帧前落到 AppCompat。 */
public class SporeApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        applyNightMode(this);
    }

    /**
     * 主题档位 → AppCompat 夜间模式：system 跟系统、light/dark 强制。
     * 应用级 Configuration 一改，所有 Activity 重建、values-night 接管原生配色；
     * 各页 WebView 的明暗由桥 {@code Spore.theme()} 自己再取一次（HTML 首帧前）。
     */
    public static void applyNightMode(android.content.Context c) {
        String t = SporeSettings.load(c).theme;
        int mode;
        if ("light".equals(t)) {
            mode = AppCompatDelegate.MODE_NIGHT_NO;
        } else if ("dark".equals(t)) {
            mode = AppCompatDelegate.MODE_NIGHT_YES;
        } else {
            mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
        AppCompatDelegate.setDefaultNightMode(mode);
    }
}

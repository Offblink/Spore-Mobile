package org.offblink.spore;

import android.app.Application;

/** 进程入口：崩溃留证必须在任何组件之前装上（第五轮崩溃取证）。 */
public class SporeApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
    }
}

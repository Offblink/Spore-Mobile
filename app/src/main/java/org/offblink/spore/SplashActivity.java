package org.offblink.spore;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 启动页（web 皮）：白底五字母字标，粉点从 S 外侧沿弧线飞到 e 外侧落地
 * （不碰字母，弹性水滴压扁回弹），点动画 1.1s；落地后底部小字打字出现 + 粉光标闪。
 * onPageFinished 后 2.2s 跳主页（打字 ~1.16s 打完 + 光标闪一轮）。
 * 纯展示无桥；窗口全白 + 31+ 系统 splash 图标已抹空（用户拍板：不要图标）。
 * 页面挂死有 5s 死线兜底，别让白屏卡死。
 */
public class SplashActivity extends AppCompatActivity {

    private static final long SHOW_MS = 2200;
    private static final long DEADLINE_MS = 5000;

    private final Handler host = new Handler(Looper.getMainLooper());
    private boolean jumped;
    private final Runnable goMain = () -> {
        if (jumped) {
            return;
        }
        jumped = true;
        startActivity(new Intent(SplashActivity.this, MainActivity.class));
        finish();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        WebView web = findViewById(R.id.web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        // 排错入口同记录页：chrome://inspect + console 落 logcat（tag spore-web）
        WebView.setWebContentsDebuggingEnabled(true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d("spore-web", "[splash] " + m.message() + " [" + m.sourceId() + ":" + m.lineNumber() + "]");
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                // 页面起来才计 1.5s（动画 1.4s + 0.1s 落定）；重排会顶掉死线兜底
                host.removeCallbacks(goMain);
                host.postDelayed(goMain, SHOW_MS);
            }
        });
        web.loadUrl("file:///android_asset/web/splash.html");
        host.postDelayed(goMain, DEADLINE_MS); // 死线：加载失败也绝不白屏卡死
    }

    @Override
    protected void onDestroy() {
        host.removeCallbacks(goMain);
        super.onDestroy();
    }
}

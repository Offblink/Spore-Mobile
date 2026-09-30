package org.offblink.spore;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/**
 * 透明中转页（**仅首次授权走这里**，投影常驻后点球不再启动本页——2026-09-30 用户实测：
 * 每次授权都会把前台应用挤回桌面、只截到桌面）：
 * 系统 MediaProjection 授权结果只能经 Activity 的 onActivityResult 拿回，
 * Service 收不到，所以由本页承载授权弹窗，把 resultCode/data 转交 CaptureService 后立即退出。
 * taskAffinity="" + excludeFromRecents：独立小任务，退完不留在最近任务，也不把主页顶到前台。
 */
public class CaptureConsentActivity extends Activity {

    private static final int REQ_CAPTURE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_CAPTURE) {
            Intent i = new Intent(this, CaptureService.class);
            i.setAction(CaptureService.ACTION_PROJECT);
            i.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
            i.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
            startService(i);
            finish();
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }
}

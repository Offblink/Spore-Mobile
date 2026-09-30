package org.offblink.spore.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import org.offblink.spore.R;

/**
 * 冻结帧框选层（桌面 overlay.js 的移动版）：
 * 深色遮罩 + 居中冻结帧 + 拖拽矩形；右上 ✕ 关闭。
 * 判定沿用桌面契约：MIN_W/MIN_H = 60/40（移动端换成 dp），框太小 toast 不放行。
 * 松手即截取（当前阶段=落盘；接作答链路后同一回调直接进两阶段）。
 */
public class CropOverlayView extends View {

    public interface Listener {
        /** 已按选择区裁出 Bitmap（调用在 UI 线程），由服务负责落盘与移除视图 */
        void onCropped(Bitmap crop);

        /** 用户点 ✕ 取消 */
        void onClose();
    }

    private final Bitmap frame;
    private final Listener listener;
    private final float minW, minH;

    private final Paint dimPaint = new Paint();
    private final Paint framePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint borderShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint closeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint closePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 冻结帧在本视图内的落位（fit-center）与缩放 */
    private final RectF dst = new RectF();
    private final RectF sel = new RectF();
    private final RectF closeRect = new RectF();
    private float scale = 1f;

    private boolean hasSel;
    private boolean dragging;
    private boolean closeHit;
    private float startX, startY;

    public CropOverlayView(Context context, Bitmap frame, Listener listener) {
        super(context);
        this.frame = frame;
        this.listener = listener;
        float density = context.getResources().getDisplayMetrics().density;
        minW = Math.round(60 * density);
        minH = Math.round(40 * density);

        dimPaint.setColor(0xA6000000);
        dimPaint.setStyle(Paint.Style.FILL);

        borderShadow.setColor(Color.BLACK);
        borderShadow.setStyle(Paint.Style.STROKE);
        borderShadow.setStrokeWidth(6 * density);

        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3 * density);

        hintBgPaint.setColor(0xCC1F2937);
        hintPaint.setColor(Color.WHITE);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setTextSize(15 * density);

        closeBgPaint.setColor(0xCC1F2937);
        closePaint.setColor(Color.WHITE);
        closePaint.setStyle(Paint.Style.STROKE);
        closePaint.setStrokeWidth(2.5f * density);
        closePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float bw = frame.getWidth();
        float bh = frame.getHeight();
        scale = Math.min(w / bw, h / bh);
        float offX = (w - bw * scale) / 2f;
        float offY = (h - bh * scale) / 2f;
        dst.set(offX, offY, offX + bw * scale, offY + bh * scale);

        float r = 22 * getResources().getDisplayMetrics().density;
        float cx = w - r - 12 * getResources().getDisplayMetrics().density;
        float cy = r + 12 * getResources().getDisplayMetrics().density;
        closeRect.set(cx - r, cy - r, cx + r, cy + r);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        canvas.drawRect(0, 0, w, h, dimPaint);
        canvas.drawBitmap(frame, null, dst, framePaint);

        if (hasSel) {
            canvas.drawRect(sel, borderShadow);
            canvas.drawRect(sel, borderPaint);
        } else if (!dragging) {
            // 无选择且未在拖动 → 居中提示
            String hint = getContext().getString(R.string.crop_hint);
            float tw = hintPaint.measureText(hint);
            float cx = w / 2f;
            float cy = h / 2f;
            float pad = 16 * getResources().getDisplayMetrics().density;
            float th = hintPaint.getTextSize();
            canvas.drawRoundRect(cx - tw / 2 - pad, cy - th - pad,
                    cx + tw / 2 + pad, cy + pad, pad, pad, hintBgPaint);
            canvas.drawText(hint, cx, cy - pad / 2, hintPaint);
        }

        // 右上关闭
        float cr = closeRect.width() / 2f;
        float ccx = closeRect.centerX();
        float ccy = closeRect.centerY();
        float arm = cr * 0.45f;
        canvas.drawCircle(ccx, ccy, cr, closeBgPaint);
        canvas.drawLine(ccx - arm, ccy - arm, ccx + arm, ccy + arm, closePaint);
        canvas.drawLine(ccx + arm, ccy - arm, ccx - arm, ccy + arm, closePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                closeHit = closeRect.contains(x, y);
                if (closeHit) {
                    return true;
                }
                dragging = true;
                hasSel = false;
                startX = x;
                startY = y;
                sel.set(x, y, x, y);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (closeHit) {
                    return true;
                }
                if (dragging) {
                    sel.set(Math.min(startX, x), Math.min(startY, y),
                            Math.max(startX, x), Math.max(startY, y));
                    clampSel();
                    hasSel = sel.width() > 4 && sel.height() > 4;
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (closeHit) {
                    closeHit = false;
                    if (closeRect.contains(x, y)) {
                        listener.onClose();
                    }
                    return true;
                }
                if (dragging) {
                    dragging = false;
                    if (hasSel) {
                        if (sel.width() < minW || sel.height() < minH) {
                            hasSel = false;
                            Toast.makeText(getContext(), R.string.crop_too_small,
                                    Toast.LENGTH_SHORT).show();
                            invalidate();
                            return true;
                        }
                        Bitmap crop = cropSelection();
                        if (crop != null) {
                            listener.onCropped(crop);
                            return true;
                        }
                        hasSel = false;
                    }
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                closeHit = false;
                hasSel = false;
                invalidate();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private void clampSel() {
        if (sel.left < dst.left) {
            sel.left = dst.left;
        }
        if (sel.top < dst.top) {
            sel.top = dst.top;
        }
        if (sel.right > dst.right) {
            sel.right = dst.right;
        }
        if (sel.bottom > dst.bottom) {
            sel.bottom = dst.bottom;
        }
    }

    /** 视图坐标 → 冻结帧像素坐标后裁剪 */
    private Bitmap cropSelection() {
        int x = Math.round((sel.left - dst.left) / scale);
        int y = Math.round((sel.top - dst.top) / scale);
        int cw = Math.round(sel.width() / scale);
        int ch = Math.round(sel.height() / scale);
        x = Math.max(0, Math.min(x, frame.getWidth() - 1));
        y = Math.max(0, Math.min(y, frame.getHeight() - 1));
        cw = Math.max(1, Math.min(cw, frame.getWidth() - x));
        ch = Math.max(1, Math.min(ch, frame.getHeight() - y));
        return Bitmap.createBitmap(frame, x, y, cw, ch);
    }
}

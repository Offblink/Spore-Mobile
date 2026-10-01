package org.offblink.spore.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import org.offblink.spore.R;

/**
 * 冻结帧框选层 —— MV3 桌面 overlay.js 的逐字复刻：
 * 55% 暗幕 + 2dp #EC4899 选框（10% 粉填充）、框上 W×H 粉色尺寸牌、
 * 顶部深色提示 pill（出错时转红 1.8s，同桌面 warn()）、底部粉色「搜」pill、右上 ✕。
 * 判定沿用桌面契约：宽高**都**小于下限才拒，有其一过线就放行。
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
    private final int minWdp, minHdp;
    private final float density;

    private final Paint dimPaint = new Paint();
    private final Paint framePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint selFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint closeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint closePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 冻结帧在本视图内的落位（fit-center）与缩放 */
    private final RectF dst = new RectF();
    private final RectF sel = new RectF();
    private final RectF closeRect = new RectF();
    private final RectF searchRect = new RectF();
    private final RectF searchHitRect = new RectF();
    private final RectF hintRect = new RectF();
    private float scale = 1f;

    private boolean hasSel;
    private boolean dragging;
    private boolean moving;
    private boolean closeHit;
    private boolean searchHit;
    private float startX, startY;
    private float lastX, lastY;
    /** 建议框先于布局到达时暂存（帧坐标），onSizeChanged 后回放 */
    private int[] pendingSuggestion;

    /** 顶部提示 pill 的默认文案与告警态复位（桌面 warn() 同款 1.8s） */
    private final Runnable warnReset = new Runnable() {
        @Override
        public void run() {
            hintText = getContext().getString(R.string.crop_hint);
            hintBgPaint.setColor(0xDB0C0C12);
            invalidate();
        }
    };
    private String hintText;

    public CropOverlayView(Context context, Bitmap frame, Listener listener) {
        super(context);
        this.frame = frame;
        this.listener = listener;
        density = context.getResources().getDisplayMetrics().density;
        minW = Math.round(60 * density);
        minH = Math.round(40 * density);
        minWdp = Math.round(minW / density);
        minHdp = Math.round(minH / density);
        hintText = context.getString(R.string.crop_hint);

        dimPaint.setColor(0x8C000000); // 桌面 box-shadow 环 = 55% 黑
        dimPaint.setStyle(Paint.Style.FILL);

        selFillPaint.setColor(0x1AEC4899); // rgba(236,72,153,.10)
        selFillPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(0xFFEC4899); // border:2px solid #ec4899
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2 * density);

        labelBgPaint.setColor(0xFFEC4899); // 尺寸牌：粉底 6px 圆角
        labelBgPaint.setStyle(Paint.Style.FILL);
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(12 * density);

        hintBgPaint.setColor(0xDB0C0C12); // rgba(12,13,18,.86)
        hintPaint.setColor(0xFFF5F6FC);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setTextSize(13 * density);

        btnPaint.setColor(0xFFEC4899); // 搜 pill = 品牌粉
        btnTextPaint.setColor(Color.WHITE);
        btnTextPaint.setTextAlign(Paint.Align.CENTER);
        btnTextPaint.setTextSize(15 * density);
        btnTextPaint.setFakeBoldText(true);

        closeBgPaint.setColor(0xDB0C0C12);
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

        float r = 22 * density;
        float cx = w - r - 12 * density;
        float cy = r + 12 * density;
        closeRect.set(cx - r, cy - r, cx + r, cy + r);

        float btnW = 110 * density;
        float btnH = 46 * density;
        searchRect.set((w - btnW) / 2f, h - btnH - 26 * density,
                (w + btnW) / 2f, h - 26 * density);
        searchHitRect.set(searchRect);
        searchHitRect.inset(-10 * density, -10 * density);

        if (pendingSuggestion != null) {
            int[] box = pendingSuggestion;
            pendingSuggestion = null;
            applySuggestion(box[0], box[1], box[2], box[3]);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        canvas.drawRect(0, 0, w, h, dimPaint);
        canvas.drawBitmap(frame, null, dst, framePaint);

        // 顶部提示 pill（常显；warn 态改红底，1.8s 复位）
        float hw = hintPaint.measureText(hintText);
        float hpad = 16 * density;
        float hleft = (w - hw) / 2 - hpad;
        float htop = 18 * density;
        hintRect.set(hleft, htop, hleft + hw + 2 * hpad, htop + 13 * density + 2 * 7 * density);
        canvas.drawRoundRect(hintRect, hintRect.height() / 2, hintRect.height() / 2, hintBgPaint);
        canvas.drawText(hintText, w / 2f, hintRect.centerY() - (hintPaint.descent() + hintPaint.ascent()) / 2f, hintPaint);

        if (hasSel) {
            canvas.drawRect(sel, selFillPaint);
            canvas.drawRect(sel, borderPaint);

            // W×H 尺寸牌（框上方，桌面 label 同款）
            String size = Math.round(sel.width() / scale) + " × " + Math.round(sel.height() / scale);
            float lw = labelPaint.measureText(size);
            float lpadH = 7 * density;
            float chipW = lw + 2 * lpadH;
            float chipH = 12 * density + 2 * 3 * density;
            float lx = Math.max(4 * density, Math.min(sel.left, w - chipW - 4 * density));
            float ly = Math.max(4 * density, sel.top - chipH - 4 * density);
            canvas.drawRoundRect(lx, ly, lx + chipW, ly + chipH, 6 * density, 6 * density, labelBgPaint);
            canvas.drawText(size, lx + chipW / 2, ly + chipH / 2 - (labelPaint.descent() + labelPaint.ascent()) / 2f, labelPaint);
        }

        // 「搜」确认 pill（有框且不在新手势中）
        if (hasSel && !dragging) {
            float radius = searchRect.height() / 2f;
            btnPaint.setColor(searchHit ? 0xFFDB2777 : 0xFFEC4899);
            canvas.drawRoundRect(searchRect, radius, radius, btnPaint);
            String confirm = getContext().getString(R.string.crop_confirm);
            float baseline = searchRect.centerY()
                    - (btnTextPaint.descent() + btnTextPaint.ascent()) / 2f;
            canvas.drawText(confirm, searchRect.centerX(), baseline, btnTextPaint);
        }

        // 右上 ✕
        float cr = closeRect.width() / 2f;
        float ccx = closeRect.centerX();
        float ccy = closeRect.centerY();
        float arm = cr * 0.45f;
        canvas.drawCircle(ccx, ccy, cr, closeBgPaint);
        canvas.drawLine(ccx - arm, ccy - arm, ccx + arm, ccy + arm, closePaint);
        canvas.drawLine(ccx + arm, ccy - arm, ccx - arm, ccy + arm, closePaint);
    }

    /** 桌面 warn()：顶部 pill 转红 1.8s 后复位（替代 toast，出错不打断） */
    private void warn(String text) {
        removeCallbacks(warnReset);
        hintText = text;
        hintBgPaint.setColor(0xF29E1C2C); // rgba(158,28,44,.95)
        invalidate();
        postDelayed(warnReset, 1800);
    }

    /**
     * §9.3 建议框入口：ML Kit 单框（冻结帧坐标）预填为选择框。
     * 已有选择/新手势进行中不覆盖；建议先于布局到达则暂存，onSizeChanged 后回放。
     */
    public void setSuggestion(int left, int top, int right, int bottom) {
        if (dragging || moving || hasSel) {
            return;
        }
        if (dst.isEmpty()) {
            pendingSuggestion = new int[]{left, top, right, bottom};
            return;
        }
        applySuggestion(left, top, right, bottom);
    }

    private void applySuggestion(int left, int top, int right, int bottom) {
        sel.set(dst.left + left * scale, dst.top + top * scale,
                dst.left + right * scale, dst.top + bottom * scale);
        clampSel();
        hasSel = sel.width() > 4 && sel.height() > 4;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (hasSel && !dragging && searchHitRect.contains(x, y)) {
                    searchHit = true;
                    invalidate();
                    return true;
                }
                closeHit = closeRect.contains(x, y);
                if (closeHit) {
                    return true;
                }
                if (hasSel) {
                    if (sel.contains(x, y)) {
                        moving = true;
                        lastX = x;
                        lastY = y;
                        return true;
                    }
                }
                dragging = true;
                hasSel = false;
                startX = x;
                startY = y;
                sel.set(x, y, x, y);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (closeHit || searchHit) {
                    return true;
                }
                if (moving) {
                    sel.offset(x - lastX, y - lastY);
                    lastX = x;
                    lastY = y;
                    clampSel();
                    invalidate();
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
                if (searchHit) {
                    boolean in = searchHitRect.contains(x, y);
                    searchHit = false;
                    if (in) {
                        confirmSelection();
                    } else {
                        invalidate();
                    }
                    return true;
                }
                if (closeHit) {
                    closeHit = false;
                    if (closeRect.contains(x, y)) {
                        listener.onClose();
                    }
                    return true;
                }
                if (moving) {
                    moving = false;
                    invalidate();
                    return true;
                }
                if (dragging) {
                    dragging = false;
                    if (!hasSel) {
                        // 点了一下没拖：桌面同款提示，重新拖
                        warn(getContext().getString(R.string.crop_warn_hold, minWdp, minHdp));
                        invalidate();
                        return true;
                    }
                    // 桌面契约：宽高都小于下限才拒（有其一过线放行）
                    if (sel.width() < minW && sel.height() < minH) {
                        int pw = Math.round(sel.width() / scale);
                        int ph = Math.round(sel.height() / scale);
                        hasSel = false;
                        warn(getContext().getString(R.string.crop_warn_small,
                                pw, ph, minWdp, minHdp));
                        invalidate();
                        return true;
                    }
                    confirmSelection();
                    return true;
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                moving = false;
                closeHit = false;
                searchHit = false;
                hasSel = false;
                invalidate();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    /** 按钮确认：太小只拦不清（用户拖大再来）；判定同手拖路径 */
    private void confirmSelection() {
        if (sel.width() < minW && sel.height() < minH) {
            int pw = Math.round(sel.width() / scale);
            int ph = Math.round(sel.height() / scale);
            warn(getContext().getString(R.string.crop_warn_small, pw, ph, minWdp, minHdp));
            return;
        }
        Bitmap crop = cropSelection();
        if (crop != null) {
            listener.onCropped(crop);
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

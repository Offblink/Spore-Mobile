package org.offblink.spore.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
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
 *
 * §9.3 建议框（2026-09-30 拍板）：ML Kit 出的单框经 {@link #setSuggestion} 预填为琥珀虚线框，
 * 点「搜」确认 / 拖角（4 圆角柄）微调 / 框内拖动整体平移；块外另起手势 = 重新手拖，
 * 松手即截取（桌面原样）。检测不到建议 → 全程退化为手动拖框。
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
    private final Paint sugBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint closeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint closePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 冻结帧在本视图内的落位（fit-center）与缩放 */
    private final RectF dst = new RectF();
    private final RectF sel = new RectF();
    private final RectF closeRect = new RectF();
    /** 「搜」确认按钮（画在底部中央）与其放大热区 */
    private final RectF searchRect = new RectF();
    private final RectF searchHitRect = new RectF();
    private float scale = 1f;
    private final float handleR;

    private boolean hasSel;
    private boolean dragging;
    private boolean resizing;
    private boolean moving;
    private boolean suggested;
    private boolean closeHit;
    private boolean searchHit;
    private int activeCorner;
    private float startX, startY;
    private float resizeStartX, resizeStartY, lastX, lastY;
    private final RectF resizeBase = new RectF();
    /** 建议框先于布局到达时暂存（帧坐标），onSizeChanged 后回放 */
    private int[] pendingSuggestion;

    public CropOverlayView(Context context, Bitmap frame, Listener listener) {
        super(context);
        this.frame = frame;
        this.listener = listener;
        float density = context.getResources().getDisplayMetrics().density;
        minW = Math.round(60 * density);
        minH = Math.round(40 * density);
        handleR = 11 * density;

        dimPaint.setColor(0xA6000000);
        dimPaint.setStyle(Paint.Style.FILL);

        borderShadow.setColor(Color.BLACK);
        borderShadow.setStyle(Paint.Style.STROKE);
        borderShadow.setStrokeWidth(6 * density);

        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3 * density);

        // 建议框 = 琥珀虚线，和「可调后确认」的手拖框区分开
        sugBorderPaint.setColor(0xFFFFC107);
        sugBorderPaint.setStyle(Paint.Style.STROKE);
        sugBorderPaint.setStrokeWidth(3 * density);
        sugBorderPaint.setPathEffect(new DashPathEffect(new float[]{12 * density, 8 * density}, 0f));

        handleFillPaint.setColor(Color.WHITE);
        handleStrokePaint.setColor(0xFF111827);
        handleStrokePaint.setStyle(Paint.Style.STROKE);
        handleStrokePaint.setStrokeWidth(2 * density);

        btnBgPaint.setColor(0xF0FF8F00);
        btnTextPaint.setColor(Color.WHITE);
        btnTextPaint.setTextAlign(Paint.Align.CENTER);
        btnTextPaint.setTextSize(17 * density);
        btnTextPaint.setFakeBoldText(true);

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

        float density = getResources().getDisplayMetrics().density;
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

        if (hasSel) {
            canvas.drawRect(sel, borderShadow);
            if (suggested) {
                canvas.drawRect(sel, sugBorderPaint);
            } else {
                canvas.drawRect(sel, borderPaint);
            }
            if (!dragging) {
                drawHandle(canvas, sel.left, sel.top);
                drawHandle(canvas, sel.right, sel.top);
                drawHandle(canvas, sel.right, sel.bottom);
                drawHandle(canvas, sel.left, sel.bottom);
            }
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

        // 「搜」确认（有框且不在新手势中才显示）
        if (hasSel && !dragging) {
            canvas.drawRoundRect(searchRect,
                    searchRect.height() / 2f, searchRect.height() / 2f, btnBgPaint);
            String confirm = getContext().getString(R.string.crop_confirm);
            float baseline = searchRect.centerY()
                    - (btnTextPaint.descent() + btnTextPaint.ascent()) / 2f;
            canvas.drawText(confirm, searchRect.centerX(), baseline, btnTextPaint);
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

    private void drawHandle(Canvas canvas, float x, float y) {
        canvas.drawCircle(x, y, handleR, handleFillPaint);
        canvas.drawCircle(x, y, handleR, handleStrokePaint);
    }

    /**
     * §9.3 建议框入口：ML Kit 单框（冻结帧坐标）预填为选择框。
     * 已有选择/新手势进行中不覆盖；建议先于布局到达则暂存，onSizeChanged 后回放。
     */
    public void setSuggestion(int left, int top, int right, int bottom) {
        if (dragging || resizing || moving || hasSel) {
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
        suggested = hasSel;
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
                    int corner = cornerAt(x, y);
                    if (corner >= 0) {
                        resizing = true;
                        activeCorner = corner;
                        resizeStartX = x;
                        resizeStartY = y;
                        resizeBase.set(sel);
                        suggested = false;
                        return true;
                    }
                    if (sel.contains(x, y)) {
                        moving = true;
                        lastX = x;
                        lastY = y;
                        suggested = false;
                        return true;
                    }
                }
                dragging = true;
                hasSel = false;
                suggested = false;
                startX = x;
                startY = y;
                sel.set(x, y, x, y);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (closeHit || searchHit) {
                    return true;
                }
                if (resizing) {
                    float dx = x - resizeStartX;
                    float dy = y - resizeStartY;
                    sel.set(resizeBase);
                    switch (activeCorner) {
                        case 0: // 左上
                            sel.left += dx;
                            sel.top += dy;
                            break;
                        case 1: // 右上
                            sel.right += dx;
                            sel.top += dy;
                            break;
                        case 2: // 右下
                            sel.right += dx;
                            sel.bottom += dy;
                            break;
                        default: // 左下
                            sel.left += dx;
                            sel.bottom += dy;
                            break;
                    }
                    // 防翻转：越过对边就钉在对边内 4px
                    if (sel.left > sel.right - 4) {
                        sel.left = sel.right - 4;
                    }
                    if (sel.top > sel.bottom - 4) {
                        sel.top = sel.bottom - 4;
                    }
                    clampSel();
                    hasSel = sel.width() > 4 && sel.height() > 4;
                    invalidate();
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
                    searchHit = false;
                    if (searchHitRect.contains(x, y)) {
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
                if (resizing || moving) {
                    resizing = false;
                    moving = false;
                    invalidate();
                    return true;
                }
                if (dragging) {
                    dragging = false;
                    if (hasSel) {
                        // 桌面契约（design.md）：宽高**都**小于下限才拒，有其一过线就放行
                        if (sel.width() < minW && sel.height() < minH) {
                            hasSel = false;
                            Toast.makeText(getContext(), R.string.crop_too_small,
                                    Toast.LENGTH_SHORT).show();
                            invalidate();
                            return true;
                        }
                        confirmSelection();
                        return true;
                    }
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                resizing = false;
                moving = false;
                closeHit = false;
                searchHit = false;
                hasSel = false;
                suggested = false;
                invalidate();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    /** 按钮确认：太小只拦不清（用户拖大再来）；与手拖松手路径的「清框重来」刻意不同。
     *  判定同手拖路径（桌面契约）：都小于下限才拒 */
    private void confirmSelection() {
        if (sel.width() < minW && sel.height() < minH) {
            Toast.makeText(getContext(), R.string.crop_too_small, Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap crop = cropSelection();
        if (crop != null) {
            listener.onCropped(crop);
        }
    }

    /** 命中角柄（26dp 半径）→ 0=左上 1=右上 2=右下 3=左下，否则 -1 */
    private int cornerAt(float x, float y) {
        if (dist(x, y, sel.left, sel.top) <= handleR + 8 * getResources().getDisplayMetrics().density) {
            return 0;
        }
        if (dist(x, y, sel.right, sel.top) <= handleR + 8 * getResources().getDisplayMetrics().density) {
            return 1;
        }
        if (dist(x, y, sel.right, sel.bottom) <= handleR + 8 * getResources().getDisplayMetrics().density) {
            return 2;
        }
        if (dist(x, y, sel.left, sel.bottom) <= handleR + 8 * getResources().getDisplayMetrics().density) {
            return 3;
        }
        return -1;
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
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

package org.offblink.spore.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 应用内的拍照框选层（拍照搜题专用，不走悬浮窗那套 {@code CropOverlayView}）：
 * 冻结照片 fit-center + 拖拽框选（可挪动 / 角把手改大小）+ W×H 尺寸牌。
 * 旋转与确认都不在这个视图里：旋转是顶栏键（{@link #rotate()}），确认是底栏「确定」
 * （{@link #takeCrop()}）—— 与截屏路径的「松手即搜」完全不同口径。
 */
public class PhotoCropView extends View {

    private Bitmap frame;
    private final float density;
    private final float minW, minH;

    private final Paint framePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint selFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF dst = new RectF();
    private final RectF sel = new RectF();
    private float scale = 1f;

    private boolean hasSel;
    private boolean dragging;
    private boolean moving;
    private static final int H_NONE = 0, H_TL = 1, H_TR = 2, H_BL = 3, H_BR = 4;
    private int handle = H_NONE;
    private boolean resizing;
    private float anchorX, anchorY;
    private float startX, startY, lastX, lastY;

    public PhotoCropView(Context context, Bitmap frame) {
        super(context);
        this.frame = frame;
        density = context.getResources().getDisplayMetrics().density;
        minW = Math.round(60 * density);
        minH = Math.round(40 * density);

        selFillPaint.setColor(0x1AEC4899);
        selFillPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(0xFFEC4899);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2 * density);

        labelBgPaint.setColor(0xFFEC4899);
        labelBgPaint.setStyle(Paint.Style.FILL);
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(12 * density);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitFrame();
    }

    private void fitFrame() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || frame == null) {
            return;
        }
        float bw = frame.getWidth();
        float bh = frame.getHeight();
        scale = Math.min(w / bw, h / bh);
        float offX = (w - bw * scale) / 2f;
        float offY = (h - bh * scale) / 2f;
        dst.set(offX, offY, offX + bw * scale, offY + bh * scale);
        hasSel = false;
    }

    /** 顶栏旋转键（顺时针 90°）：换帧 → 重排 → 选区作废（坐标系变了，让用户重拖） */
    public void rotate() {
        if (frame == null || frame.isRecycled()) {
            return;
        }
        Matrix m = new Matrix();
        m.postRotate(90);
        Bitmap rot = Bitmap.createBitmap(frame, 0, 0,
                frame.getWidth(), frame.getHeight(), m, true);
        if (rot == frame) {
            return;
        }
        // 旧帧不 recycle：可能还有人拿着引用，交给 GC
        frame = rot;
        fitFrame();
        invalidate();
    }

    /** 底栏「确定」：返回选区裁剪；没框/太小回 null（调用方提示） */
    public Bitmap takeCrop() {
        if (!hasSel || frame == null) {
            return null;
        }
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

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(frame, null, dst, framePaint);

        if (hasSel) {
            // 选区外的暗幕：只在照片内圈暗，不压黑边（黑边是 letterbox，无所谓）
            canvas.save();
            android.graphics.Path holePath = PathClip.make(dst, sel);
            if (holePath != null) {
                canvas.clipPath(holePath);
            }
            canvas.drawColor(0x8C000000);
            canvas.restore();

            canvas.drawRect(sel, selFillPaint);
            canvas.drawRect(sel, borderPaint);

            String size = Math.round(sel.width() / scale) + " × " + Math.round(sel.height() / scale);
            float lw = labelPaint.measureText(size);
            float lpadH = 7 * density;
            float chipW = lw + 2 * lpadH;
            float chipH = 12 * density + 2 * 3 * density;
            float lx = Math.max(4 * density, Math.min(sel.left, w - chipW - 4 * density));
            float ly = Math.max(4 * density, sel.top - chipH - 4 * density);
            canvas.drawRoundRect(lx, ly, lx + chipW, ly + chipH, 6 * density, 6 * density, labelBgPaint);
            canvas.drawText(size, lx + chipW / 2,
                    ly + chipH / 2 - (labelPaint.descent() + labelPaint.ascent()) / 2f, labelPaint);
        }
    }

    /** 照片区域减去选区的路径（选区外暗幕）；PathClip 只是为了少一层嵌套类名 */
    private static final class PathClip {
        static android.graphics.Path make(RectF photo, RectF sel) {
            if (photo.width() <= 0 || photo.height() <= 0) {
                return null;
            }
            android.graphics.Path p = new android.graphics.Path();
            p.addRect(photo, android.graphics.Path.Direction.CW);
            android.graphics.Path hole = new android.graphics.Path();
            hole.addRect(sel, android.graphics.Path.Direction.CCW);
            p.op(hole, android.graphics.Path.Op.DIFFERENCE);
            return p;
        }
    }

    private int cornerHit(float x, float y) {
        if (!hasSel) {
            return H_NONE;
        }
        float hit = 24 * density;
        float in = Math.min(hit, Math.min(sel.width(), sel.height()) * 0.25f);
        boolean x0 = x >= sel.left - hit && x <= sel.left + in;
        boolean x1 = x >= sel.right - in && x <= sel.right + hit;
        boolean y0 = y >= sel.top - hit && y <= sel.top + in;
        boolean y1 = y >= sel.bottom - in && y <= sel.bottom + hit;
        if (x0 && y0) {
            return H_TL;
        }
        if (x1 && y0) {
            return H_TR;
        }
        if (x0 && y1) {
            return H_BL;
        }
        if (x1 && y1) {
            return H_BR;
        }
        return H_NONE;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (hasSel) {
                    int h = cornerHit(x, y);
                    if (h != H_NONE) {
                        handle = h;
                        resizing = true;
                        anchorX = (h == H_TL || h == H_BL) ? sel.right : sel.left;
                        anchorY = (h == H_TL || h == H_TR) ? sel.bottom : sel.top;
                        return true;
                    }
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
                if (moving) {
                    sel.offset(x - lastX, y - lastY);
                    lastX = x;
                    lastY = y;
                    clampSel();
                    invalidate();
                    return true;
                }
                if (resizing) {
                    sel.set(Math.min(anchorX, x), Math.min(anchorY, y),
                            Math.max(anchorX, x), Math.max(anchorY, y));
                    clampSel();
                    hasSel = sel.width() > 4 && sel.height() > 4;
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
                if (resizing) {
                    resizing = false;
                    handle = H_NONE;
                    invalidate();
                    return true;
                }
                if (moving) {
                    moving = false;
                    invalidate();
                    return true;
                }
                if (dragging) {
                    dragging = false;
                    // 松手不确认：确认只属于底栏「确定」（与截屏的松手即搜不同口径）
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                moving = false;
                resizing = false;
                handle = H_NONE;
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

    /** 底栏确定前的最小框判定（与截屏口径一致：宽高都小于下限才拒） */
    public boolean selectionTooSmall() {
        return !hasSel || sel.width() < minW || sel.height() < minH;
    }
}

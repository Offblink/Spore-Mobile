package org.offblink.spore.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 悬浮把手（MV3 桌面 #toggle 半圆小角的移动复刻）：
 * 22×46 半圆贴边、深粉→品牌粉渐变、粉色光晕；平边永远贴屏幕边（setSide 换向）。
 * 手势与窗口参数分离：本 View 只报「手势起点 / 总位移 / 点按」，参数更新全在 CaptureService。
 * 点按 = 截屏；长按 = 面板开/收（handoff §9.1/§9 补充拍板）。
 */
public class BallView extends View {

    /** 把手可视尺寸（MV3 原值 22×46）与四周光晕留白 */
    public static final int HANDLE_W_DP = 22;
    public static final int HANDLE_H_DP = 46;
    public static final int GLOW_DP = 8;

    public interface Listener {
        /** 未拖动的点按（未超过 touchSlop 且时长正常）→ 截屏 */
        void onTap();

        /** 长按 → 面板开/收切换（收起后唯一的唤回入口，handoff §9） */
        void onLongPress();

        /** 拖动开始：服务缓存当前窗口参数作基点 */
        void onGestureStart();

        /** 拖动中：相对手势起点的总位移 */
        void onGestureMove(int totalDx, int totalDy);

        /** 拖动结束：服务执行吸边 */
        void onGestureEnd();
    }

    private final Listener listener;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path handlePath = new Path();
    private final android.graphics.RectF knobRect = new android.graphics.RectF();
    /** 平边朝右（贴右缘）/ 朝左（贴左缘）的圆角序列（TL,TR,BR,BL × xy） */
    private final float[] radiiRight = {0, 0, 0, 0, 0, 0, 0, 0};
    private final float[] radiiLeft = {0, 0, 0, 0, 0, 0, 0, 0};
    private final int wPx;
    private final int hPx;
    private final int glowPx;
    private final int radiusPx;
    private final int touchSlop;

    /** 贴在右缘（false）还是左缘（true）：决定平边朝向 */
    private boolean sideLeft;
    private float downRawX, downRawY;
    private long downTime;
    private boolean dragging;
    private boolean longFired;
    /** 长按判定与 tap 互斥：postDelayed 到 longPressTimeout，抬起即取消 */
    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!dragging) {
                longFired = true;
                listener.onLongPress();
            }
        }
    };

    public BallView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        float density = context.getResources().getDisplayMetrics().density;
        glowPx = Math.round(GLOW_DP * density);
        wPx = Math.round((HANDLE_W_DP + 2 * GLOW_DP) * density);
        hPx = Math.round((HANDLE_H_DP + 2 * GLOW_DP) * density);
        radiusPx = Math.round(HANDLE_H_DP / 2f * density);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        // 光晕要出界外 → 软件层 shadowLayer
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setShadowLayer(glowPx * 1.5f, 0, 0, 0x52EC4899);
        // 渐变一次成形（尺寸构造期已知，避免每次 onDraw 分配）
        bgPaint.setShader(new LinearGradient(0, glowPx, 0, hPx - glowPx,
                0xFFDB2777, 0xFFEC4899, Shader.TileMode.CLAMP));
    }

    /** 吸边后由服务侧告知贴哪条边：平边贴边、圆弧朝屏内 */
    public void setSide(boolean left) {
        if (sideLeft != left) {
            sideLeft = left;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(wPx, hPx);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        handlePath.reset();
        knobRect.set(glowPx, glowPx, wPx - glowPx, hPx - glowPx);
        float[] radii;
        if (sideLeft) {
            // 贴左缘：平边朝左（贴屏），圆弧朝右（进屏）
            radiiLeft[0] = 0;
            radiiLeft[1] = 0;
            radiiLeft[2] = radiusPx;
            radiiLeft[3] = radiusPx;
            radiiLeft[4] = radiusPx;
            radiiLeft[5] = radiusPx;
            radiiLeft[6] = 0;
            radiiLeft[7] = 0;
            radii = radiiLeft;
        } else {
            // 贴右缘：平边朝右（贴屏），圆弧朝左（进屏）
            radiiRight[0] = radiusPx;
            radiiRight[1] = radiusPx;
            radiiRight[6] = radiusPx;
            radiiRight[7] = radiusPx;
            radii = radiiRight;
        }
        handlePath.addRoundRect(knobRect, radii, Path.Direction.CW);
        canvas.drawPath(handlePath, bgPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downTime = System.currentTimeMillis();
                dragging = false;
                longFired = false;
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                return true;

            case MotionEvent.ACTION_MOVE: {
                int dx = (int) (event.getRawX() - downRawX);
                int dy = (int) (event.getRawY() - downRawY);
                if (!dragging && !longFired && Math.hypot(dx, dy) > touchSlop) {
                    dragging = true;
                    removeCallbacks(longPressRunnable);
                    listener.onGestureStart();
                }
                if (dragging) {
                    listener.onGestureMove(dx, dy);
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                removeCallbacks(longPressRunnable);
                if (dragging) {
                    listener.onGestureEnd();
                } else if (longFired) {
                    longFired = false;
                } else if (System.currentTimeMillis() - downTime < 400) {
                    listener.onTap();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressRunnable);
                if (dragging) {
                    listener.onGestureEnd();
                }
                dragging = false;
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }
}

package org.offblink.spore.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 悬浮球（自绘）：拖动移动，松手由服务侧吸边；点按 = 直接截屏（handoff §9.1，球上无菜单）。
 * 手势与窗口参数分离：本 View 只报「手势起点 / 总位移 / 点按」，参数更新全在 CaptureService。
 */
public class BallView extends View {

    public interface Listener {
        /** 未拖动的点按（未超过 touchSlop 且时长正常）→ 截屏 */
        void onTap();

        /** 拖动开始：服务缓存当前窗口参数作基点 */
        void onGestureStart();

        /** 拖动中：相对手势起点的总位移 */
        void onGestureMove(int totalDx, int totalDy);

        /** 拖动结束：服务执行吸边 */
        void onGestureEnd();
    }

    private final Listener listener;
    private final Paint bgPaint;
    private final Paint textPaint;
    private final int sizePx;
    private final int touchSlop;

    private float downRawX, downRawY;
    private long downTime;
    private boolean dragging;

    public BallView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        float density = context.getResources().getDisplayMetrics().density;
        sizePx = Math.round(56 * density);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(0xE61F2937);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(22 * context.getResources().getDisplayMetrics().scaledDensity);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(sizePx, sizePx);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = sizePx / 2f;
        canvas.drawCircle(r, r, r - 1, bgPaint);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float ty = (sizePx - (fm.ascent + fm.descent)) / 2f;
        canvas.drawText("S", r, ty, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downTime = System.currentTimeMillis();
                dragging = false;
                return true;

            case MotionEvent.ACTION_MOVE: {
                int dx = (int) (event.getRawX() - downRawX);
                int dy = (int) (event.getRawY() - downRawY);
                if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                    dragging = true;
                    listener.onGestureStart();
                }
                if (dragging) {
                    listener.onGestureMove(dx, dy);
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                if (dragging) {
                    listener.onGestureEnd();
                } else if (System.currentTimeMillis() - downTime < 400) {
                    listener.onTap();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    listener.onGestureEnd();
                }
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }
}

package org.offblink.spore.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 悬浮球：圆形 + 白色放大镜（第八轮从 🔍 emoji 换回手绘并修对齐：
 * 镜圈圆心与手柄末端关于球心沿对角对称、手柄起点正压镜圈圆周）。
 * 48dp 圆球居中、深粉→品牌粉渐变、粉色光晕；无方向性（对称，贴边朝向无视觉差异）。
 * 手势与窗口参数分离：本 View 只报「手势起点 / 总位移 / 点按 / 长按」，参数更新全在 CaptureService。
 * 点按 = 截屏；长按 = 面板开/关（会话列表由面板内 💬 手动弹，不再连带）。
 */
public class BallView extends View {

    /** 圆球直径（不含光晕）；窗口 = 球 + 2×光晕 */
    public static final int BALL_DP = 48;
    /** 四周光晕留白 */
    public static final int GLOW_DP = 8;

    public interface Listener {
        /** 手指刚按下（还没判定是点按/长按/拖动）→ 服务侧可开始预取帧，抬起成点按时直接用 */
        void onPress();

        /** 未拖动的点按（未超过 touchSlop 且时长正常）→ 截屏 */
        void onTap();

        /** 长按 → 会话抽屉开/关（类 MV3 alt+z；收起后唯一的唤回入口，handoff §9） */
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
    /** 放大镜（镜圈 + 对角手柄）白色描边漆，构造期定形避免 onDraw 分配 */
    private final Paint lensPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int wPx;
    private final int hPx;
    private final int glowPx;
    private final int touchSlop;

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
        wPx = Math.round((BALL_DP + 2 * GLOW_DP) * density);
        hPx = wPx;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        // 光晕要出界外 → 软件层 shadowLayer
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setShadowLayer(glowPx * 1.5f, 0, 0, 0x52EC4899);
        // 渐变一次成形（尺寸构造期已知，避免每次 onDraw 分配）
        bgPaint.setShader(new LinearGradient(0, glowPx, 0, hPx - glowPx,
                0xFFDB2777, 0xFFEC4899, Shader.TileMode.CLAMP));
        lensPaint.setStyle(Paint.Style.STROKE);
        lensPaint.setStrokeCap(Paint.Cap.ROUND);
        lensPaint.setStrokeWidth(Math.max(2f, density * 2.6f));
        lensPaint.setColor(0xFFFFFFFF);
    }

    /** 圆球对称，朝向无视觉差异；服务侧吸边仍回调（接口兼容，无视觉效果） */
    public void setSide(boolean left) {
        // no-op
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(wPx, hPx);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = wPx / 2f;
        float cy = hPx / 2f;
        float r = Math.min(wPx, hPx) / 2f - glowPx;
        // 圆形本体（渐变 + 光晕由 bgPaint 的 shader/shadowLayer 自带）
        canvas.drawCircle(cx, cy, r, bgPaint);
        // 白色放大镜（对齐修法）：沿对角单位向量 d 布置——镜圈圆心 = 球心 - g·d，
        // 手柄从镜圈圆周（lensR·d）画到 lensR·lenK·d；g = lensR·(lenK-1)/2
        // 使镜圈后沿与手柄端点关于球心对称 → 图标不偏心、手柄正压圆周（修「没对齐」）
        final float d = 0.70710678f;
        final float lensR = r * 0.40f;
        final float lenK = 1.8f;
        float g = lensR * (lenK - 1f) / 2f;
        float lcX = cx - g * d;
        float lcY = cy - g * d;
        canvas.drawCircle(lcX, lcY, lensR, lensPaint);
        canvas.drawLine(lcX + lensR * d, lcY + lensR * d,
                lcX + lensR * lenK * d, lcY + lensR * lenK * d,
                lensPaint);
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
                listener.onPress(); // 抬起成点按时才有得用：建 VD + 等首帧这段先跑起来
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

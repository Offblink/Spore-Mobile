package org.offblink.spore;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 无障碍读屏：从当前活动窗口的节点树里取文本。
 *
 * 为什么不是截屏：对方应用开了 {@code FLAG_SECURE} 时，MediaProjection / screencap 只能拿到
 * 黑帧（见 {@link FrameQuality} 的 blocked 判定），但 FLAG_SECURE 只作用于**合成后的像素**，
 * 节点树里的文本属于另一半数据（无障碍读的是 View 内容，不是渲染结果）。
 * 所以「安全窗口」下的题目文本在这里仍然拿得到——这是本服务存在的唯一理由。
 *
 * 现阶段只做最小闭环：读到 → 写 {@link SporeLog}（设置 → 运行日志可见）+ logcat，
 * 并把最近一次结果留在静态字段里，供后续搜题流水线按需取用。
 * 红线同 SporeLog：绝不把 token/apiKey 之类的窗口内容落盘；本类只登记「读到了多少字」，
 * 正文截断进日志，避免把用户屏幕上的隐私整段抄进日志文件。
 */
public class A11yTextService extends AccessibilityService {

    private static final String TAG = "SporeA11y";
    /** 内容变化类事件很密：两次真实取值至少隔这么久。 */
    private static final long MIN_INTERVAL_MS = 250L;
    /** 单次遍历的节点上限，防长列表/WebView 把主线程拖死。 */
    private static final int MAX_NODES = 1500;
    /** 文本总长上限（字符）。 */
    private static final int MAX_CHARS = 8000;
    /** 写日志的正文前缀长度：够判读，又不至于把整屏隐私抄进日志。 */
    private static final int LOG_CHARS = 160;

    private static volatile A11yTextService instance;
    private static volatile String lastPackage = "";
    private static volatile String lastText = "";

    private long lastReadAt;
    private String lastLogged;

    /** 服务是否已被用户在系统设置里启用（未启用 = 读不到任何窗口）。 */
    public static boolean isConnected() {
        return instance != null;
    }

    /** 最近一次读到的文本（可能来自任意应用的最后一次窗口事件）。 */
    public static String lastText() {
        return lastText;
    }

    public static String lastPackage() {
        return lastPackage;
    }

    /**
     * 立即读一次当前活动窗口的文本；服务未连接或窗口不可读时返回空串。
     * 后续搜题流水线从这里取文本，替代 OCR 那一段。
     */
    public static String readNow() {
        A11yTextService svc = instance;
        if (svc == null) {
            return "";
        }
        String text = svc.capture(svc.getRootInActiveWindow());
        lastText = text;
        return text;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        SporeLog.i(this, "a11y 服务已连接");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        SporeLog.i(this, "a11y 服务已断开");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    /** 系统中断（服务被临时停用/反馈被打断）：只需保证不持有残留状态。 */
    @Override
    public void onInterrupt() {
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        String name = pkg == null ? "" : pkg.toString();
        // 自己的界面不读：面板/记录页的内容本来就是 Spore 自己写的，读它没有意义还会刷屏。
        if (getPackageName().equals(name)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReadAt < MIN_INTERVAL_MS) {
            return;
        }
        lastReadAt = now;
        captureAndLog(name);
    }

    private void captureAndLog(String pkg) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String text = capture(root);
        lastPackage = pkg;
        lastText = text;
        if (text.isEmpty()) {
            // 读不到也要留痕：这是「假设被证伪」时唯一的一手证据（root 为 null / 无文本节点）。
            String why = root == null ? "root=null" : "text-empty";
            String line = "a11y pkg=" + pkg + " " + why;
            if (!line.equals(lastLogged)) {
                lastLogged = line;
                SporeLog.i(this, line);
                android.util.Log.i(TAG, line);
            }
            return;
        }
        String brief = text.length() > LOG_CHARS ? text.substring(0, LOG_CHARS) + "…" : text;
        String line = "a11y pkg=" + pkg + " chars=" + text.length() + " | "
                + brief.replace('\n', ' ');
        if (line.equals(lastLogged)) {
            return;
        }
        lastLogged = line;
        SporeLog.i(this, line);
        android.util.Log.i(TAG, line);
    }

    /** 遍历节点树，按出现顺序收集 text / contentDescription（去重、限长）。 */
    private String capture(AccessibilityNodeInfo root) {
        if (root == null) {
            return "";
        }
        Set<String> lines = new LinkedHashSet<>();
        int[] budget = {MAX_NODES, MAX_CHARS};
        walk(root, lines, budget);
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            if (sb.length() + s.length() + 1 > MAX_CHARS) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private void walk(AccessibilityNodeInfo node, Set<String> out, int[] budget) {
        if (node == null || budget[0] <= 0 || budget[1] <= 0) {
            return;
        }
        budget[0]--;
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            add(out, text.toString().trim(), budget);
        }
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            add(out, desc.toString().trim(), budget);
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            if (budget[0] <= 0 || budget[1] <= 0) {
                return;
            }
            AccessibilityNodeInfo child;
            try {
                child = node.getChild(i);
            } catch (Throwable t) {
                continue; // 节点在遍历途中失效（窗口销毁）：跳过这一支
            }
            walk(child, out, budget);
        }
    }

    private static void add(Set<String> out, String s, int[] budget) {
        if (s.isEmpty()) {
            return;
        }
        if (out.add(s)) {
            budget[1] -= s.length() + 1;
        }
    }
}

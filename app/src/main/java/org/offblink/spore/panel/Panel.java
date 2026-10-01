package org.offblink.spore.panel;

import org.offblink.spore.agent.AgentEngine;

import java.io.File;

/**
 * 面板契约（第五轮分叉）：CaptureService 只认这 6 个方法 + 引擎事件。
 * 默认走 WebView 面板；设置或设备判定（华为/鸿蒙）为原生时走 NativeAnswerPanel
 * （round-3 原生、实测零 WebView 崩溃）。两皮同事件源，记录页桥全走引擎、互不感知。
 */
public interface Panel extends AgentEngine.Listener {

    /** 截图落盘后：开面板 + 起两阶段回合 */
    void openWithCapture(File crop);

    /** 球长按 = 面板开/关（第八轮拍板：长按只开面板，会话列表由面板内 💬 手动弹） */
    void toggle();

    void show();

    void close();

    /** 服务销毁：WebView 是重对象必须显式销毁；原生同名空转到 close */
    void destroy();
}

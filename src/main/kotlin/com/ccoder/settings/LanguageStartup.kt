package com.ccoder.settings

import com.ccoder.text.CcoderText
import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.Logger

/**
 * 启动时把界面语言推给词表层（`CcoderText`）。
 *
 * ## 这是 `preload="true"` 的替代品（2026-09-20）
 *
 * 界面语言必须**在界面出现之前**读起来：最早取文案的两处（编辑器/项目树右键菜单的
 * `update()`、状态栏的 `getDisplayName()`）都**早于**工具窗口，而平台服务是懒实例化的
 * —— 不主动推一次，"上次选了英文"的用户在中文 IDE 里会先看到中文的菜单。
 *
 * 从前这一条靠 `<applicationService … preload="true"/>` 实现。**市场不收了**：
 *
 * > Service preloading is deprecated in the `<com.intellij.applicationService>` element.
 * > Remove the 'preload' attribute and migrate to listeners.
 *
 * （2026-09-20 上传 0.2.22 时被挡下，见计划文档 §八。）于是改成显式监听，语义没变：
 * **帧建起来那一刻推一次** —— 而那时还没有任何菜单被打开过，所以那两处早期的取词点
 * 读到的已经是用户选定的语言。
 *
 * ## 两个细节
 *
 * - 类必须 public：平台靠反射实例化（照 [UiLanguageSettings] 的先例）。
 * - 走的是同伴的 [UiLanguageSettings.applyLanguageToText]：**取不到服务也不抛**。
 *   这是启动路径 —— 界面必须能打开，语言只是偏好（2026-09-20 那次 NPE 的教训）。
 */
class LanguageStartup : AppLifecycleListener {

    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        UiLanguageSettings.applyLanguageToText()
        // 留一行凭据：这是"动启动路径"的改动，真机那一遍要能在日志里看见它跑过
        // （`runIde` 的沙盒日志 / 用户机器的 idea.log 里 grep "界面语言：启动时"）
        LOG.info("CCoder 界面语言：启动时推给词表层（${CcoderText.tag()}）")
    }

    private companion object {
        val LOG = Logger.getInstance(LanguageStartup::class.java)
    }
}

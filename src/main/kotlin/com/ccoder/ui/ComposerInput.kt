package com.ccoder.ui

import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JTextArea
import javax.swing.TransferHandler
import javax.swing.text.DefaultEditorKit

/** 输入框内边距（垂直, 水平），未缩放 px。 */
private const val PADDING_V = 5
private const val PADDING_H = 7

/**
 * 输入框只留内边距，**不画线也不填底**。
 *
 * 边框与底色都归外层卡片（[ComposerCard]）：卡片包住的是整个输入区——
 * 上下文行、输入框、工具栏，边界比只框住文字那一块更明确。
 *
 * 这里若再画一层，就退回了"框里套框"——那正是方案 A 要付的代价，
 * 不能连代价带来的好处也一起丢掉。
 *
 * 抽成独立函数是为了可测 —— ClaudePanel 依赖 Project，起不了单测。
 */
internal fun styleComposerInput(area: JBTextArea) {
    // 不透明会把卡片的底色盖掉，看起来又像嵌了一层
    area.isOpaque = false
    area.border = JBUI.Borders.empty(PADDING_V, PADDING_H)
}

/**
 * 往输入框追加一段片段（右键"添加到 CCoder 聊天框"用）。
 *
 * 追加而不是覆盖：这个动作的用途是**攒上下文** —— 连着扔几段代码再一起问，
 * 覆盖会把上一段吞掉。
 */
internal fun appendSnippet(area: JBTextArea, snippet: String) {
    // 末尾的空白先收干净：不然连着扔两段就会攒出三四行空行
    val existing = area.text.trimEnd()
    area.text = if (existing.isEmpty()) snippet else "$existing\n\n$snippet"
    // 光标停到末尾：追加完接着就能打字
    area.caretPosition = area.text.length
}

/**
 * 装一个"先看剪贴板里有没有图"的粘贴动作。
 *
 * **必须替换 ActionMap 里的 paste**，不能只覆写 `JTextArea.paste()`：
 * Swing 的 Ctrl+V 绑的是 ActionMap 里那个 action，不走那个方法。
 *
 * 这里只做**便宜的那一步**（判 flavor）。真正读图 + 归一化是几十到几百毫秒的
 * 活，交给 [onImages] 去后台做 —— 在 EDT 上解一张 4K 截图会卡住整个 IDE。
 */
internal fun installImagePaste(area: JTextArea, onImages: () -> Unit) {
    val original = area.actionMap.get(DefaultEditorKit.pasteAction)
    area.actionMap.put(
        DefaultEditorKit.pasteAction,
        object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                val flavors = runCatching {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.availableDataFlavors.toList()
                }.getOrDefault(emptyList())

                // 没有图就原样放行 —— 纯文本粘贴的行为一个字都不变
                if (clipHasImage(flavors)) onImages() else original?.actionPerformed(e)
            }
        },
    )
}

/**
 * 拖拽落点。只接图片文件；拖一堆别的进来不会粘上任何东西。
 *
 * 与粘贴共用同一条下游（[onFiles] 拿到的也是原始文件），读盘与归一化同样
 * 交出去在后台做。
 *
 * **原来的处理器必须整个留着**：输入框自带一个（`BasicTextUI` 的
 * `TextTransferHandler`），它同时管着两件事 —— 拖入（"从编辑器拖一段文字进来"）
 * 与**拖出**（Ctrl+C / Ctrl+X 走的正是它的源码侧）。而 Swing 的拖放
 * **不向父级冒泡**：光标下最深的那层是唯一落点。所以本处理器直接装在输入框上，
 * 并且把两半都原样交回给原来那个：非图片的拖入、以及整个源码侧。
 * 少委派源码侧的话，输入框里的复制/剪切会**静默失效** —— 不报错、不提示，
 * 剪贴板纹丝不动。
 */
internal fun installImageDrop(component: JComponent, onFiles: (List<File>) -> Unit) {
    val original = component.transferHandler
    component.transferHandler = object : TransferHandler() {
        // ---- 拖入：我们要加的那一半 ----

        /** 只有真含图片时才轮到我们；返回 null 表示"不归我管，问原来的去"。 */
        private fun imageFiles(support: TransferSupport): List<File>? =
            if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) null
            else runCatching {
                @Suppress("UNCHECKED_CAST")
                (support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
                    .filter { isImageFile(it.name) }
            }.getOrNull()?.takeIf { it.isNotEmpty() }

        override fun canImport(support: TransferSupport): Boolean =
            imageFiles(support) != null || original?.canImport(support) == true

        override fun importData(support: TransferSupport): Boolean {
            val files = imageFiles(support)
            if (files != null) {
                onFiles(files)
                return true
            }
            return original?.importData(support) ?: false
        }

        // ---- 拖出（复制 / 剪切）：两个**公开**入口原样委派 ----
        //
        // Ctrl+C / Ctrl+X 用的是**同一个** transferHandler 的源码侧：cut/copy 绑的是
        // TransferHandler 自带的 TransferAction，它调 getSourceActions +
        // exportToClipboard。只重写拖入那一半的话，源码侧会退化成 NONE —— 复制
        // 什么都不做，而且不报错、不提示，剪贴板纹丝不动。
        //
        // 只委派这两个公开方法是有意的：`createTransferable` / `exportDone` 在
        // TransferHandler 里是 **protected**，Java 与 Kotlin 都不允许在"另一个实例"上
        // 调它（编译器原话：it is protected in 'javax/swing/TransferHandler'），所以
        // 委派不了。但也不需要：拖出只有这两条路，各自内部的 createTransferable
        // 与 exportDone 都跑在原处理器自己身上（见下）
        override fun getSourceActions(c: JComponent): Int =
            original?.getSourceActions(c) ?: TransferHandler.NONE

        // 复制 / 剪切走这条（Ctrl+C、Ctrl+X）
        override fun exportToClipboard(c: JComponent, clip: Clipboard, action: Int) {
            original?.exportToClipboard(c, clip, action)
        }

        // 把选中的文字往外拖走这条。基准实现自己会调 createTransferable + exportDone，
        // 整个交出去就等于那两半也交出去了
        override fun exportAsDrag(c: JComponent, trigger: InputEvent, action: Int) {
            original?.exportAsDrag(c, trigger, action)
        }
    }
}

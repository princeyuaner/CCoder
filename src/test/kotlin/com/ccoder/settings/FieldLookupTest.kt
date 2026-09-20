package com.ccoder.settings

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * 定位器自己的用例（2026-09-20，见 `FieldLookup.kt` 上那份说明）。
 *
 * 要点：**不依赖任何真实页面** —— 页面还会继续改，而"标签怎么摆、输入件怎么认"
 * 这条契约得一直立着。所以这里用的是两条**合成夹具**：一条照旧布局（标签在上、
 * 输入在下），一条照卡片里的新行语言（标签 + 说明在左、控件在右）。
 *
 * 后一条是这次改版能不能安全落地的**唯一证据**：它证明新的定位器在"说明挂在标签
 * 下面"时不会把说明当成输入框 —— 而旧写法在这一点上是错的（详 `FieldLookup.kt`）。
 */
class FieldLookupTest {

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    /** 旧布局：`labeledField` 的形状。 */
    private fun oldShape(): Pair<JPanel, JTextField> {
        lateinit var root: JPanel
        lateinit var field: JTextField
        onEdt {
            field = JTextField()
            root = JPanel(BorderLayout()).apply { add(labeledField("名称", field), BorderLayout.NORTH) }
        }
        return root to field
    }

    /**
     * 新行语言：标签与说明在左列、控件在右。
     *
     * 说明用的是**真的** `wrappedHint`（不是随便一个 JTextArea）——这条夹具要证明的
     * 正是"生产代码产出的那块说明不会被当成输入框"。
     */
    private fun newShape(): Triple<JPanel, JTextField, Component> {
        lateinit var root: JPanel
        lateinit var field: JTextField
        lateinit var hint: Component
        onEdt {
            field = JTextField()
            hint = wrappedHint("这句说明不该被当成输入框", 176)
            val labelCol = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JLabel("名称"))
                add(hint)
            }
            val row = JPanel(BorderLayout()).apply {
                add(labelCol, BorderLayout.WEST)
                add(field, BorderLayout.CENTER)
            }
            root = JPanel(BorderLayout()).apply { add(row, BorderLayout.NORTH) }
        }
        return Triple(root, field, hint)
    }

    @Test
    fun `旧布局——拿到的是标签下面那个输入框`() {
        val (root, field) = oldShape()
        assertSame(field, inputOf(root, "名称"))
    }

    @Test
    fun `新行语言——说明挂在标签下面，也不会被当成输入框`() {
        val (root, field, hint) = newShape()

        val found = inputOf(root, "名称")

        assertSame(field, found, "拿到的应当是右边那个控件，而不是左列里的说明")
        assertTrue(found !== hint, "说明（不可编辑的 JTextArea）被当成输入控件了")
    }

    /** 多行框：拿到的必须是**外面那层滚动壳** —— hooks 的 `commandArea` 靠它取视口里的 JTextArea。 */
    @Test
    fun `多行框拿到的就是那层滚动壳`() {
        lateinit var root: JPanel
        lateinit var shell: JScrollPane
        onEdt {
            shell = JScrollPane(JTextArea())
            root = JPanel(BorderLayout()).apply { add(labeledField("命令", shell), BorderLayout.NORTH) }
        }

        assertSame(shell, inputOf(root, "命令"))
    }

    /** 密钥那格外面套了一层（装眼睛）—— 要钻进去拿到真输入框，不能停在壳上。 */
    @Test
    fun `外面套了一层壳就往里钻`() {
        lateinit var root: JPanel
        lateinit var psw: JPasswordField
        onEdt {
            psw = JPasswordField()
            val wrap = JPanel(BorderLayout()).apply {
                add(psw, BorderLayout.CENTER)
                add(JLabel("眼睛"), BorderLayout.EAST)
            }
            root = JPanel(BorderLayout()).apply { add(labeledField("API Key", wrap), BorderLayout.NORTH) }
        }

        assertSame(psw, inputOf(root, "API Key"))
    }

    /** 一行里有两个输入框时取第一个（自上而下），别去猜。 */
    @Test
    fun `一行里两个输入框——取上面那个`() {
        lateinit var root: JPanel
        lateinit var first: JTextField
        onEdt {
            first = JTextField()
            val second = JTextField()
            val col = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(first)
                add(second)
            }
            root = JPanel(BorderLayout()).apply { add(labeledField("两个", col), BorderLayout.NORTH) }
        }

        assertSame(first, inputOf(root, "两个"))
    }
}

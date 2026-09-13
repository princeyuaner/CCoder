package com.ccoder.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.lang.reflect.Proxy
import javax.imageio.ImageIO
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把设置对话框离屏画成 PNG，好让人眼看一眼。
 *
 * 没有断言，也不该有 —— 单测钉得住"字段顺序对不对""有没有写回 ModelProfiles"，
 * 钉不住"860px 里 Base URL 折没折行""左页签只放一项空不空"。而后者正是选方案 A
 * 的**全部理由**，不该只靠信念。
 *
 * 对话框是**真的**：`ModelProfilesDialog` 的实例、`createCenterPanel()` 的产物、
 * 连"点列表行"都走真实的 `mouseClicked` 监听器，不是照着布局重画一份。
 * 唯一替身是 Project（测试 JVM 里起不了真的）。
 *
 * 产物在 `build/probe/model-profiles-dialog*.png`。改了设置页的观感就跑一下看一眼。
 */
class ModelProfilesDialogProbe {

    /** 设计稿里的三条，外加一条超长 Base URL —— 折行只在最长的那条上才看得见。 */
    private val deepseek = ModelProfile(
        id = "p1",
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/anthropic",
        modelId = "deepseek-flash[1m]",
        authKind = AuthKind.AUTH_TOKEN.name,
    )
    private val sonnet = ModelProfile(
        id = "p2",
        name = "官方 Sonnet",
        baseUrl = "https://api.anthropic-relay.internal.corp.example.com/anthropic",
        modelId = "claude-sonnet-5",
    )
    private val opus = ModelProfile(
        id = "p3",
        name = "中转 Opus",
        baseUrl = "https://relay.example.com",
        modelId = "claude-opus-5",
        authKind = AuthKind.AUTH_TOKEN.name,
    )

    /** 三条都在。选中第一条 —— 这张是主图，看三栏的比例与 Base URL 折行。 */
    @Test
    fun `把设置对话框画成图片`() = render(
        "build/probe/model-profiles-dialog.png",
        profiles = listOf(deepseek, sonnet, opus),
        secrets = mapOf("p1" to "sk-9f2c4e6a8b0d1f3a5c7e9b1d4f2a"),
        clicking = "DeepSeek",
    )

    /** 长 Base URL 单独出一张特写：860px 里折没折行，只有这张说了算。 */
    @Test
    fun `把最长的 Base URL 画成图片`() = render(
        "build/probe/model-profiles-dialog-long-url.png",
        profiles = listOf(sonnet),
        clicking = "官方 Sonnet",
    )

    /** 一条配置都没有时。这是新用户点开看到的第一眼。 */
    @Test
    fun `把空对话框画成图片`() =
        render("build/probe/model-profiles-dialog-empty.png", profiles = emptyList())

    /** `envOverrides` 抢变量时，警告条长什么样、会不会把列表挤下去。 */
    @Test
    fun `把带冲突警告的对话框画成图片`() = render(
        "build/probe/model-profiles-dialog-conflict.png",
        profiles = listOf(deepseek, sonnet),
        envOverrides = mapOf("ANTHROPIC_BASE_URL" to "https://old.example.com"),
        clicking = "DeepSeek",
    )

    /**
     * 三把键全被抢的最长一版。
     *
     * 折行只在最长的这句上才露馅：警告条要是按"一行"定死高度，这里会裁得只剩半句，
     * 而一行的版本（上面那张）看起来完全正常。
     */
    @Test
    fun `把最长的一句冲突警告画成图片`() = render(
        "build/probe/model-profiles-dialog-conflict-long.png",
        profiles = listOf(deepseek, sonnet),
        envOverrides = mapOf(
            "ANTHROPIC_BASE_URL" to "https://old.example.com",
            "ANTHROPIC_AUTH_TOKEN" to "old-token",
            "ANTHROPIC_API_KEY" to "old-key",
        ),
        clicking = "DeepSeek",
    )

    /** 内存版密钥库 —— 探针不碰 PasswordSafe（纯单测环境里它没有实现）。 */
    private class MemoryStore(private val seed: Map<String, String>) : SecretStore {
        override fun read(id: String): String = seed[id] ?: ""
        override fun write(id: String, secret: String) = Unit
    }

    /**
     * 一个只应答 `getService` 的 Project 替身。
     *
     * 对话框问 Project 的只有一句：`ClaudeSettings` 的 `envOverrides`。测试 JVM 里
     * 没有 IDE，起不了真的 Project，而为了这一句去 mock 七十个方法不值当。
     */
    private fun fakeProject(envOverrides: Map<String, String>): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getService" -> ClaudeSettings().apply { this.envOverrides = envOverrides.toMutableMap() }
                "toString" -> "probe project"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        } as Project

    private fun render(
        path: String,
        profiles: List<ModelProfile>,
        secrets: Map<String, String> = emptyMap(),
        envOverrides: Map<String, String> = emptyMap(),
        clicking: String? = null,
    ) {
        SwingUtilities.invokeAndWait {
            val service = ModelProfiles(MemoryStore(secrets)).apply { profiles.forEach { upsert(it) } }
            val dialog = ModelProfilesDialog(fakeProject(envOverrides), service)

            // 点列表行走的是真的监听器：不是照着布局重画，是让对话框自己把表单填起来
            clicking?.let { clickRow(dialog.contentPanel, it) }

            val w = 860
            val h = 540
            val pane = dialog.contentPane ?: dialog.contentPanel
            pane.setSize(w, h)
            layoutAll(pane)

            // 首选尺寸是给报告用的：三栏加起来够不够 860，打字看宽度就知道
            println("probe: $path 内容区首选尺寸 ${dialog.contentPanel.preferredSize.width} x ${dialog.contentPanel.preferredSize.height}")
            write(pane, w, h, path)
        }
    }

    /** 找那一行，然后照真实鼠标点击喊一遍监听器。离屏组件收不到真事件。 */
    private fun clickRow(root: Container, displayName: String) {
        val target = findLabel(root, displayName)
            ?: error("列表里找不到「$displayName」这一行")
        val e = MouseEvent(target, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false)
        target.mouseListeners.forEach { it.mouseClicked(e) }
    }

    /** 只认完全相等的文本 —— 表单里的标签是"名称""Base URL"，不会跟行名撞。 */
    private fun findLabel(root: Container, text: String): Component? {
        for (child in root.components) {
            if (child is JLabel && child.text == text) return child
            if (child is Container) findLabel(child, text)?.let { return it }
        }
        return null
    }

    private fun write(c: Container, w: Int, h: Int, path: String) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        c.paint(g)
        g.dispose()
        val f = File(path)
        f.parentFile?.mkdirs()
        ImageIO.write(img, "png", f)
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }

    /**
     * 带一条验证任务：`ModelProfiles` 是 Application 级服务，平台靠**反射**实例化它。
     *
     * 这条在探针里跑，是因为它是这个服务在整个项目里**第一次被真实调用** ——
     * Task 2 的用例全是直接 `ModelProfiles(store)`，从没走过注册那条路。
     *
     * 不写断言：这里只是在**记录**环境的事实。结果写进报告。
     */
    @Test
    fun `记录 getInstance 在本环境里能不能解析开`() {
        println("probe: getApplication() = ${ApplicationManager.getApplication()}")

        val thrown = runCatching { ModelProfiles.getInstance() }.exceptionOrNull()
        if (thrown != null) {
            println("probe: ModelProfiles.getInstance() 解析失败 —— 完整栈如下")
            thrown.printStackTrace()
        } else {
            println("probe: ModelProfiles.getInstance() 解析成功")
        }

        // 平台实例化服务要的是**真正的无参构造器**（Kotlin 默认参数只生成合成的那份）。
        // 这一条不依赖 Application，所以在本环境里查得出真话
        val noArg = ModelProfiles::class.java.declaredConstructors.filter { it.parameterCount == 0 }
        println(
            "probe: ModelProfiles 的无参构造器 ${noArg.size} 个，" +
                "synthetic=${noArg.map { it.isSynthetic }}，" +
                "public=${noArg.map { java.lang.reflect.Modifier.isPublic(it.modifiers) }}"
        )
    }
}

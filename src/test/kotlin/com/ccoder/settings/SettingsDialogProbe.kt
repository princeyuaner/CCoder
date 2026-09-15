package com.ccoder.settings

import com.ccoder.sidecar.McpServerStatus
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPasswordField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.FocusEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/** 内存版密钥库 —— 探针不碰 PasswordSafe（纯单测环境里它没有实现）。 */
private class MemoryStore(private val seed: Map<String, String> = emptyMap()) : SecretStore {
    val map = seed.toMutableMap()

    /** 写次数。用来钉住"打开对话框不该顺手写一次凭据库"这类回归。 */
    var writes = 0
        private set

    override fun read(id: String): String = map[id] ?: ""

    override fun write(id: String, secret: String) {
        writes++
        if (secret.isEmpty()) map.remove(id) else map[id] = secret
    }
}

/**
 * 一个只应答 `isDisposed` 的 Project 替身。
 *
 * 2026-09-15 之前这里还得应答 `getService` —— 那时对话框自己去
 * `ClaudeSettings.getInstance(project)` 捞设置。改成**由调用方注入**之后，
 * Project 只剩一个身份，服务全部从参数进来：探针于是能精确控制七个字段的初值，
 * 而不是被一个"每次都新建"的替身糊弄。
 *
 * `isDisposed` 是给 `DialogWrapper(project)` 那条路准备的：代理的 `else -> null`
 * 落在 boolean 返回类型上会直接 NPE。
 */
private fun fakeProject(): Project =
    Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "isDisposed" -> false
            "toString" -> "probe project"
            "hashCode" -> 0
            "equals" -> false
            else -> null
        }
    } as Project

/** 一份可以随便写的设置。放进来的值就是探针里那几个字段的初值。 */
private fun settingsWith(
    envOverrides: Map<String, String> = emptyMap(),
    claudePath: String = "",
    model: String = "",
    extraDirs: List<String> = emptyList(),
    permissionMode: PermissionModeSetting = PermissionModeSetting.DEFAULT,
): ClaudeSettings = ClaudeSettings().apply {
    this.claudePath = claudePath
    this.model = model
    this.envOverrides = envOverrides.toMutableMap()
    this.extraDirs = extraDirs.toMutableList()
    this.permissionMode = permissionMode
}

/** 离屏组件收不到真事件，直接喊监听器 —— 走的是组件上真挂的那个。 */
private fun clickOn(target: Component) {
    val e = MouseEvent(target, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false)
    target.mouseListeners.forEach { it.mouseClicked(e) }
}

/** 按文本找标签。只认完全相等 —— 表单里的字段名不会跟列表行名撞。 */
private fun findLabel(root: Container, text: String): Component? {
    for (child in root.components) {
        if (child is JLabel && child.text == text) return child
        if (child is Container) findLabel(child, text)?.let { return it }
    }
    return null
}

private fun findFirst(root: Container, match: (Component) -> Boolean): Component? {
    for (child in root.components) {
        if (match(child)) return child
        if (child is Container) findFirst(child, match)?.let { return it }
    }
    return null
}

/** 按字段标签找它下面那个输入控件 —— 表单是「标签在上、输入在下」的两层结构。 */
private fun inputOf(root: Container, label: String): JComponent {
    val lab = findLabel(root, label) ?: error("找不到字段标签「$label」")
    val panel = lab.parent as? Container ?: error("「$label」不在容器里")
    return panel.components.filterIsInstance<JComponent>().first { it !== lab }
}

/**
 * 摆版。**`invalidate()` 不能少** —— 这一条是 2026-09-15 补上的。
 *
 * 未上屏的层级里 `revalidate()` 不会往上传播，`BoxLayout` 把尺寸算在容器的
 * `layoutSerial` 上、缓存不作废，于是量到的是**旧高度**。四页签之前这个探针
 * 没撞上（一次装完就画），切页之后它是最容易出的事 —— 症状是"新挂上去的页
 * 在 PNG 上是空的"，而单测全绿。同 `AskQuestionRenderProbe.layOutAll`。
 */
private fun layoutAll(c: Container) {
    c.invalidate()
    c.doLayout()
    for (child in c.components) {
        if (child is Container) layoutAll(child)
    }
}

private fun writePng(c: Container, w: Int, h: Int, path: String) {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    c.paint(g)
    g.dispose()
    val f = File(path)
    f.parentFile?.mkdirs()
    ImageIO.write(img, "png", f)
}

/**
 * 渲染探针：把设置对话框离屏画成 PNG，好让人眼看一眼。
 *
 * 没有断言，也不该有 —— 单测钉得住"字段顺序对不对""有没有写回 ModelProfiles"，
 * 钉不住"860px 里 Base URL 折没折行""两栏是不是叠上了"。而后者只有看图才知道：
 * 2026-09-15 那天就是靠它看出**底部一颗按钮都没画**（`closeAction` 声明在 `init`
 * 之后，那一刻还是 null）和表单栏压过列表栏 24px —— 两处单测都是绿的。
 *
 * 对话框是**真的**：`SettingsDialog` 的实例、`createCenterPanel()` 的产物、
 * 连"点列表行"都走真实的 `mouseClicked` 监听器，不是照着布局重画一份。
 * 唯一替身是 Project（测试 JVM 里起不了真的）。
 *
 * 产物在 `build/probe/model-profiles-dialog*.png`。改了设置页的观感就跑一下看一眼。
 */
class SettingsDialogProbe {

    /** 设计稿里的三条，外加一条超长 Base URL —— 折行只在最长的那条上才看得见。 */
    private val deepseek = ModelProfile(
        id = "p1",
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/anthropic",
        // 一条配置挂两个模型是常态，画图时就得按常态画
        modelIds = mutableListOf("deepseek-flash[1m]", "deepseek-v4-pro[1m]"),
        modelId = "deepseek-flash[1m]",
        authKind = AuthKind.AUTH_TOKEN.name,
    )
    private val sonnet = ModelProfile(
        id = "p2",
        name = "官方 Sonnet",
        baseUrl = "https://api.anthropic-relay.internal.corp.example.com/anthropic",
        modelIds = mutableListOf("claude-sonnet-5"),
        modelId = "claude-sonnet-5",
    )
    private val opus = ModelProfile(
        id = "p3",
        name = "中转 Opus",
        baseUrl = "https://relay.example.com",
        modelIds = mutableListOf("claude-opus-5"),
        modelId = "claude-opus-5",
        authKind = AuthKind.AUTH_TOKEN.name,
    )

    /**
     * 三条都在。**在用的那条与正在编辑的那条刻意错开** —— 中转 Opus 在用，
     * 手上编辑的是 DeepSeek。这两个标记回答的是两个问题，画在同一行上看不出
     * 它们是不是真的各管各的。
     */
    @Test
    fun `把设置对话框画成图片`() = render(
        "build/probe/model-profiles-dialog.png",
        profiles = listOf(deepseek, sonnet, opus),
        secrets = mapOf("p1" to "sk-9f2c4e6a8b0d1f3a5c7e9b1d4f2a"),
        selected = "p3",
        clicking = "DeepSeek",
    )

    /** 眼睛点开之后：密钥从一串圆点变成明文。 */
    @Test
    fun `把密钥明文的那一版画成图片`() = render(
        "build/probe/model-profiles-dialog-revealed.png",
        profiles = listOf(deepseek, sonnet, opus),
        secrets = mapOf("p1" to "sk-9f2c4e6a8b0d1f3a5c7e9b1d4f2a"),
        selected = "p3",
        clicking = "DeepSeek",
        revealSecret = true,
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

    // ---- 另外三页（2026-09-15 四页签）----

    /**
     * 通用页。四项都填上东西 —— 空着的话「浏览」按钮、下拉的宽度对不对都看不出来。
     */
    @Test
    fun `把通用页画成图片`() = render(
        "build/probe/settings-general.png",
        claudePath = "C:\\Users\\CY\\AppData\\Roaming\\npm\\node_modules\\@anthropic-ai\\claude-code\\claude.exe",
        model = "claude-sonnet-5",
        page = "通用",
    )

    /** 权限页。默认停在「标准」上 —— 这也是新用户点开看到的那一眼。 */
    @Test
    fun `把权限页画成图片`() = render(
        "build/probe/settings-permission.png",
        page = "权限",
    )

    /**
     * 停在**需要确认**的那个模式上。
     *
     * 为什么非要有这一张：那个「我明白风险」的复选框只在绕过模式下**才存在**，
     * 而"它到底出没出来、出来了会不会把说明挤掉"只有图上看得见。
     */
    @Test
    fun `把需要确认的权限画成图片`() = render(
        "build/probe/settings-permission-bypass.png",
        permissionMode = PermissionModeSetting.BYPASS_PERMISSIONS,
        page = "权限",
    )

    /**
     * **选了绕过、但还没勾确认**的那一帧。
     *
     * 这一帧是新来的：即时保存之后，选中绕过会被立刻降级，而下拉**留在绕过上**
     * （拉回去就等于把复选框也藏了，用户再没地方勾）。于是"下拉说绕过、实际跑标准"
     * 这个中间状态真实存在 —— 全靠那行说明把话说清，值不值得信得看一眼。
     */
    @Test
    fun `把还没确认的绕过权限画成图片`() = render(
        "build/probe/settings-permission-unconfirmed.png",
        permissionMode = PermissionModeSetting.BYPASS_PERMISSIONS,
        pickMode = PermissionModeSetting.BYPASS_PERMISSIONS,
        uncheckOptIn = true,
        page = "权限",
    )

    /**
     * 环境页。两张表都填了几行、并且**故意留一把会被配置覆盖的键** ——
     * 顶部那句冲突提示与表的行高，只有这张图上看得出来。
     */
    @Test
    fun `把环境页画成图片`() = render(
        "build/probe/settings-environment.png",
        envOverrides = mapOf(
            "ANTHROPIC_BASE_URL" to "https://old.example.com",
            "MY_OWN_VAR" to "keep-me",
        ),
        extraDirs = listOf("D:\\shared-libs", "C:\\Users\\CY\\notes"),
        page = "环境",
    )

    /**
     * 预置页。两条预设、其中一条正文多行 —— 内容框的高度封没封住、
     * 长名字在列表栏里怎么收，只有图上看得出来。
     */
    @Test
    fun `把预置页画成图片`() = render(
        "build/probe/settings-presets.png",
        presets = listOf(
            PromptPreset(
                name = "写测试",
                content = "给这段代码补单测。\n要求：\n- 覆盖边界与失败路径\n- 用项目里既有的测试风格\n- 别改产品代码",
            ),
            PromptPreset(
                name = "解释这段报错，尽量说人话",
                content = "解释这段报错：先给结论，再说为什么。\n不要复述代码。",
            ),
        ),
        clicking = "写测试",
        page = "预置",
    )

    /**
     * MCP 页。**故意让左右两栏对不齐**：文件里有两条，会话里有三条
     * （其中一条是用户全局配的、根本不在这个文件里）。
     * 这正是这一页最需要让人看懂的事 —— 对不齐是正常的。
     */
    @Test
    fun `把 MCP 页画成图片`() = render(
        "build/probe/settings-mcp.png",
        page = "MCP",
        mcpFile = """
            {
              "mcpServers": {
                "codegraph": { "command": "npx", "args": ["-y", "codegraph"] },
                "项目内的": { "type": "sse", "url": "https://example.com/sse" }
              }
            }
        """.trimIndent(),
        mcpServers = listOf(
            McpServerStatus("codegraph", "failed", "user", "MCP error -32000: Connection closed", emptyList()),
            McpServerStatus("项目内的", "pending", "project", null, emptyList()),
            McpServerStatus("我自己全局配的", "connected", "user", null, listOf("t1", "t2", "t3")),
        ),
        clicking = "codegraph",
    )

    /**
     * hooks 页。文件里**两种都有**：归这个面板管的（能编辑），
     * 以及不归它管的（原样保留）。顶部那句"另有 N 处"是这一页最需要让人看见的
     * —— 不报的话，"我原来配的那些哪去了"是必然会被问的问题。
     */
    @Test
    fun `把 hooks 页画成图片`() = render(
        "build/probe/settings-hooks.png",
        page = "hooks",
        hooksFile = """
            {
              "hooks": {
                "PreToolUse": [
                  { "matcher": "Write", "hooks": [ { "type": "command", "command": "echo '改文件前先问一声' >&2; exit 2" } ] },
                  { "matcher": "Bash", "hooks": [ { "type": "prompt", "prompt": "看看这条命令危不危险" } ] }
                ],
                "PostCompact": [ { "hooks": [ { "type": "command", "command": "echo 压缩完了" } ] } ]
              }
            }
        """.trimIndent(),
        clicking = "PreToolUse",
    )

    private fun render(
        path: String,
        profiles: List<ModelProfile> = emptyList(),
        secrets: Map<String, String> = emptyMap(),
        envOverrides: Map<String, String> = emptyMap(),
        claudePath: String = "",
        model: String = "",
        extraDirs: List<String> = emptyList(),
        permissionMode: PermissionModeSetting = PermissionModeSetting.DEFAULT,
        /** 进框之后在权限页的下拉里再选一次。用来画"选了但还没确认"那一帧。 */
        pickMode: PermissionModeSetting? = null,
        /** 把「我明白风险」那个勾去掉 —— `reload()` 会照 settings 勾上，得手动反悔一次。 */
        uncheckOptIn: Boolean = false,
        selected: String? = null,
        clicking: String? = null,
        revealSecret: Boolean = false,
        /** 停在哪个页签上。默认是「模型」—— 齿轮点开就落在那儿。 */
        page: String = "模型",
        presets: List<PromptPreset> = emptyList(),
        /** 写进临时项目根的 `.mcp.json`。null = 那份文件不存在。 */
        mcpFile: String? = null,
        /** 会话里的实时状态。空 = 还没拿到过。 */
        mcpServers: List<McpServerStatus> = emptyList(),
        /** 写进临时项目根的 `.claude/settings.json`。null = 那份文件不存在。 */
        hooksFile: String? = null,
    ) {
        SwingUtilities.invokeAndWait {
            val store = MemoryStore(secrets)
            val service = ModelProfiles(store).apply {
                profiles.forEach { upsert(it) }
                select(selected)
            }
            val settings = settingsWith(envOverrides, claudePath, model, extraDirs, permissionMode)
            // 一个临时项目根：MCP 页要往它下面写 `.mcp.json`。
            // 用真目录而不是假的 —— 这一页的读写路径本身就是被测对象
            val base = Files.createTempDirectory("ccoder-settings-probe")
            mcpFile?.let { Files.writeString(base.resolve(".mcp.json"), it) }
            hooksFile?.let {
                // `.claude/` 常常还不存在 —— 页面写的时候会自己建，探针也得自己建
                Files.createDirectories(base.resolve(".claude"))
                Files.writeString(base.resolve(".claude").resolve("settings.json"), it)
            }
            val mcpStatus = McpStatus().apply { if (mcpServers.isNotEmpty()) set(mcpServers) }
            val dialog = SettingsDialog(
                fakeProject(),
                settings,
                service,
                PromptPresets().apply { presets.forEach { upsert(it) } },
                mcpStatus,
                base,
            )

            // 切页走的是真的监听器（页签上挂的那个），不是直接调 select()
            if (page != "模型") clickTab(dialog.contentPanel, page)
            // 注意：这一段本来就跑在 EDT 上（render 整个包在 invokeAndWait 里），
            // 里面**不能**再 invokeAndWait —— 会抛 "Cannot call invokeAndWait from
            // the event dispatcher thread"，看起来像探针坏了，其实是自己套自己
            if (uncheckOptIn) {
                val box = findFirst(dialog.contentPanel) { it is JCheckBox } as? JCheckBox
                    ?: error("找不到那个风险确认框")
                // doClick 而不是 isSelected=false：只有前者会发 ActionEvent（挂在它上面）
                box.doClick()
            }
            if (pickMode != null) {
                val combo = findFirst(dialog.contentPanel) { it is JComboBox<*> } as? JComboBox<Any>
                    ?: error("权限页没有下拉框")
                combo.selectedItem = pickMode
            }

            // 点列表行走的是真的监听器：不是照着布局重画，是让对话框自己把表单填起来
            clicking?.let { clickRow(dialog.contentPanel, it) }
            if (revealSecret) {
                val eye = findFirst(dialog.contentPanel) { it is JLabel && it.toolTipText == "显示/隐藏密钥" }
                    ?: error("找不到那只眼睛")
                clickOn(eye)
            }

            val w = DIALOG_WIDTH
            val h = DIALOG_HEIGHT
            val pane = dialog.contentPane ?: dialog.contentPanel
            pane.setSize(w, h)
            layoutAll(pane)

            // 首选尺寸是给报告用的：几栏加起来够不够 860，打字看宽度就知道
            println("probe: $path 内容区首选尺寸 ${dialog.contentPanel.preferredSize.width} x ${dialog.contentPanel.preferredSize.height}")
            writePng(pane, w, h, path)
        }
    }

    /** 点页签。监听器挂在页签那个 JBLabel 上，所以直接喊它。 */
    private fun clickTab(root: Container, title: String) {
        val label = findLabel(root, title) ?: error("页签栏里找不到「$title」")
        clickOn(label)
    }

    private fun clickRow(root: Container, displayName: String) {
        // 监听器挂在整行上（背景高亮要铺满一行），所以从名字往上找到带监听器的那一层
        var c: Component? = findLabel(root, displayName) ?: error("列表里找不到「$displayName」这一行")
        while (c != null && c.mouseListeners.isEmpty()) c = c.parent
        clickOn(c ?: error("「$displayName」那一行上没有监听器"))
    }
}

/**
 * 「改动即时保存」和它的两个边界 —— 这个类**是断言**，跟上面那个探针不同。
 *
 * spec §11 明列"设置对话框：列表与表单联动、删除时清密钥"，7 份 brief 里一条都没落。
 * 单看代码是看不出来的：`selectedItem` 在挂监听器之前赋值、`JBPasswordField(text)`
 * 在挂监听器之前构造，这两处的**顺序**一旦被后人调换，症状是"一打开对话框就写一次库"，
 * 而没有任何用例会红。
 *
 * 「认证方式」那个下拉与密钥框的打码也归这里 —— 两者都是"控件状态 → 落盘/显示"
 * 这类单看代码看不出对错的接线（见各自用例上的说明）。
 */
class SettingsDialogSaveTest {

    private val p1 = ModelProfile(
        id = "p1",
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/anthropic",
        modelIds = mutableListOf("deepseek-flash[1m]", "deepseek-v4-pro[1m]"),
        modelId = "deepseek-flash[1m]",
        authKind = AuthKind.AUTH_TOKEN.name,
    )

    /** 开一个对话框，停在「编辑第一条」的状态。 */
    private fun openOn(
        store: MemoryStore,
        vararg profiles: ModelProfile,
    ): Pair<SettingsDialog, ModelProfiles> {
        lateinit var dialog: SettingsDialog
        lateinit var service: ModelProfiles
        SwingUtilities.invokeAndWait {
            service = ModelProfiles(store).apply { profiles.forEach { upsert(it) } }
            dialog = SettingsDialog(fakeProject(), settingsWith(), service, PromptPresets(), McpStatus())
            clickOn(listRowFor(dialog, profiles.first().displayName()))
        }
        return dialog to service
    }

    private fun listRowFor(dialog: SettingsDialog, name: String): Component {
        var c: Component? = findLabel(dialog.contentPanel, name) ?: error("列表里没有「$name」")
        while (c != null && c.mouseListeners.isEmpty()) c = c.parent
        return c ?: error("「$name」那一行上没有监听器")
    }

    /** 字段下面那个文本框。API Key 那格外面套了一层（装眼睛），所以往里再找一层。 */
    private fun fieldOf(dialog: SettingsDialog, label: String): JTextComponent {
        val input = inputOf(dialog.contentPanel, label)
        return input as? JTextComponent
            ?: findFirst(input, { it is JTextComponent }) as? JTextComponent
            ?: error("「$label」下面没有输入控件")
    }

    /**
     * 「模型 ID」那一栏里所有输入框，从上到下。
     *
     * 不能走 [fieldOf] —— 它只回第一个，而这里的每个模型各占一行。外面套了一层
     * 滚动框（限高用），所以得往里走。
     */
    private fun modelFields(dialog: SettingsDialog): List<JTextComponent> {
        val out = mutableListOf<JTextComponent>()
        fun walk(c: Container) {
            for (child in c.components) {
                if (child is JTextComponent) out += child
                if (child is Container) walk(child)
            }
        }
        walk(inputOf(dialog.contentPanel, MODEL_IDS_LABEL))
        return out
    }

    /**
     * 摆好版之后，每一行那个 ✕ 在自己的行里的 x。
     *
     * 比较的是**局部坐标**：两行的宽度一样，所以 ✕ 的 x 只差在它左边那个
     * 〔使用中〕占位有多宽 —— 正是要钉的那个东西。
     */
    private fun deleteButtonXs(dialog: SettingsDialog): List<Int> {
        val out = mutableListOf<Int>()
        SwingUtilities.invokeAndWait {
            layoutAll(dialog.contentPanel)
            fun walk(c: Container) {
                for (child in c.components) {
                    if (child is JLabel && child.text == "✕") out += child.location.x
                    if (child is Container) walk(child)
                }
            }
            walk(dialog.contentPanel)
        }
        return out
    }

    /** 字段下面那个下拉。 */
    private fun comboOf(dialog: SettingsDialog, label: String): JComboBox<*> =
        inputOf(dialog.contentPanel, label) as? JComboBox<*>
            ?: error("「$label」下面不是下拉框")

    /**
     * 在某个字段里"打字"：改的是真 Document，所以走的是真监听器。
     *
     * 注意 `setText` 是**整个替换**，文档会先发 remove 再发 insert 两个事件
     * （`DocumentAdapter` 两个都接），而真人逐字输入一次只发一个。
     * 要数事件个数就用 [appendChar]。
     */
    private fun type(dialog: SettingsDialog, label: String, text: String) {
        SwingUtilities.invokeAndWait { fieldOf(dialog, label).text = text }
    }

    /** 在末尾敲一个字符 —— 一次事件，跟真人敲一下一样。 */
    private fun appendChar(dialog: SettingsDialog, label: String, c: String) {
        SwingUtilities.invokeAndWait {
            val doc = fieldOf(dialog, label).document
            doc.insertString(doc.length, c, null)
        }
    }

    @Test
    fun `改文本立刻写回 ModelProfiles，列表也跟着改名`() {
        val store = MemoryStore()
        val (dialog, service) = openOn(store, p1)

        type(dialog, "名称", "改过名了")

        assertEquals("改过名了", service.profiles().single().name, "输入框的改动必须已经落进 ModelProfiles")
        // 联动：列表那一行也得跟着叫新名字，否则左栏显示的是一份过期的数据
        assertNotNull(findLabel(dialog.contentPanel, "改过名了"), "列表没有跟着改名")
    }

    @Test
    fun `改 Base URL 同样立刻落库`() {
        val store = MemoryStore()
        val (dialog, service) = openOn(store, p1)

        type(dialog, "Base URL", "https://relay.example.com/v1")

        val saved = service.profiles().single()
        assertEquals("https://relay.example.com/v1", saved.baseUrl)
        assertEquals("p1", saved.id, "改字段不该换 id —— 换了 id 密钥就失联了")
    }

    /**
     * 模型是一族，每一行一个。改第一行（正好是在用的那个）应当：列表里那一项
     * 被改掉，而**在用的仍然是它** —— 把在用的模型改个名，改名之后它依然在用，
     * 不该被顶到第一项去（那正是"取下标"会犯的错）。
     */
    @Test
    fun `改在用的那一行：列表跟着改，在用的还是它`() {
        val store = MemoryStore()
        val (dialog, service) = openOn(store, p1)

        SwingUtilities.invokeAndWait { modelFields(dialog)[0].text = "glm-4.6" }

        val saved = service.profiles().single()
        assertEquals(listOf("glm-4.6", "deepseek-v4-pro[1m]"), saved.modelIds)
        assertEquals("glm-4.6", saved.modelId, "改的是在用的那一行，它就该仍然在用")
    }

    /** 删一行只该去掉那一行，别的行——包括刚打过字还没失焦的——都得留着。 */
    @Test
    fun `删掉一行只去掉那一行`() {
        val store = MemoryStore()
        val (dialog, service) = openOn(store, p1)

        // 先改第二行（还没删），再删第一行：已打的字不能跟着第一行一起没
        SwingUtilities.invokeAndWait { modelFields(dialog)[1].text = "改过的" }
        SwingUtilities.invokeAndWait { clickOn(findLabel(dialog.contentPanel, "✕")!!) }

        val saved = service.profiles().single()
        assertEquals(listOf("改过的"), saved.modelIds)
        assertEquals("改过的", saved.modelId, "在用的那个被删了，就该落到剩下的第一项")
    }

    /**
     * 每行末尾的 ✕ 要**对齐**。
     *
     * 〔使用中〕只挂在在用的那一行上，不按固定宽度留位的话，有它的那行会把 ✕
     * 往左挤 —— 三行三个位置，看起来像没对齐（同 `MARK` 那条"未选中也缩进"）。
     * 渲染图上看得出，但它值得一条能红的断言，而不是靠人每次看图。
     */
    @Test
    fun `模型行的删除按钮对齐`() {
        val store = MemoryStore()
        val (dialog, _) = openOn(store, p1)

        val xs = deleteButtonXs(dialog)

        assertEquals(2, xs.size, "两行应当各有一个 ✕")
        assertEquals(xs[0], xs[1], "✕ 没对齐 —— 〔使用中〕那个位置没留够")
    }

    @Test
    fun `加一行会多出一个空输入框`() {
        val store = MemoryStore()
        val (dialog, service) = openOn(store, p1)

        SwingUtilities.invokeAndWait { clickOn(findLabel(dialog.contentPanel, ADD_MODEL_LABEL)!!) }

        assertEquals(3, modelFields(dialog).size, "点一下该多一行")
        // 空行**不落库**：normalizeModelProfile 会把空串滤掉，写了等于没写
        assertEquals(2, service.profiles().single().modelIds.size, "空行不该被存进去")
    }

    @Test
    fun `删除会把密钥一并清掉`() {
        val store = MemoryStore(mapOf("p1" to "sk-secret"))
        val (dialog, service) = openOn(store, p1)

        SwingUtilities.invokeAndWait { clickOn(findLabel(dialog.contentPanel, "删除") ?: error("找不到删除")) }

        assertTrue(service.profiles().isEmpty(), "删了之后列表里不该还有它")
        assertNull(store.map["p1"], "密钥必须跟着走 —— 留一条孤儿密钥等于删了个寂寞")
    }

    @Test
    fun `打开对话框不会顺手写一次凭据库`() {
        val store = MemoryStore(mapOf("p1" to "sk-secret"))

        openOn(store, p1)

        // 挂监听器与赋值选中的**先后顺序**就是这个断言在守的东西
        assertEquals(0, store.writes, "只是打开并点开一条配置，不该产生任何写入")
    }

    @Test
    fun `改名不会写凭据库，只有密钥真变了才写`() {
        val store = MemoryStore(mapOf("p1" to "sk-secret"))
        val (dialog, service) = openOn(store, p1)

        // 三个字段、六次文档事件，密钥一个字都没动
        type(dialog, "名称", "D")
        type(dialog, "名称", "De")
        type(dialog, "名称", "Dee")
        assertEquals(0, store.writes, "改的是名字，不该往凭据库写")

        // 密钥真变了才写：一次按键一次写，不是一次文档事件一次
        appendChar(dialog, "API Key", "!")
        assertEquals("sk-secret!", store.map["p1"], "改过的密钥要落进库")
        assertEquals(1, store.writes)
    }

    /**
     * 下拉的**初值**必须是存下来的那个。
     *
     * 它是"认证方式"唯一落盘通路上的第一环：初值要是丢了（比如 `selectedItem = ...`
     * 那行被删），下拉会显示第一项 API_KEY，而用户在**名称**里随便打一个字触发 save()
     * 时，`authKind.selectedItem` 就被当成了他的选择 —— 认证方式被静默改掉。
     */
    @Test
    fun `打开表单时下拉显示的是存下来的认证方式`() {
        val store = MemoryStore()
        val (dialog, _) = openOn(store, p1)   // p1 存的是 AUTH_TOKEN

        assertEquals(
            AuthKind.AUTH_TOKEN,
            comboOf(dialog, "认证方式").selectedItem,
            "下拉的初值必须与落盘的那个一致",
        )
    }

    /**
     * 改下拉要立刻落库 —— 这是"认证方式"**唯一落盘的通路**。
     *
     * 坏了就是"下拉显示 Bearer、实际发 x-api-key"的静默 401，而那看起来和
     * "密钥填错了"一模一样（见 AuthKind 的说明）。挂监听器那行删掉时，这条会红。
     */
    @Test
    fun `改认证方式立刻落库`() {
        val store = MemoryStore(mapOf("p1" to "sk-secret"))
        val (dialog, service) = openOn(store, p1)

        SwingUtilities.invokeAndWait {
            comboOf(dialog, "认证方式").selectedItem = AuthKind.API_KEY
        }

        assertEquals(
            AuthKind.API_KEY.name,
            service.profiles().single().authKind,
            "下拉的改动必须已经落进 ModelProfiles",
        )
        assertEquals(0, store.writes, "只改了认证方式，密钥一个字没动，不该写凭据库")
    }

    /**
     * spec §7 的第三半：「失焦即恢复打码」。
     *
     * 前两半（默认打码 + 👁 切明文）都有实现，但只看代码分不出第三半在不在：
     * 不挂监听器的话，**只有点另一行**（整张表单重建）才会重新打码，光把焦点移开
     * 不会 —— 明文就一直留在屏幕上，直到切换配置或关窗，而那正是这个字段唯一要防的事。
     *
     * 临时失焦**不能**复位：点 ComboBox 弹下拉会让焦点临时移走再还回来，
     * 那种也复位的话，眼睛刚点开的明文会跟着闪一下。
     */
    @Test
    fun `密钥失焦恢复打码，临时失焦不复位`() {
        val store = MemoryStore(mapOf("p1" to "sk-secret"))
        val (dialog, _) = openOn(store, p1)
        val secret = fieldOf(dialog, "API Key") as JBPasswordField
        val masked = secret.echoChar

        SwingUtilities.invokeAndWait {
            val eye = findFirst(dialog.contentPanel) { it is JLabel && it.toolTipText == "显示/隐藏密钥" }
                ?: error("找不到那只眼睛")
            clickOn(eye)
        }
        assertEquals(0.toChar(), secret.echoChar, "点了眼睛就该是明文")

        SwingUtilities.invokeAndWait {
            secret.focusListeners.forEach {
                it.focusLost(FocusEvent(secret, FocusEvent.FOCUS_LOST, true, null))
            }
        }
        assertEquals(0.toChar(), secret.echoChar, "临时失焦（点下拉那种）不该复位，否则明文会闪")

        SwingUtilities.invokeAndWait {
            secret.focusListeners.forEach {
                it.focusLost(FocusEvent(secret, FocusEvent.FOCUS_LOST, false, null))
            }
        }
        assertEquals(masked, secret.echoChar, "真失焦就该恢复打码 —— 明文不该留在屏幕上")
    }

    /**
     * 平台靠**反射**实例化 Application 级服务，要的是真正的无参构造器 ——
     * Kotlin 的默认参数只生成合成构造器，反射找不到，`getService` 会抛。
     *
     * 这里只能查"构造器在不在"：纯单测 JVM 里 `ApplicationManager.getApplication()`
     * 是 null，`getInstance()` 连反射那一步都走不到（详见 task-5-report）。
     */
    @Test
    fun `ModelProfiles 有平台反射要的那个无参构造器`() {
        val noArg = ModelProfiles::class.java.declaredConstructors.filter { it.parameterCount == 0 }
        assertEquals(1, noArg.size, "平台实例化服务只要一份无参构造器")
        assertFalse(noArg.single().isSynthetic, "合成构造器反射找不到 —— 不能用默认参数顶替")
        assertTrue(java.lang.reflect.Modifier.isPublic(noArg.single().modifiers))
    }
}

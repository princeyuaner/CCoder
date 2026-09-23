package com.ccoder.settings

import com.ccoder.text.CcoderText
import com.ccoder.sidecar.DepStatus
import com.ccoder.sidecar.McpServerStatus
import com.ccoder.sidecar.Os
import com.ccoder.sidecar.RunEvent
import com.ccoder.sidecar.RuntimeDep
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPasswordField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
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
import javax.swing.ToolTipManager
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

/** 深度优先找第一个满足条件的组件（按**位置**找控件，尽量别用：卡片一改就静默指错）。 */
private fun findFirst(root: Container, match: (Component) -> Boolean): Component? {
    for (child in root.components) {
        if (match(child)) return child
        if (child is Container) findFirst(child, match)?.let { return it }
    }
    return null
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
 *
 * > **跑过 `-PtestLang=en` 那遍之后要加 `--rerun-tasks`**：切换系统属性不被这个任务的
 * 增量判据看见，于是图会**留在英文上**（2026-09-20 踩过：出完英文那批图忘了重跑，
 * 中文的截图其实一直是英文的，而文件名看不出来）。
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

    /**
     * **模型很多**的那一帧（2026-09-22）。
     *
     * 用户报的是"模型配多了后 后面的看不到了"：表单挂在 `BorderLayout.NORTH` 上、
     * 够高时不缩只裁，三四个模型起最后一行只剩半截、「＋ 添加模型」直接消失。
     * 这张图只回答一件事：列表里每一行还在不在卡身里、滚动条露没露出来。
     */
    @Test
    fun `把模型很多的对话框画成图片`() = render(
        "build/probe/model-profiles-dialog-many-models.png",
        profiles = listOf(
            deepseek.copy(
                id = "p-many",
                name = "公司tokenhub",
                baseUrl = "http://tokenhub.lihoogame.com:3000",
                modelIds = mutableListOf(
                    "deepseek-v4-flash[1m]",
                    "deepseek-v4-pro[1m]",
                    "deepseek-v4-flash",
                    "deepseek-v4-pro",
                    "glm-4.6",
                    "kimi-k2.5",
                ),
                modelId = "deepseek-v4-flash[1m]",
                authKind = AuthKind.API_KEY.name,
            ),
        ),
        secrets = mapOf("p-many" to "sk-9f2c4e6a8b0d1f3a5c7e9b1d4f2a"),
        clicking = "公司tokenhub",
    )

    /**
     * 模型很多、而且**字号还大**（2026-09-22）。
     *
     * 光看默认字号那一张不够：高度预算是按控件首选高度加出来的，字号一涨
     * 每一格都变高，最先被裁掉的还是模型列表。这张与上面那张对照着看。
     */
    @Test
    fun `把模型很多且字号很大画成图片`() = render(
        "build/probe/model-profiles-dialog-many-models-xl.png",
        profiles = listOf(
            deepseek.copy(
                id = "p-many-xl",
                name = "公司tokenhub",
                baseUrl = "http://tokenhub.lihoogame.com:3000",
                modelIds = mutableListOf(
                    "deepseek-v4-flash[1m]",
                    "deepseek-v4-pro[1m]",
                    "deepseek-v4-flash",
                    "glm-4.6",
                    "kimi-k2.5",
                ),
                modelId = "deepseek-v4-flash[1m]",
                authKind = AuthKind.API_KEY.name,
            ),
        ),
        secrets = mapOf("p-many-xl" to "sk-9f2c4e6a8b0d1f3a5c7e9b1d4f2a"),
        clicking = "公司tokenhub",
        scale = FontScale.XLARGE,
    )

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
     * 通用页。该填的都填上 —— 空着的话「浏览」按钮、下拉的宽度对不对都看不出来。
     */
    @Test
    fun `把通用页画成图片`() = render(
        "build/probe/settings-general.png",
        claudePath = "C:\\Users\\CY\\AppData\\Roaming\\npm\\node_modules\\@anthropic-ai\\claude-code\\claude.exe",
        model = "claude-sonnet-5",
        page = "settings.page.general",
    )

    /**
     * 通用页 · 思考折叠**勾上**的那一帧（2026-09-21）。
     *
     * 与权限页那两张同一个道理：勾与不勾是两种形态，而"说明那两行会不会把卡片撑长、
     * 卡片会不会顶出可视区"只有图上看得见（这一页的卡是量出来贴边的，见页面里那段注释）。
     */
    @Test
    fun `把通用页勾上思考折叠画成图片`() = render(
        "build/probe/settings-general-thinkfold.png",
        model = "claude-sonnet-5",
        page = "settings.page.general",
        thinkingFold = true,
    )

    /**
     * 通用页 · 换了字体与字号的那一帧（2026-09-21）。
     *
     * 与上面那张同一个道理：下拉里选中的那一项、以及说明会不会把卡片顶出可视区，
     * 都只有图上看得见（这一页本来就贴边，见页面里那段注释）。
     */
    @Test
    fun `把通用页换字体与字号画成图片`() = render(
        "build/probe/settings-general-font.png",
        model = "claude-sonnet-5",
        page = "settings.page.general",
        font = FontChoice.GEORGIA,
        scale = FontScale.XLARGE,
    )

    /** 权限页。默认停在「标准」上 —— 这也是新用户点开看到的那一眼。 */
    @Test
    fun `把权限页画成图片`() = render(
        "build/probe/settings-permission.png",
        page = "settings.page.permission",
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
        page = "settings.page.permission",
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
        page = "settings.page.permission",
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
        page = "settings.page.environment",
    )

    /**
     * 环境页顶部的「运行依赖」，**四种状态各一张**（2026-09-17）。
     *
     * 看图只回答四件事：两行的状态文字有没有对齐、输出区高不高得离谱、
     * 等宽体与周围是不是一家人、与下面两张表之间的分隔线够不够。
     */
    @Test
    fun `把两个依赖都可用画成图片`() = render(
        "build/probe/settings-environment-deps-ok.png",
        page = "settings.page.environment",
        nodeStatus = DepStatus.Ok("C:\\Program Files\\nodejs\\node.exe", "24.13.1"),
        claudeStatus = DepStatus.Ok("C:\\Users\\CY\\AppData\\Roaming\\npm\\claude.cmd", "2.1.268"),
    )

    /** 两个都缺：两颗「安装 …」都在，状态是那支危险色。这是新用户的第一眼。 */
    @Test
    fun `把两个依赖都缺失画成图片`() = render(
        "build/probe/settings-environment-deps-missing.png",
        page = "settings.page.environment",
    )

    /** 装到一半：输出区在刷、那颗动作变成「取消」。 */
    @Test
    fun `把安装中的样子画成图片`() = render(
        "build/probe/settings-environment-deps-installing.png",
        page = "settings.page.environment",
        nodeStatus = DepStatus.TooOld("C:\\Program Files\\nodejs\\node.exe", "16.20.2", 18),
        claudeStatus = DepStatus.NotFound,
        installingLog = listOf(
            "已找到现有 Node.js 安装（24.13.1）",
            "正在尝试更新……",
            "正在下载 https://cdn.example.com/node-v24.19.0.msi（28.4 MB）",
            "正在安装 node-v24.19.0 (x64)……",
        ),
    )

    /** 这台机器上装不了：动作改说「复制命令并打开安装页」。最长的那颗字，最容易挤爆。 */
    @Test
    fun `把兜底那条路画成图片`() = render(
        "build/probe/settings-environment-deps-manual.png",
        page = "settings.page.environment",
        noPackageManager = true,
    )

    /**
     * 群交流页：一句说明 + 一张二维码（2026-09-17）。
     *
     * 看图只回答三件事：码够不够大（手机扫得动不）、有没有被拉变形、
     * 整页是不是空得发慌。
     */
    @Test
    fun `把群交流页画成图片`() = render("build/probe/settings-group-chat.png", page = "settings.page.groupChat")

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
        page = "settings.page.presets",
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
        /** 「思考折叠」那一格：true = 画"勾上了"的那一帧（默认那一帧是不勾的）。 */
        thinkingFold: Boolean = false,
        /** 「字体」那一档：不给就是默认（跟随 IDE）。传的可能没装 —— 页里会带上它。 */
        font: FontChoice? = null,
        /** 「字号」那一档：不给就是默认（标准）。 */
        scale: FontScale? = null,
        selected: String? = null,
        clicking: String? = null,
        revealSecret: Boolean = false,
        /**
         * 停在哪个页签上，传的是**词表键**（不是页签文字）—— 探针要在两种语言下
         * 都能出图，而"按中文找控件"会让英文那一遍直接中止（2026-09-20 实测）。
         * 默认是「模型」那一页 —— 齿轮点开就落在那儿。
         */
        page: String = "settings.page.models",
        presets: List<PromptPreset> = emptyList(),
        /** 写进临时项目根的 `.mcp.json`。null = 那份文件不存在。 */
        mcpFile: String? = null,
        /** 会话里的实时状态。空 = 还没拿到过。 */
        mcpServers: List<McpServerStatus> = emptyList(),
        /** 写进临时项目根的 `.claude/settings.json`。null = 那份文件不存在。 */
        hooksFile: String? = null,
        /** 「运行依赖」两行的状态。默认两个都缺 —— 那是最该看的一屏。 */
        nodeStatus: DepStatus = DepStatus.NotFound,
        claudeStatus: DepStatus = DepStatus.NotFound,
        /** 非空 = 画「装到一半」那一屏：把这几行灌进输出区，且**不落终态**。 */
        installingLog: List<String> = emptyList(),
        /** true = 这台机器上装不了（没有 npm/winget/brew），两行都走兜底那条路。 */
        noPackageManager: Boolean = false,
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

            // 「运行依赖」：假服务 + 假执行器，探针不真跑 `claude --version`，也不真装任何东西
            var fakeRun: FakeRun? = null
            val deps = depsService(
                probe = { dep, _ -> if (dep == RuntimeDep.NODE) nodeStatus else claudeStatus },
                newRunner = { _, onEvent -> FakeRun(onEvent).also { fakeRun = it } },
            )
            val tools = if (noPackageManager) ToolSet(null, null, null) else TOOLS_WINDOWS
            // 探针的 runAsync 是同步的，所以这一下就把两行的状态落定（同页面 reload() 里那步）
            deps.refreshAll()
            if (installingLog.isNotEmpty()) {
                deps.startInstall(
                    installPlan(RuntimeDep.CLAUDE, claudeStatus, tools, Os.WINDOWS, null, null),
                )
                installingLog.forEach { fakeRun!!.emit(RunEvent.Line(it)) }
            }

            val dialog = SettingsDialog(
                fakeProject(),
                settings,
                service,
                PromptPresets().apply { presets.forEach { upsert(it) } },
                mcpStatus,
                deps,
                UiLanguageSettings(),
                // 界面偏好照 `reload()` 那条路勾上 —— 与"手动反悔一次"的 uncheckOptIn
                // 相反，这一格探针只画它自然的样子（两种形态各出一张图）
                UiPreferences().apply {
                    collapseThinking = thinkingFold
                    font?.let { fontChoice = it }
                    scale?.let { fontScale = it }
                },
                DepsUi(os = Os.WINDOWS, tools = { tools }),
                base,
            )

            // 切页走的是真的监听器（页签上挂的那个），不是直接调 select()
            if (page != "settings.page.models") clickTab(dialog.tabStrip, page)
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
                val eye = findFirst(dialog.contentPanel) { it is JLabel && it.toolTipText == CcoderText.text("settings.models.secretEyeTip") }
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
    private fun clickTab(root: Container, titleKey: String) {
        val title = CcoderText.textOrNull(titleKey) ?: titleKey   // MCP / hooks 是专有名词，不在词表里
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
            dialog = SettingsDialog(
                fakeProject(), settingsWith(), service, PromptPresets(), McpStatus(), depsService(),
                UiLanguageSettings(), UiPreferences(), TEST_DEPS_UI,
            )
            clickOn(listRowFor(dialog, profiles.first().displayName()))
        }
        return dialog to service
    }

    private fun listRowFor(dialog: SettingsDialog, name: String): Component =
        listRowIn(dialog.contentPanel, name)

    /** 列表里那一行（监听器挂在整行上，所以从名字往上找到带监听器的那一层）。 */
    private fun listRowIn(root: Container, name: String): Component {
        var c: Component? = findLabel(root, name) ?: error("列表里没有「$name」")
        while (c != null && c.mouseListeners.isEmpty()) c = c.parent
        return c ?: error("「$name」那一行上没有监听器")
    }

    /** 字段下面那个文本框。API Key 那格外面套了一层（装眼睛），所以往里再找一层。 */
    private fun fieldOf(dialog: SettingsDialog, label: String): JTextComponent =
        fieldOfIn(dialog.contentPanel, label)

    private fun fieldOfIn(root: Container, label: String): JTextComponent {
        val input = inputOf(root, label)
        return input as? JTextComponent
            ?: findFirst(input, { it is JTextComponent }) as? JTextComponent
            ?: error("「$label」下面没有输入控件")
    }

    /** 密钥那一格里的「复制密钥」那颗。 */
    private fun copyLabelIn(root: Container): Component =
        findFirst(root) {
            it is JLabel && it.toolTipText == CcoderText.text("settings.models.secretCopyTip")
        } ?: error("找不到复制密钥那一颗")

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

    /**
     * 模型很多时，**每一行都还得够得着**（2026-09-22）。
     *
     * 用户报的是"模型配多了后 后面的看不到了"。病因是表单挂在 `BorderLayout.NORTH`
     * 上、高度不够时不缩只裁 —— 模型列表那个滚动框被裁出卡身，里面的滚动条
     * 跟着没了，后几行连同「＋ 添加模型」一起消失。单看代码看不出"裁没裁"：
     * `modelListBox` 有封顶也有 `JBScrollPane`，两条都对，合起来还是够不着。
     *
     * 这里钉三件事：
     *  1. 六行输入框都在树里（重建没丢行）；
     *  2. 滚动框**整块**落在卡身矩形里（不是露半截）；
     *  3. 「＋ 添加模型」在滚动视口的内容里（滚到底就够得着）。
     */
    @Test
    fun `模型很多时每一行都够得着，滚动框不被裁出卡身`() {
        val store = MemoryStore()
        val many = p1.copy(
            id = "p-many",
            modelIds = mutableListOf("m1", "m2", "m3", "m4", "m5", "m6"),
            modelId = "m1",
        )
        val (dialog, _) = openOn(store, many)

        SwingUtilities.invokeAndWait {
            val pane = dialog.contentPane ?: dialog.contentPanel
            pane.setSize(DIALOG_WIDTH, DIALOG_HEIGHT)
            layoutAll(pane)

            assertEquals(6, modelFields(dialog).size, "六行模型输入框都该在树里")

            val list = inputOf(dialog.contentPanel, MODEL_IDS_LABEL)
            val scroll = list as? javax.swing.JScrollPane
                ?: error("模型 ID 那一栏应当是滚动框，实际：${list.javaClass.name}")
            check(scroll.height > 0) { "滚动框高度不该是 0" }

            // 滚动框整块落在卡里：露半截的症状就是下面几行再也滚不出来
            var card: java.awt.Component? = scroll.parent
            while (card != null && card !is CardPanel) card = card.parent
            checkNotNull(card) { "模型列表不在任何一张卡里" }
            val scrollInCard = SwingUtilities.convertPoint(scroll.parent, scroll.location, card)
            check(scrollInCard.y + scroll.height <= card.height + 1) {
                "模型列表被裁出卡身了：卡内 y=${scrollInCard.y} 高=${scroll.height} 卡高=${card.height}"
            }

            // 「＋ 添加模型」在滚动内容里，滚到底就够得着
            val view = scroll.viewport.view as? Container ?: error("滚动框没有内容")
            val addOnView = findLabel(view, ADD_MODEL_LABEL)
                ?: error("「$ADD_MODEL_LABEL」不在模型列表的内容里")
            val addInView = SwingUtilities.convertPoint(addOnView.parent, addOnView.location, view)
            check(addInView.y + addOnView.height <= view.preferredSize.height + 1) {
                "「$ADD_MODEL_LABEL」落在滚动内容之外：bottom=${addInView.y + addOnView.height}" +
                    " 内容高=${view.preferredSize.height}"
            }
        }
    }

    @Test
    fun `删除会把密钥一并清掉`() {
        val store = MemoryStore(mapOf("p1" to "sk-secret"))
        val (dialog, service) = openOn(store, p1)

        SwingUtilities.invokeAndWait { clickOn(findLabel(dialog.contentPanel, CcoderText.text("settings.common.delete")) ?: error("找不到删除")) }

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
            val eye = findFirst(dialog.contentPanel) { it is JLabel && it.toolTipText == CcoderText.text("settings.models.secretEyeTip") }
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
     * 复制键（2026-09-22 用户："设置界面的密钥需要可以被复制"）。
     *
     * 三件事都在这一条里：**打码时也照复制**（密码管理器那条惯例 —— 要求先点眼睛
     * 看明文才让复制的话，那一步反倒把明文留在了屏幕上）；复制的是**整条**而不是
     * 选区（打码时选区在屏幕上看不见）；复制不该顺手把打码状态改掉。
     *
     * 走**页面本身**而不是对话框：剪贴板那颗副作用只能从页面构造器注入 ——
     * 生产那条是 `copyToClipboard`，纯单测 JVM 里没有 ApplicationManager，一调就 NPE。
     */
    @Test
    fun `点复制键把整条密钥交出去，打码状态不动`() {
        val copied = mutableListOf<String>()
        lateinit var root: JComponent
        lateinit var secret: JBPasswordField
        lateinit var label: JLabel
        SwingUtilities.invokeAndWait {
            val service = ModelProfiles(MemoryStore(mapOf("p1" to "sk-secret"))).apply { upsert(p1) }
            val page = ModelProfilesPage(settingsWith(), service, copy = { copied += it })
            root = page.component()
            layoutAll(root)
            clickOn(listRowIn(root, p1.displayName()))
            secret = fieldOfIn(root, "API Key") as JBPasswordField
            label = copyLabelIn(root) as JLabel
        }
        val masked = secret.echoChar
        val idle = label.icon

        SwingUtilities.invokeAndWait { clickOn(label) }

        assertEquals(listOf("sk-secret"), copied, "点一下就该把整条密钥交出去")
        assertEquals(masked, secret.echoChar, "复制不该顺手把明文亮出来")
        // 复制是这一格里唯一「成功了也看不出来」的动作，所以点完得换个样子；
        // 换回来的时机是指针离开（那一刻正是"我按完了、去看别处了"）
        assertNotSame(idle, label.icon, "点完该给一个复制走了的信号")
        SwingUtilities.invokeAndWait {
            val exit = MouseEvent(label, MouseEvent.MOUSE_EXITED, 0L, 0, -1, -1, 0, false)
            // 只喊页面自己那个监听器：**Swing 的 ToolTipManager 也挂在上面**，
            // 它收到退出事件会起一个 500ms 的计时器 —— 测试框架那条
            // `checkJavaSwingTimersAreDisposed` 判红的就是它，不是我们的代码
            label.mouseListeners.filterNot { it is ToolTipManager }.forEach { it.mouseExited(exit) }
        }
        assertSame(idle, label.icon, "指针一走就该还原成待命的样子")
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

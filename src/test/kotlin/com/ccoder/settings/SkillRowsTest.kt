package com.ccoder.settings

import com.ccoder.sidecar.SkillCommand
import com.ccoder.sidecar.SkillUsageLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * 拼行：CLI 的清单 + 账单 + 设置 + 磁盘目录 → 页面上的行。
 *
 * 每条用例都对应探针（`sidecar/tools/probe-skills.mjs`）量到的一条事实，
 * 注释里写着是哪一条 —— 这些不是假想的形状。
 */
class SkillRowsTest {

    private fun scan(dir: String, display: String, inProject: Boolean = false) =
        ScannedSkill(dirName = dir, displayName = display, path = Path.of("/tmp/$dir/SKILL.md"), inProject = inProject)

    @Test
    fun `显示名靠目录名翻成设置键`() {
        // 探针 Q5：写显示名给 CLI 的写手，它照收，但那条覆盖永远不命中；真键是目录名
        val rows = skillRowsOf(
            commands = listOf(SkillCommand("Debug Issue", "调试", false)),
            usage = listOf(SkillUsageLine("Debug Issue", "userSettings", 26)),
            overrides = emptyMap(),
            scanned = listOf(scan("debug-issue", "Debug Issue")),
        )

        assertEquals("debug-issue", rows.single().key)
        assertEquals("Debug Issue", rows.single().name, "名字照旧显示显示名")
    }

    @Test
    fun `扫不到的（内置、插件）就拿名字当键`() {
        val rows = skillRowsOf(
            commands = listOf(SkillCommand("dataviz", "画图", true), SkillCommand("code-review:code-review", "审", false)),
            usage = listOf(
                SkillUsageLine("dataviz", "built-in", 300),
                SkillUsageLine("code-review:code-review", "plugin", 120),
            ),
            overrides = emptyMap(),
            scanned = emptyList(),
        )

        // 顺序按来源分组：插件在内置前面（见 SkillSource 的枚举次序）
        assertEquals(listOf("code-review:code-review", "dataviz"), rows.map { it.key })
    }

    @Test
    fun `关掉的技能从设置里补出来 —— 没有描述与 token，但还在`() {
        // 探针 Q1/实测②：off 的技能从 CLI 清单里整个消失（totalSkills 一起少一条）。
        // 不补的话用户关掉一条就再也看不见它，也就开不回来
        val rows = skillRowsOf(
            commands = emptyList(),
            usage = emptyList(),
            overrides = mapOf("lazy-senior-dev" to "off"),
            scanned = listOf(scan("lazy-senior-dev", "lazy-senior-dev")),
        )

        val row = rows.single()
        assertEquals(SkillState.OFF, row.state)
        assertFalse(row.listed, "它不是 CLI 这次列出来的")
        assertNull(row.description)
        assertNull(row.tokens)
        // 比 Path 本身而不是串：Windows 上 toString() 是反斜杠，写死一个 "/tmp/…" 必红
        assertEquals(scan("lazy-senior-dev", "lazy-senior-dev").path, row.path, "扫到了目录就还能打开文件")
    }

    @Test
    fun `插件来源是锁的`() {
        val rows = skillRowsOf(
            commands = listOf(SkillCommand("frontend-design", "界面", false)),
            usage = listOf(SkillUsageLine("frontend-design", "plugin", 90)),
            overrides = emptyMap(),
            scanned = emptyList(),
        )

        assertTrue(rows.single().locked, "写 off 也没用（实测），界面不该给这颗开关")
    }

    @Test
    fun `来源认不出就归其他，不藏起来`() {
        val rows = skillRowsOf(
            commands = listOf(SkillCommand("未来技能", "", false)),
            usage = listOf(SkillUsageLine("未来技能", "syncedSkills", 10)),
            overrides = emptyMap(),
            scanned = emptyList(),
        )

        assertEquals(SkillSource.OTHER, rows.single().source)
    }

    @Test
    fun `账单里没有这一条时，来源退回按目录判`() {
        val rows = skillRowsOf(
            commands = listOf(SkillCommand("probe-demo", "探针技能", false)),
            usage = emptyList(),
            overrides = emptyMap(),
            scanned = listOf(scan("probe-demo", "probe-demo", inProject = true)),
        )

        assertEquals(SkillSource.PROJECT, rows.single().source)
        assertNull(rows.single().tokens)
    }

    @Test
    fun `认不出的档位原样带着，不当成开`() {
        val rows = skillRowsOf(
            commands = listOf(SkillCommand("caveman", "省话", false)),
            usage = listOf(SkillUsageLine("caveman", "userSettings", 28)),
            overrides = mapOf("caveman" to "half-on"),
            scanned = emptyList(),
        )

        assertNull(rows.single().state)
        assertEquals("half-on", rows.single().stateRaw)
    }

    @Test
    fun `排序按来源分组、组内按名字`() {
        val rows = skillRowsOf(
            commands = listOf(
                SkillCommand("zebra", "", true),
                SkillCommand("alpha", "", false),
                SkillCommand("beta", "", false),
            ),
            usage = listOf(
                SkillUsageLine("zebra", "built-in", 1),
                SkillUsageLine("alpha", "userSettings", 1),
                SkillUsageLine("beta", "plugin", 1),
            ),
            overrides = emptyMap(),
            scanned = emptyList(),
        )

        assertEquals(listOf(SkillSource.MINE, SkillSource.PLUGIN, SkillSource.BUILTIN), rows.map { it.source })
        assertEquals(listOf("alpha", "beta", "zebra"), rows.map { it.name })
    }

    @Test
    fun `只出现在账单里的名字也要成行`() {
        // 两条路的名字取并集：清单那次没答上来（超时/老 CLI），不该整页空着
        val rows = skillRowsOf(
            commands = emptyList(),
            usage = listOf(SkillUsageLine("caveman", "userSettings", 28)),
            overrides = emptyMap(),
            scanned = emptyList(),
        )

        assertEquals("caveman", rows.single().name)
        assertEquals(28L, rows.single().tokens)
    }
}

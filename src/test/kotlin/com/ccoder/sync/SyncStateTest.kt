package com.ccoder.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * 落盘镜像与领域对象之间的往返（2026-09-24）。
 *
 * 存在的理由只有一条，但很重要：`@State` 要的形状（全 `var`、`MutableList`）与
 * 纯层要的形状（不可变）不一样，于是中间有一对搬运函数 —— 而**"加了字段忘了搬"是
 * 静默故障**：重启之后设置自己变回去，没有任何报错（仓库里 `ClaudeSettings` 记着这个坑）。
 *
 * 所以第一条用例把**每个字段都填上不一样的值**再转一圈比 —— 将来加字段忘了搬，它会红。
 */
class SyncStateTest {

    @Test
    fun `往返：每个字段都搬到了 —— 漏一个就是「我设的东西自己变回去了」`() {
        val cfg = normalizeConfig(
            SyncConfig(
                enabled = true,
                src = "C:\\.ccoder-test\\server",
                dst = "Z:\\.ccoder-test\\server",
                syncRoots = listOf("trunk", "config"),
                exclude = listOf("temp", "*.log"),
                settleMs = 2_500,
                sweepMs = 90_000,
                deleteMissing = false,
                junk = JunkRules(
                    dirNames = listOf("build"),
                    fileNames = listOf("thumbs.db"),
                    suffixes = listOf(".bak"),
                    prefixes = listOf("gslog"),
                ),
            ),
        )

        assertEquals(cfg, cfg.toState().toConfig(), "转一圈回来必须一模一样")
    }

    @Test
    fun `空表按当前默认处理 —— 没动过的人会跟上新默认`() {
        // 四组 junk 都留空（新建的状态就是这样）
        val cfg = SyncState().toConfig()

        assertEquals(JunkRules.DEFAULT_DIR_NAMES, cfg.junk.dirNames)
        assertEquals(JunkRules.DEFAULT_FILE_NAMES, cfg.junk.fileNames)
        assertEquals(JunkRules.DEFAULT_SUFFIXES, cfg.junk.suffixes)
        assertEquals(JunkRules.DEFAULT_PREFIXES, cfg.junk.prefixes)
    }

    @Test
    fun `某一组动过就整组用他的 —— 不被默认值盖掉`() {
        val state = SyncState(junkDirNames = mutableListOf("只排我这一层"))

        val cfg = state.toConfig()

        assertEquals(listOf("只排我这一层"), cfg.junk.dirNames)
        assertEquals(JunkRules.DEFAULT_SUFFIXES, cfg.junk.suffixes, "没动过的组照旧用默认")
    }

    @Test
    fun `默认状态就是「不配置就不开启」`() {
        val cfg = SyncState().toConfig()

        assertFalse(cfg.enabled, "新建的项目默认不开")
        assertEquals(ConfigProblem.DISABLED, configProblem(cfg))
        assertEquals(SyncConfig.DEFAULT_SETTLE_MS, cfg.settleMs)
        assertEquals(SyncConfig.DEFAULT_SWEEP_MS, cfg.sweepMs)
        assertEquals(true, cfg.deleteMissing)
    }

    @Test
    fun `toConfig 顺手归一 —— 从镜像读出来的配置就是规整过的`() {
        val state = SyncState(src = "  C:\\x  ", exclude = mutableListOf(" Temp ", ""))

        val cfg = state.toConfig()

        assertEquals("C:\\x", cfg.src, "去空白")
        assertEquals(listOf("temp"), cfg.exclude, "模式归一 + 去空项")
    }
}

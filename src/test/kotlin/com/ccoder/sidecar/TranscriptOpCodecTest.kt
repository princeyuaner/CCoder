package com.ccoder.sidecar

import com.ccoder.ui.TranscriptOpCodec
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TranscriptOpCodecTest {

    /** 仓库根的 shared/ 目录，与 React 侧读的是同一份文件。 */
    private fun fixturePath(): Path {
        // 测试的工作目录是项目根（Gradle 默认）
        val path = Path.of("shared", "transcript-ops.json")
        check(Files.exists(path)) { "找不到共享 fixture：${path.toAbsolutePath()}" }
        return path
    }

    @Test
    fun `fixture 中的每一条 op 都能被解析`() {
        val array = JsonParser.parseString(Files.readString(fixturePath())).asJsonArray
        val kinds = array.map { it.asJsonObject.get("op").asString }

        assertEquals("reset", kinds[0])
        assertEquals("append", kinds[1])
        assertEquals("appendDelta", kinds[3])
        assertEquals("finalizeDelta", kinds[5])

        // 7 是工具的**起头帧**、8 是完整消息、9 是它的结果 —— 三条独立 append：
        // 前两条靠 toolUseId 被界面**合并成一张卡**（起头帧先把卡生出来，
        // 2026-09-22 补），第三条靠同一个 toolUseId 把输出挂回那张卡。
        //
        // **两条都要钉**：只断言 kind 的话，7/8 都是 toolUse、把其中一条删掉或
        // 顺序换过来都不报错 —— 而这两条正是"卡片提前出生"的全部依据。
        fun itemAt(index: Int) = array[index].asJsonObject.getAsJsonObject("item")
        fun itemKindAt(index: Int) = itemAt(index).get("kind").asString
        assertEquals("toolUse", itemKindAt(7))
        assertEquals("", itemAt(7).get("input").asString, "起头帧那条参数是空的")
        assertEquals("toolUse", itemKindAt(8))
        assertEquals("toolu_1", itemAt(8).get("toolUseId").asString)
        assertTrue(itemAt(8).get("input").asString.isNotEmpty(), "完整消息那条带着真参数")
        assertEquals("toolResult", itemKindAt(9))

        assertEquals("clearDelta", kinds[10])

        // 带图的用户消息：契约里有它，两侧才会一起去管 images。
        // 2026-09-15 补：此前夹具里一张图都没有，web 侧把 images 整个丢掉都测不出来
        val withImages = itemAt(14)
        assertEquals("user", withImages.get("kind").asString)
        assertEquals(2, withImages.getAsJsonArray("images").size())

        // 末尾三条是**子代理那层**（A1）：一张 Task 卡 + 它名下的一次工具与一句正文。
        // 2026-09-18 补：此前夹具里一条都不带 parent，两端任何一侧把这个字段吃掉
        // 都测不出来 —— 与 images 那次（见上）是同一类漏洞
        val taskCard = itemAt(15)
        assertEquals("toolu_task", taskCard.get("toolUseId").asString)
        assertTrue(!taskCard.has("parent"), "主线程那条 Task 卡不该带 parent")

        val subTool = itemAt(16)
        assertEquals("toolu_task", subTool.get("parent").asString)
        val subText = itemAt(17)
        assertEquals("toolu_task", subText.get("parent").asString)

        assertEquals(21, kinds.size)
        // 末尾是 fresh 标记（2026-10-10 补）：新会话就绪，网页侧据此显示空态引导卡。
        // 它放在**最后一条**而不是 reset 后面：上面全是按下标钉住的断言，
        // 插在中间会把它们全部挪位，而挪位后的断言照样"绿"（只是钉错了东西）
        assertEquals("fresh", kinds[18])

        // 再往后两条是「本轮改动」（2026-10-13 补）：快照标记与回滚回执。
        // 同样挂在**末尾**（同 fresh 那条理由）。两种形状都要在夹具里 ——
        // 网页侧的字段名一旦漂移，它那条"能解析 fixture"的用例当场变红
        assertEquals("snapshots", kinds[19])
        assertEquals("revertReport", kinds[20])
        val snapEntry = array[19].asJsonObject.getAsJsonArray("entries")[0].asJsonObject
        assertEquals("toolu_1", snapEntry.get("toolUseId").asString)
        assertEquals(true, snapEntry.get("existed").asBoolean)
        val revResult = array[20].asJsonObject.getAsJsonArray("results")[2].asJsonObject
        assertEquals("no-snapshot", revResult.get("error").asString)
        assertEquals("failed", revResult.get("action").asString)
    }

    @Test
    fun `snapshots 编码 entries，三种 existed 都能出去`() {
        val ops = listOf(
            TranscriptOp.Snapshots(
                listOf(
                    SnapshotEntry("toolu_1", "/a.txt", true),
                    SnapshotEntry("toolu_2", "/new.txt", false),
                    SnapshotEntry("toolu_3", "/big.bin", null),
                    SnapshotEntry(null, "/no-id.txt", true),
                )
            )
        )
        val obj = JsonParser.parseString(TranscriptOpCodec.encodeBatch(ops)).asJsonArray[0].asJsonObject
        assertEquals("snapshots", obj.get("op").asString)
        val entries = obj.getAsJsonArray("entries")
        assertEquals(4, entries.size())
        assertEquals(true, entries[0].asJsonObject.get("existed").asBoolean)
        assertEquals(false, entries[1].asJsonObject.get("existed").asBoolean)
        // null = 无存档：**显式写成 JSON null**（不是省掉键）—— 收端靠它区分
        // "没存下"与"字段坏了"，两者的兜底不一样
        assertTrue(entries[2].asJsonObject.get("existed").isJsonNull)
        assertTrue(entries[3].asJsonObject.get("toolUseId").isJsonNull)
    }

    @Test
    fun `revertReport 编码逐文件回执，可空字段省略`() {
        val ops = listOf(
            TranscriptOp.RevertReport(
                listOf(
                    RevertResult("/a.txt", ok = true, action = "restored"),
                    RevertResult("/new.txt", ok = true, action = "deleted", dirty = true),
                    RevertResult("/b.kt", ok = false, action = "failed", error = "no-snapshot"),
                )
            )
        )
        val obj = JsonParser.parseString(TranscriptOpCodec.encodeBatch(ops)).asJsonArray[0].asJsonObject
        assertEquals("revertReport", obj.get("op").asString)
        val results = obj.getAsJsonArray("results")
        assertEquals(3, results.size())
        assertEquals("restored", results[0].asJsonObject.get("action").asString)
        assertTrue(!results[0].asJsonObject.has("error"), "null 的可选字段不写（同 result 那几项）")
        assertTrue(!results[0].asJsonObject.has("dirty"))
        assertEquals(true, results[1].asJsonObject.get("dirty").asBoolean)
        assertEquals("no-snapshot", results[2].asJsonObject.get("error").asString)
    }

    @Test
    fun `fresh 编码为仅有 op 字段`() {
        val obj = JsonParser.parseString(TranscriptOpCodec.encodeBatch(listOf(TranscriptOp.Fresh)))
            .asJsonArray[0].asJsonObject
        assertEquals("fresh", obj.get("op").asString)
        assertEquals(1, obj.size(), "fresh 不应携带额外字段")
    }

    @Test
    fun `reset 编码为仅有 op 字段`() {
        val json = TranscriptOpCodec.encodeBatch(listOf(TranscriptOp.Reset))
        val obj = JsonParser.parseString(json).asJsonArray[0].asJsonObject
        assertEquals("reset", obj.get("op").asString)
        assertEquals(1, obj.size(), "reset 不应携带额外字段")
    }

    @Test
    fun `append 编码包含完整 item`() {
        val ops = listOf(
            TranscriptOp.Append(
                TranscriptItem.User(id = "m0", ts = 1726050000000L, text = "你好")
            )
        )
        val item = JsonParser.parseString(TranscriptOpCodec.encodeBatch(ops))
            .asJsonArray[0].asJsonObject.getAsJsonObject("item")

        assertEquals("user", item.get("kind").asString)
        assertEquals("m0", item.get("id").asString)
        assertEquals(1726050000000L, item.get("ts").asLong)
        assertEquals("你好", item.get("text").asString)
    }

    @Test
    fun `result 编码省略为 null 的可选字段`() {
        val ops = listOf(
            TranscriptItem.Result(id = "m", ts = 1L, subtype = "success", costUsd = null, durationMs = null)
                .let { TranscriptOp.Append(it) }
        )
        val item = JsonParser.parseString(TranscriptOpCodec.encodeBatch(ops))
            .asJsonArray[0].asJsonObject.getAsJsonObject("item")

        assertEquals("success", item.get("subtype").asString)
        assertTrue(!item.has("costUsd"), "null 的可选字段不应出现")
        assertTrue(!item.has("durationMs"))
    }

    @Test
    fun `result 编码保留非 null 的可选字段`() {
        val ops = listOf(
            TranscriptItem.Result(id = "m", ts = 1L, subtype = "success", costUsd = 0.1008, durationMs = 1681L)
                .let { TranscriptOp.Append(it) }
        )
        val item = JsonParser.parseString(TranscriptOpCodec.encodeBatch(ops))
            .asJsonArray[0].asJsonObject.getAsJsonObject("item")

        assertEquals(0.1008, item.get("costUsd").asDouble, 0.0001)
        assertEquals(1681L, item.get("durationMs").asLong)
    }

    @Test
    fun `delta 类 op 编码 target 与 text`() {
        val ops = listOf(
            TranscriptOp.AppendDelta("assistant", "你"),
            TranscriptOp.FinalizeDelta("assistant", "你好"),
            TranscriptOp.ClearDelta("assistant"),
        )
        val array = JsonParser.parseString(TranscriptOpCodec.encodeBatch(ops)).asJsonArray

        assertEquals("assistant", array[0].asJsonObject.get("target").asString)
        assertEquals("你", array[0].asJsonObject.get("text").asString)
        assertEquals("你好", array[1].asJsonObject.get("text").asString)
        assertEquals("assistant", array[2].asJsonObject.get("target").asString)
        assertTrue(!array[2].asJsonObject.has("text"), "clearDelta 不带 text")
    }

    @Test
    fun `toolUse 编码 name、input 与配对的 toolUseId`() {
        val ops = listOf(
            TranscriptOp.Append(
                TranscriptItem.ToolUse(
                    id = "m", ts = 1L, toolUseId = "toolu_1",
                    name = "Read", input = """{"file_path":"/a.txt"}""",
                )
            )
        )
        val item = JsonParser.parseString(TranscriptOpCodec.encodeBatch(ops))
            .asJsonArray[0].asJsonObject.getAsJsonObject("item")

        assertEquals("toolUse", item.get("kind").asString)
        assertEquals("Read", item.get("name").asString)
        assertEquals("""{"file_path":"/a.txt"}""", item.get("input").asString)
        // 界面靠它把输出挂回这次调用
        assertEquals("toolu_1", item.get("toolUseId").asString)
    }

    @Test
    fun `parent 只在子代理那几项上出现（主线程的报文一字不差）`() {
        fun itemOf(op: TranscriptOp) = JsonParser.parseString(TranscriptOpCodec.encodeBatch(listOf(op)))
            .asJsonArray[0].asJsonObject.getAsJsonObject("item")

        // 主线程：字段**不出现**（不是 null）—— 老 web 侧不认识这个键，报文保持原样最安全
        val plain = itemOf(
            TranscriptOp.Append(TranscriptItem.ToolUse(id = "m", ts = 1L, toolUseId = "t1", name = "Read", input = "{}"))
        )
        assertTrue(!plain.has("parent"), "主线程的项不该带 parent")

        // 子代理：三种项都要带上，界面才收得进那张 Task 卡
        val subTool = itemOf(
            TranscriptOp.Append(
                TranscriptItem.ToolUse(
                    id = "m", ts = 1L, toolUseId = "t2", name = "Grep", input = "{}", parent = "toolu_task",
                )
            )
        )
        assertEquals("toolu_task", subTool.get("parent").asString)

        val subText = itemOf(
            TranscriptOp.Append(TranscriptItem.Assistant(id = "m", ts = 1L, text = "找到了", parent = "toolu_task"))
        )
        assertEquals("toolu_task", subText.get("parent").asString)

        val subThink = itemOf(
            TranscriptOp.Append(TranscriptItem.Thinking(id = "m", ts = 1L, text = "想想", parent = "toolu_task"))
        )
        assertEquals("toolu_task", subThink.get("parent").asString)
    }

    @Test
    fun `toolResult 编码配对 id、正文与错误标记`() {
        val ops = listOf(
            TranscriptOp.Append(
                TranscriptItem.ToolResult(
                    id = "m", ts = 1L, toolUseId = "toolu_1", text = "boom", isError = true,
                )
            )
        )
        val item = JsonParser.parseString(TranscriptOpCodec.encodeBatch(ops))
            .asJsonArray[0].asJsonObject.getAsJsonObject("item")

        assertEquals("toolResult", item.get("kind").asString)
        assertEquals("toolu_1", item.get("toolUseId").asString)
        assertEquals("boom", item.get("text").asString)
        assertEquals(true, item.get("isError").asBoolean)
    }

    @Test
    fun `批次编码为 JSON 数组`() {
        val json = TranscriptOpCodec.encodeBatch(
            listOf(TranscriptOp.Reset, TranscriptOp.ClearDelta("assistant"))
        )
        val array = JsonParser.parseString(json).asJsonArray
        assertEquals(2, array.size())
    }

    @Test
    fun `空批次编码为空数组`() {
        assertEquals("[]", TranscriptOpCodec.encodeBatch(emptyList()))
    }

    @Test
    fun `文本中的换行与引号被正确转义`() {
        val ops = listOf(
            TranscriptOp.Append(
                TranscriptItem.Assistant(id = "m", ts = 1L, text = "第一行\n第二行 \"引号\"")
            )
        )
        val json = TranscriptOpCodec.encodeBatch(ops)
        // 编码结果必须是单行——pushBatch 的参数不能含裸换行
        assertEquals(1, json.lines().size, "换行必须被 JSON 转义")
        val text = JsonParser.parseString(json).asJsonArray[0]
            .asJsonObject.getAsJsonObject("item").get("text").asString
        assertEquals("第一行\n第二行 \"引号\"", text)
    }
}

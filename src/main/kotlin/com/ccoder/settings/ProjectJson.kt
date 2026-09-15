package com.ccoder.settings

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/**
 * 项目里那两个**不属于我们**的 JSON 配置文件的读写：`.mcp.json` 与
 * `.claude/settings.json`。
 *
 * 两条硬规矩，都由单测钉死：
 *
 *  - **一个字节都不能碰别人的东西**。读进来是整份文档、写回去也是整份文档，
 *    我们只替换自己那一块（见 `withMcpServers` / `withHooks`）——
 *    这两个文件里还可能有 permissions、env、别的工具写的键
 *  - **序列化必须稳定**。否则用户每存一次盘，git 里就多出一片与内容无关的 diff
 */
internal object ProjectJson {

    /**
     * 写盘用的 Gson。
     *
     * - `setPrettyPrinting`：两格缩进（Gson 的默认），与绝大多数编辑器一致
     * - `disableHtmlEscaping`：默认会把 `<` `>` `&` `=` `'` 转成 `<` 这种。
     *   在**转写区的推送**里那是必需的（内容里的 `</script>` 会提前闭合脚本块，
     *   见 `TranscriptOpCodec`），但这里写的是要提交给团队看的配置文件 ——
     *   把一条带 `<` 的命令写成 `<` 是纯粹的噪音
     */
    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    /**
     * 读一份 JSON 对象。
     *
     * 文件不在、读不动、不是 JSON、JSON 但不是对象 —— **一律给空对象**。
     * 这些情况对调用方是同一件事：现在没有可读的配置。抛异常反而逼着每个
     * 调用点写一遍 try。
     */
    fun read(path: Path): JsonObject {
        if (!Files.exists(path)) return JsonObject()
        val text = runCatching { Files.readString(path) }.getOrNull() ?: return JsonObject()
        val parsed = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return JsonObject()
        return if (parsed.isJsonObject) parsed.asJsonObject else JsonObject()
    }

    /** 写回。父目录不存在就建（`.claude/` 常常还没有）。 */
    fun write(path: Path, obj: JsonObject) {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, format(obj))
    }

    /** 格式化：两格缩进 + **恰好一个**尾换行（没有尾换行的话 diff 会显示 `\ No newline`）。 */
    fun format(obj: JsonObject): String = gson.toJson(obj) + "\n"
}

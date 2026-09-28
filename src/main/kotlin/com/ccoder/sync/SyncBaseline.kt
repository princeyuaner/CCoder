package com.ccoder.sync

import com.ccoder.settings.ProjectJson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance("com.ccoder.sync.SyncBaseline")

/**
 * 内容基线：**上次已核实的双方状态**。整个功能"稳态下每轮只要 0.5 秒"靠的就是它
 * （没有它就得对每个文件真读一遍内容，参考实现实测约一分钟）。
 *
 * ## 文件形状
 *
 * ```json
 * { "_comment": "…", "src": "C:/M71/server", "dst": "Z:\\m71\\server",
 *   "files": { "trunk/a.kt": [来源大小, 来源mtime, 目标大小, 目标mtime] } }
 * ```
 *
 * `src` / `dst` 两条是**身份**：任一端换了（用户改了配置、换了机器），
 * [load] 就返回空表 → 自动重做一次全量核对。这是安全且自愈的（参考实现的 `dst_key`）。
 *
 * 一条记录写成四元数组而不是对象，是为了小：1.7 万个文件时，键名会把文件撑大一倍多。
 *
 * ## 两条硬规矩
 *
 * - **读坏了当作没有基线**（走 [ProjectJson.read] 那套容忍：文件不在 / 读不动 /
 *   不是 JSON / 不是对象，一律空对象）。后果只是下一轮慢一次，没有正确性代价。
 * - **写盘是原子的**：先写 `.tmp` 再 `ATOMIC_MOVE`。半个文件当基线比没有基线糟 ——
 *   没有基线时我们老老实实全量核对，而半个文件会让我们**以为**某些文件核实过了。
 *   （`ProjectJson.write` 是"写坏了大不了重写"的配置语义，这里不能照用。）
 *
 * ## 精度
 *
 * mtime 存的是**纳秒**（约 1.8e18），超出 double 能精确表示的范围。Gson 的
 * `JsonPrimitive(Long)` 是按字面量写的、读回来走 `getAsLong()`，所以逐位精确；
 * 但这条**必须由单测钉住**（`SyncBaselineTest`），因为一旦哪层把它当 double 过一遍，
 * 症状是"每轮都在重新核对内容"——慢，而且看不出哪里错了。
 */
internal object SyncBaseline {

    /** 紧凑 Gson：基线不是给人看的 diff（那不是 `.mcp.json` 那种要提交的配置），小一半更实在。 */
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    /**
     * 这一对目录的**键**：`src|dst` 的 SHA-256 前 8 字节（16 个十六进制字符）。
     *
     * 基线文件与认领文件都拿它拼名字（见 `SyncPaths`）。用哈希而不是把路径直接拼进
     * 文件名：路径里有 `:` `\` 这些不能进文件名的字符，而且转义规则必然会在某个盘符上
     * 出错。哈希偶尔对不上时**只会重新核对一次**，代价已知且有限。
     */
    internal fun keyFor(src: String, dst: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$src|$dst".toByteArray(StandardCharsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * 读基线。源/目标对不上、文件读不了、形状不对 —— **一律返回空表**。
     *
     * 空表不是错误：它的含义是"没有已核实过的东西"，下一轮老实做一次全量核对。
     */
    internal fun load(path: Path, srcKey: String, dstKey: String): Map<String, FilePair> {
        val root = ProjectJson.read(path)
        if (root.str("src") != srcKey || root.str("dst") != dstKey) return emptyMap()
        val files = root.getAsJsonObject("files") ?: return emptyMap()

        val out = LinkedHashMap<String, FilePair>()
        for ((rel, value) in files.entrySet()) {
            val arr = value as? JsonArray ?: continue
            if (arr.size() != 4) continue
            // 逐位精确地取回：走 getAsLong 而不是任何经过 double 的路，
            // 纳秒时间戳超出 double 的精确整数范围（见类头注）
            val nums = arr.map { runCatching { it.asLong }.getOrNull() }
            val s1 = nums.getOrNull(0) ?: continue
            val s2 = nums.getOrNull(1) ?: continue
            val d1 = nums.getOrNull(2) ?: continue
            val d2 = nums.getOrNull(3) ?: continue
            out[rel] = FilePair(FileStamp(s1, s2), FileStamp(d1, d2))
        }
        return out
    }

    /** 写基线。原子替换：先 `.tmp` 再 move，**绝不让半个文件成为下次的基线**。 */
    internal fun save(path: Path, srcKey: String, dstKey: String, files: Map<String, FilePair>) {
        val table = JsonObject()
        for ((rel, p) in files) {
            table.add(rel, JsonArray().apply {
                add(p.src.size)
                add(p.src.mtimeNs)
                add(p.dst.size)
                add(p.dst.mtimeNs)
            })
        }
        val root = JsonObject().apply {
            addProperty(
                "_comment",
                "内容对账基线：每个文件上次核实过的（本地大小, 本地mtime_ns, 目标大小, 目标mtime_ns）。" +
                    "删掉它只会让下一轮重做一次全量内容核对，无其他影响。",
            )
            addProperty("src", srcKey)
            addProperty("dst", dstKey)
            add("files", table)
        }

        // 写失败不致命（下一轮退化成全量核对），所以只吞异常、不打断同步 ——
        // 同参考实现的 save_state()。但**要留一行日志**，否则"基线一直写不进去"
        // 会表现成"每轮都很慢"，没人查得到原因。
        runCatching {
            path.parent?.let { Files.createDirectories(it) }
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.writeString(tmp, gson.toJson(root) + "\n", StandardCharsets.UTF_8)
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { LOG.warn("CCoder 同步：基线写盘失败（下一轮会重做全量核对）：$path", it) }
    }
}

/** 本文件自己那份 JSON 取值帮手（仓库惯例：每个配置文件带一份私有的，刻意的重复）。 */
private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

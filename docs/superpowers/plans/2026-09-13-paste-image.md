# 粘贴图片 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户把图粘进输入区（以及拖拽、选文件），跟文字一起发给 Claude，并在转写区看得见——live 与回放两条路都看得见。

**Architecture:** 图片以 Anthropic 的 image content block **内联 base64** 进 `session.send` 的 content 数组（SDK 原生支持，`sdk.d.ts:5464`）；历史里本来就以同样形状存着（真实会话 jsonl 实测），所以回放不需要额外存储。状态（谁在跑、跑完没）不由协议承载，全部是前端从已有消息推导。

**Tech Stack:** Kotlin 2.x（IntelliJ Platform，Swing）· Node 18+ sidecar · React 18 + Vite（JCEF 内嵌）· Gson。

**Spec:** `docs/superpowers/specs/2026-09-13-paste-image-design.md`

## Global Constraints

- **平台目标**：PyCharm `2025.3.1.1`，`sinceBuild = "253"`，JDK 21
- **不打包平台二进制**：`claude` 路径运行时解析
- **权限请求必须永远 resolve**：任何终止路径都要清空待决表
- **未知事件类型必须静默忽略**，绝不抛错
- **协议向后兼容**：新字段一律"省略即旧行为"。纯文本发送**必须仍走字符串形式**，现有测试一条都不该改
- **提交信息**结尾加 `Co-Authored-By: Claude Code <noreply@anthropic.com>`
- **限额（spec §8）**：单张原始 ≤ 5MB · 单条 ≤ 5 张 · 归一化长边 ≤ 1568 · 归一化后 base64 ≤ 1.5MB
- **回放限流（spec §7.1）**：最多 12 张 / 累计 6MB，超出只计数不传数据

---

## 文件结构

```
新增
├── src/main/kotlin/com/ccoder/ui/ImageAttachment.kt      模型 + 归一化 + 限额（纯逻辑）
├── src/main/kotlin/com/ccoder/ui/ImageIngest.kt          三入口 → 原始字节（含纯判定）
├── src/main/kotlin/com/ccoder/ui/ComposerAttachments.kt   附件条组件
├── src/test/kotlin/com/ccoder/ui/ImageAttachmentTest.kt
├── src/test/kotlin/com/ccoder/ui/ImageIngestTest.kt
├── src/test/kotlin/com/ccoder/ui/ComposerAttachmentsTest.kt
└── src/test/kotlin/com/ccoder/ui/ComposerAttachmentsRenderProbe.kt

改动（Kotlin）
├── ui/ComposerInput.kt      装粘贴拦截 + 拖拽
├── ui/Composer.kt           buildComposerCard 加附件条
├── ui/ComposerToolbar.kt    buildStatusRow 加附件按钮
├── ui/ClaudePanel.kt        接线：采集/发送/暂存/回放
├── sidecar/Protocol.kt      encodeSend 带图
├── sidecar/TranscriptOp.kt  User 加 images / omittedImages
└── ui/TranscriptOpCodec.kt  user 分支输出它们

改动（sidecar）
├── sidecar/session.js       send(text, images) 构造 content 数组
└── sidecar/index.js         send 分支传图；preStartQueue 存对象

改动（web）
├── src/types.ts             UserItem 加 images / omittedImages
├── src/codec.ts             parseItem 解析 images
├── src/components/UserBubble.tsx   渲染图片与省略计数
└── src/styles.css           图片样式

共享契约
└── shared/transcript-ops.json   加一条带图用例（两侧测试共用）
```

**三处刻意的分解**（spec §10 要求）：

1. 归一化（`ImageAttachment.kt`）与采集（`ImageIngest.kt`）分开——前者是纯字节变换，后者碰平台 API。
2. 附件条（`ComposerAttachments.kt`）独立成文件，`Composer.kt` 只负责把它摆进卡片。
3. 回放限流是纯函数（`replayBudget`），放在 `MessageRenderer.kt`，不藏在面板里。

---

### Task 1: 归一化与限额（纯逻辑）

**Files:**
- Create: `src/main/kotlin/com/ccoder/ui/ImageAttachment.kt`
- Test: `src/test/kotlin/com/ccoder/ui/ImageAttachmentTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `internal data class ImageAttachment(val mediaType: String, val base64: String)`
  - `internal data class RawImage(val bytes: ByteArray, val name: String)`
  - `internal data class ImageIntake(val accepted: List<ImageAttachment>, val rejected: Int, val reason: String?)`
  - `internal fun normalizeImage(raw: ByteArray, hintMediaType: String): ImageAttachment`
  - `internal fun mediaTypeOf(name: String): String`
  - `internal fun acceptImages(existingCount: Int, incoming: List<RawImage>): ImageIntake`
  - 常量 `MAX_RAW_BYTES` / `MAX_IMAGES` / `MAX_EDGE` / `MAX_BASE64_CHARS`

- [ ] **Step 1: 写失败的测试**

```kotlin
package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** 造一张纯色 PNG。尺寸可控，用来验缩放。 */
private fun png(w: Int, h: Int, alpha: Boolean = false): ByteArray {
    val img = BufferedImage(w, h, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = Color(0x33, 0x66, 0x99)
    g.fillRect(0, 0, w, h)
    g.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    return out.toByteArray()
}

private fun jpeg(w: Int, h: Int): ByteArray {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "jpeg", out)
    return out.toByteArray()
}

private fun decode(a: ImageAttachment): BufferedImage =
    ImageIO.read(java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(a.base64)))

class ImageAttachmentTest {

    @Test
    fun `长边超过 1568 的等比缩到 1568`() {
        val a = normalizeImage(png(4000, 2000), "image/png")
        val img = decode(a)
        assertEquals(1568, img.width)
        assertEquals(784, img.height) // 4000:2000 = 2:1，等比下来 784
    }

    @Test
    fun `长边不超标就原尺寸`() {
        val img = decode(normalizeImage(png(800, 600), "image/png"))
        assertEquals(800, img.width)
        assertEquals(600, img.height)
    }

    @Test
    fun `源是 JPEG 就出 JPEG，源是 PNG 就出 PNG`() {
        assertEquals("image/jpeg", normalizeImage(jpeg(800, 600), "image/jpeg").mediaType)
        assertEquals("image/png", normalizeImage(png(800, 600), "image/png").mediaType)
    }

    @Test
    fun `带 alpha 的 PNG 不许降成 JPEG`() {
        // 透明区在 JPEG 里会变成黑块 —— 截图带透明圆角时特别明显
        val a = normalizeImage(png(2400, 2400, alpha = true), "image/png")
        assertEquals("image/png", a.mediaType)
    }

    @Test
    fun `PNG 超限时降成 JPEG，mediaType 跟着改`() {
        // 随机噪声压不动：1568² 的 PNG 会明显超过 1.5MB 的 base64 上限。
        // 这条钉的是规则③ —— 报了 image/png 却给 JPEG 字节，Claude 会直接解不开。
        // 也是"重编码比原图大就收手"那个坑的钉子：那正是最该降质的时刻
        val noisy = BufferedImage(MAX_EDGE, MAX_EDGE, BufferedImage.TYPE_INT_RGB)
        val rnd = java.util.Random(42)
        for (y in 0 until noisy.height) {
            for (x in 0 until noisy.width) noisy.setRGB(x, y, rnd.nextInt(0xFFFFFF))
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(noisy, "png", out)

        val a = normalizeImage(out.toByteArray(), "image/png")

        assertEquals("image/jpeg", a.mediaType)
        assertTrue(a.base64.length <= MAX_BASE64_CHARS, "降质后仍然超限：${a.base64.length}")
    }

    @Test
    fun `解不开的字节原样返回，不缩放也不抛`() {
        // webp 就走这条路：Java 原生解不开，但 Claude 认这个格式
        val raw = byteArrayOf(1, 2, 3, 4, 5)
        val a = normalizeImage(raw, "image/webp")
        assertEquals("image/webp", a.mediaType)
        assertEquals(java.util.Base64.getEncoder().encodeToString(raw), a.base64)
    }

    @Test
    fun `mediaTypeOf 认扩展名，认不出的退回 png`() {
        assertEquals("image/jpeg", mediaTypeOf("a.JPG"))
        assertEquals("image/jpeg", mediaTypeOf("a.jpeg"))
        assertEquals("image/png", mediaTypeOf("a.png"))
        assertEquals("image/gif", mediaTypeOf("a.gif"))
        assertEquals("image/bmp", mediaTypeOf("a.bmp"))
        assertEquals("image/webp", mediaTypeOf("a.webp"))
        assertEquals("image/png", mediaTypeOf("a.unknown"))
    }

    @Test
    fun `超过 5MB 的单张被拒，并给出原因`() {
        val big = ByteArray(MAX_RAW_BYTES + 1)
        val intake = acceptImages(0, listOf(RawImage(big, "big.png")))
        assertTrue(intake.accepted.isEmpty())
        assertEquals(1, intake.rejected)
        assertEquals("单张超过 5MB", intake.reason)
    }

    @Test
    fun `一次最多 5 张，多出来的被拒`() {
        val small = png(10, 10)
        val intake = acceptImages(0, List(7) { RawImage(small, "s$it.png") })
        assertEquals(MAX_IMAGES, intake.accepted.size)
        assertEquals(2, intake.rejected)
        assertEquals("一次最多 5 张", intake.reason)
    }

    @Test
    fun `已经有 3 张时只能再收 2 张`() {
        val small = png(10, 10)
        val intake = acceptImages(3, List(3) { RawImage(small, "s$it.png") })
        assertEquals(2, intake.accepted.size)
        assertEquals(1, intake.rejected)
    }

    @Test
    fun `没被拒就没有原因`() {
        val intake = acceptImages(0, listOf(RawImage(png(10, 10), "s.png")))
        assertEquals(1, intake.accepted.size)
        assertNull(intake.reason)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew test --tests "*ImageAttachmentTest*" -x buildWebUi --console=plain`
Expected: 编译失败（`ImageAttachment` 未定义）

- [ ] **Step 3: 实现**

```kotlin
package com.ccoder.ui

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** 一张准备发送 / 已经发送的图。存 base64 而不是字节：协议、转写区契约、缩略图三条路最终要的都是它。 */
internal data class ImageAttachment(val mediaType: String, val base64: String)

/** 刚采到、还没归一化的图。[name] 只用来推断媒体类型（剪贴板没有文件名时用 "image.png"）。 */
internal data class RawImage(val bytes: ByteArray, val name: String)

/** 一次收图的结果。[reason] 给界面拼提示行用。 */
internal data class ImageIntake(
    val accepted: List<ImageAttachment>,
    val rejected: Int,
    val reason: String?,
)

/** 单张原始字节上限。base64 后约 6.7MB，NDJSON 单行撑得住；再大就该用户自己压。 */
internal const val MAX_RAW_BYTES = 5 * 1024 * 1024

/** 单条消息张数上限。 */
internal const val MAX_IMAGES = 5

/**
 * 缩放后的长边上限。
 *
 * 取 1568 而不是 Claude Code 自己用的 2000：1568 是 API 的视觉最优长边，
 * 超过它只会被服务端再缩一次，token 却照算。既然要缩，就一步缩到位。
 */
internal const val MAX_EDGE = 1568

/** 归一化之后的 base64 上限，超了逐档降质。 */
internal const val MAX_BASE64_CHARS = 1_500_000

private const val JPEG_QUALITY = 0.85
private val FALLBACK_QUALITIES = listOf(0.6f, 0.4f)

internal fun mediaTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "webp" -> "image/webp"
    else -> "image/png"
}

/**
 * 归一化。
 *
 * 三步：缩放（长边 > [MAX_EDGE]）→ 编码（源 JPEG 出 JPEG，其余出 PNG）→
 * 结果比原始还大就回退原图。解不开的字节（webp 等）原样放行 —— 宁可让 Claude
 * 自己认，也不要在这里把图丢掉。
 */
internal fun normalizeImage(raw: ByteArray, hintMediaType: String): ImageAttachment {
    val decoded = runCatching { ImageIO.read(raw.inputStream()) }.getOrNull()
        ?: return ImageAttachment(hintMediaType, raw.b64())

    val scaled = scaleToMaxEdge(decoded, MAX_EDGE)
    val hasAlpha = scaled.colorModel.hasAlpha()
    val sourceIsJpeg = hintMediaType == "image/jpeg" && !hasAlpha

    // 先按源的格式编。编出来比原图还大、**又没超限**时才回退原图 ——
    // 说明重编码得不偿失。注意这里不能无条件回退：超限恰恰是最该降质的时刻
    var best = encode(scaled, jpeg = sourceIsJpeg)
    if (best == null || (best.size >= raw.size && best.size <= MAX_BASE64_CHARS)) {
        return ImageAttachment(hintMediaType, raw.b64())
    }

    // 超限就逐档降质。**没有 alpha 时允许转 JPEG** —— PNG 没有"降质"这个旋钮，
    // 转格式是它唯一的路。有 alpha 的不能转：透明区会变成黑块
    if (!hasAlpha) {
        for (quality in FALLBACK_QUALITIES) {
            if (best.size <= MAX_BASE64_CHARS) break
            best = encode(scaled, jpeg = true, quality = quality) ?: break
        }
    }

    // 格式跟着**实际**编码走：把 PNG 降成了 JPEG 却还报 PNG，Claude 会按 PNG 去解。
    // 仍超限也照发（spec §8：宁可不省，不丢图）
    val mediaType = if (best.jpeg) "image/jpeg" else hintMediaType
    return ImageAttachment(mediaType, Base64.getEncoder().encodeToString(best.bytes))
}

private class Encoded(val bytes: ByteArray, val jpeg: Boolean)

private fun encode(img: BufferedImage, jpeg: Boolean, quality: Float = JPEG_QUALITY.toFloat()): Encoded? {
    val out = ByteArrayOutputStream()
    return runCatching {
        if (!jpeg) {
            ImageIO.write(img, "png", out)
        } else {
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
            ImageIO.createImageOutputStream(out).use { writer.output = it; writer.write(null, IIOImage(img, null, null), param) }
        }
        Encoded(out.toByteArray(), jpeg)
    }.getOrNull()
}

private fun scaleToMaxEdge(img: BufferedImage, maxEdge: Int): BufferedImage {
    val longEdge = maxOf(img.width, img.height)
    if (longEdge <= maxEdge) return img

    val ratio = maxEdge.toDouble() / longEdge
    val w = (img.width * ratio).toInt().coerceAtLeast(1)
    val h = (img.height * ratio).toInt().coerceAtLeast(1)
    val out = BufferedImage(w, h, if (img.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(img, 0, 0, w, h, null)
    g.dispose()
    return out
}

private fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)

/**
 * 收图策略：先按大小过、再按张数截。
 *
 * **拒绝要给出理由**：粘了 6 张只发 5 张却不吭声，用户会以为全发出去了。
 */
internal fun acceptImages(existingCount: Int, incoming: List<RawImage>): ImageIntake {
    val accepted = mutableListOf<ImageAttachment>()
    var rejected = 0
    var reason: String? = null

    for (raw in incoming) {
        if (raw.bytes.size > MAX_RAW_BYTES) {
            rejected++
            reason = reason ?: "单张超过 5MB"
            continue
        }
        if (existingCount + accepted.size >= MAX_IMAGES) {
            rejected++
            reason = reason ?: "一次最多 5 张"
            continue
        }
        accepted += normalizeImage(raw.bytes, mediaTypeOf(raw.name))
    }
    return ImageIntake(accepted, rejected, reason)
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew test --tests "*ImageAttachmentTest*" -x buildWebUi --console=plain`
Expected: PASS（10 条）

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/ccoder/ui/ImageAttachment.kt src/test/kotlin/com/ccoder/ui/ImageAttachmentTest.kt
git commit -m "feat(image): 图片归一化与限额

长边 1568、编码变大回退、有 alpha 的 PNG 不转 JPEG、mediaType 跟实际编码走。
拒绝要带原因 —— 静默丢掉几张用户会以为全发出去了。"
```

---

### Task 2: 采集（三入口的原始字节）

**Files:**
- Create: `src/main/kotlin/com/ccoder/ui/ImageIngest.kt`
- Test: `src/test/kotlin/com/ccoder/ui/ImageIngestTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `RawImage` / `mediaTypeOf`
- Produces:
  - `internal val IMAGE_EXTENSIONS: Set<String>`
  - `internal fun isImageFile(name: String): Boolean`
  - `internal fun clipHasImage(flavors: List<DataFlavor>): Boolean`
  - `internal fun readClipboardImages(): List<RawImage>`（平台）
  - `internal fun readImageFiles(files: List<File>): List<RawImage>`（读文件，可用临时文件测）

- [ ] **Step 1: 写失败的测试**

```kotlin
package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Path

class ImageIngestTest {

    @Test
    fun `图片扩展名认大小写`() {
        assertTrue(isImageFile("a.png"))
        assertTrue(isImageFile("a.JPG"))
        assertTrue(isImageFile("a.Jpeg"))
        assertTrue(isImageFile("a.webp"))
        assertFalse(isImageFile("a.txt"))
        assertFalse(isImageFile("a.kt"))
        assertFalse(isImageFile("noextension"))
    }

    @Test
    fun `剪贴板同时有图与文本时算有图 —— 有图优先`() {
        // 截图工具基本只放图，所以这条很少触发；代价是"从 Excel 复制表格"
        // 会粘成一张图，需要手动删一下。这个取舍写在 spec §4
        val flavors = listOf(DataFlavor.stringFlavor, DataFlavor.imageFlavor)
        assertTrue(clipHasImage(flavors))
    }

    @Test
    fun `只有文本时不算有图 —— 纯文本粘贴必须原样放行`() {
        assertFalse(clipHasImage(listOf(DataFlavor.stringFlavor)))
        assertFalse(clipHasImage(emptyList()))
    }

    @Test
    fun `文件列表里只挑图片，非图片与读不出的都跳过`(@TempDir dir: Path) {
        val png = dir.resolve("a.png").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val txt = dir.resolve("b.txt").toFile().apply { writeText("hello") }
        val gone = File(dir.toFile(), "missing.png")

        val out = readImageFiles(listOf(png, txt, gone, dir.toFile()))

        assertEquals(1, out.size)
        assertEquals("a.png", out[0].name)
        assertTrue(out[0].bytes.contentEquals(byteArrayOf(1, 2, 3)))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew test --tests "*ImageIngestTest*" -x buildWebUi --console=plain`
Expected: 编译失败（`isImageFile` 未定义）

- [ ] **Step 3: 实现**

```kotlin
package com.ccoder.ui

import com.intellij.openapi.ide.CopyPasteManager
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * 按图片处理的扩展名。
 *
 * webp 在列，但 Java 原生**解不开**它 —— 归一化会原样放行（见 normalizeImage），
 * 由 Claude 自己认。这不是遗漏，是唯一不丢图的做法。
 */
internal val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")

internal fun isImageFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

/**
 * 剪贴板里有没有图。
 *
 * **有图优先**是刻意的：截图工具（Win+Shift+S、Snipaste、微信）基本只放图。
 * 代价是"从 Excel 复制表格"会粘成一张图而不是文本 —— 需要手动删一下，
 * 这个取舍记在 spec §4。
 */
internal fun clipHasImage(flavors: List<DataFlavor>): Boolean =
    flavors.any { it == DataFlavor.imageFlavor }

/** 从剪贴板取图。取不到就返回空表，不抛。 */
internal fun readClipboardImages(): List<RawImage> {
    val contents = runCatching { CopyPasteManager.getInstance().getContents<Any>(DataFlavor.imageFlavor) }
        .getOrNull() ?: return emptyList()
    val image = contents as? Image ?: return emptyList()
    val bytes = image.toPngBytes() ?: return emptyList()
    return listOf(RawImage(bytes, "clipboard.png"))
}

/** 从文件读。非图片、读不出来、目录都跳过 —— 拖了一堆东西进来时不该整批失败。 */
internal fun readImageFiles(files: List<File>): List<RawImage> = files.mapNotNull { f ->
    if (!f.isFile || !isImageFile(f.name)) return@mapNotNull null
    runCatching { RawImage(f.readBytes(), f.name) }.getOrNull()
}

/**
 * `java.awt.Image` → PNG 字节。
 *
 * 走 `MediaTracker` 等它加载完：剪贴板给的常常是个还没解码完的懒加载 Image，
 * 直接画会得到一张空白图。
 */
private fun Image.toPngBytes(): ByteArray? = runCatching {
    val tracker = java.awt.MediaTracker(java.awt.Label())
    tracker.addImage(this, 0)
    tracker.waitForID(0)

    val w = getWidth(null)
    val h = getHeight(null)
    if (w <= 0 || h <= 0) return null

    val buffered = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = buffered.createGraphics()
    g.drawImage(this, 0, 0, null)
    g.dispose()

    val out = ByteArrayOutputStream()
    ImageIO.write(buffered, "png", out)
    out.toByteArray()
}.getOrNull()
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew test --tests "*ImageIngestTest*" -x buildWebUi --console=plain`
Expected: PASS（4 条）

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/ccoder/ui/ImageIngest.kt src/test/kotlin/com/ccoder/ui/ImageIngestTest.kt
git commit -m "feat(image): 三入口的采集与纯判定

剪贴板有图优先、扩展名过滤、读不出的跳过。平台 API 只在这一个文件里。"
```

---

### Task 3: 附件条组件

**Files:**
- Create: `src/main/kotlin/com/ccoder/ui/ComposerAttachments.kt`
- Test: `src/test/kotlin/com/ccoder/ui/ComposerAttachmentsTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `ImageAttachment` / `ImageIntake`
- Produces:
  - `internal fun attachmentNotice(intake: ImageIntake): String?`
  - `internal fun thumbSize(w: Int, h: Int, box: Int): Dimension`
  - `internal class ComposerAttachments(onRemove: (Int) -> Unit) : JPanel`，方法 `setImages(List<ImageAttachment>)` / `setNotice(String?)` / `clear()` / `imageCount(): Int`

- [ ] **Step 1: 写失败的测试**

```kotlin
package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ComposerAttachmentsTest {

    @Test
    fun `超限时拼出提示行`() {
        val intake = ImageIntake(emptyList(), 2, "单张超过 5MB")
        assertEquals("已跳过 2 张：单张超过 5MB", attachmentNotice(intake))
    }

    @Test
    fun `没被拒就没有提示`() {
        assertNull(attachmentNotice(ImageIntake(emptyList(), 0, null)))
    }

    @Test
    fun `缩略图等比缩到框内`() {
        // 竖图按高度顶满，宽度按比例 —— 不拉伸变形
        assertEquals(32 to 64, thumbSize(1000, 2000, 64).let { it.width to it.height })
        assertEquals(64 to 32, thumbSize(2000, 1000, 64).let { it.width to it.height })
    }

    @Test
    fun `正方形顶满框`() {
        assertEquals(64 to 64, thumbSize(500, 500, 64).let { it.width to it.height })
    }

    @Test
    fun `退化尺寸不炸`() {
        assertEquals(1 to 1, thumbSize(0, 0, 64).let { it.width to it.height })
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew test --tests "*ComposerAttachmentsTest*" -x buildWebUi --console=plain`
Expected: 编译失败

- [ ] **Step 3: 实现**

要点（代码见下）：整条无图时 `isVisible = false`（**否则输入区永远多一条空白**）；缩略图固定高 64px；✕ 角标删单张；提示行用次要色，下次添加时清掉。

```kotlin
package com.ccoder.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** 缩略图高度（未缩放 px）。 */
internal const val THUMB_BOX = 64

/** 提示行文字。被拒了就明说拒了几张、为什么。 */
internal fun attachmentNotice(intake: ImageIntake): String? =
    intake.reason?.let { "已跳过 ${intake.rejected} 张：$it" }

/** 等比缩进 [box]×[box]，不改长宽比（拉伸的缩略图会让人以为粘错了图）。 */
internal fun thumbSize(w: Int, h: Int, box: Int): Dimension {
    if (w <= 0 || h <= 0) return Dimension(1, 1)
    val ratio = box.toDouble() / maxOf(w, h)
    return Dimension((w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1))
}

/**
 * 输入框上方的附件条。
 *
 * **无图时整条隐藏**：不隐藏的话输入区永远挂着一条空白，而且它的最小高度会
 * 通过 preferredSize 顶住分隔条的默认比例（同 COMPOSER_MIN_ROWS 那个坑）。
 */
internal class ComposerAttachments(
    private val onRemove: (Int) -> Unit,
) : JPanel() {

    private val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
    private val notice = JLabel().apply {
        foreground = UIUtil.getInactiveTextColor()
        isVisible = false
    }
    private var images: List<ImageAttachment> = emptyList()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(row)
        add(Box.createVerticalStrut(JBUI.scale(2)))
        add(notice)
        isVisible = false
    }

    fun imageCount(): Int = images.size

    fun setImages(next: List<ImageAttachment>) {
        images = next
        row.removeAll()
        next.forEachIndexed { index, image ->
            row.add(thumb(image, index))
        }
        row.revalidate()
        row.repaint()
        refreshVisibility()
    }

    fun setNotice(text: String?) {
        notice.text = text
        notice.isVisible = text != null
        refreshVisibility()
    }

    fun clear() {
        setImages(emptyList())
        setNotice(null)
    }

    private fun refreshVisibility() {
        isVisible = images.isNotEmpty() || notice.isVisible
    }

    /** 缩略图 + 右上角的 ✕。用 BoxLayout 包一层，好让 ✕ 角标叠在图上。 */
    private fun thumb(image: ImageAttachment, index: Int): JComponent {
        val pane = JPanel(BorderLayout()).apply { isOpaque = false }
        val icon = runCatching {
            val bytes = Base64.getDecoder().decode(image.base64)
            val src = ImageIO.read(bytes.inputStream())
            val size = thumbSize(src.width, src.height, JBUI.scale(THUMB_BOX))
            val scaled = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
            val g = scaled.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, 0, 0, size.width, size.height, null)
            g.dispose()
            ImageIcon(scaled).also {
                pane.toolTipText = "${src.width}×${src.height} · ${bytes.size / 1024}KB"
            }
        }.getOrNull()

        val label = JLabel(icon).apply { border = JBUI.Borders.empty(0, 0, 0, JBUI.scale(2)) }
        pane.add(label, BorderLayout.CENTER)

        val remove = JLabel("✕").apply {
            foreground = UIUtil.getInactiveTextColor()
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = onRemove(index)
            })
        }
        pane.add(remove, BorderLayout.EAST)
        return pane
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew test --tests "*ComposerAttachmentsTest*" -x buildWebUi --console=plain`
Expected: PASS（5 条）

- [ ] **Step 5: 渲染探针（仓库惯例：观感问题先离屏看一眼）**

新建 `src/test/kotlin/com/ccoder/ui/ComposerAttachmentsRenderProbe.kt`：

```kotlin
package com.ccoder.ui

import com.intellij.ui.JBColor
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把附件条的四种形态各画一张 PNG。
 *
 * 没有断言，也不该有 —— 单测能钉住"等比缩到框内""没被拒就没有提示"，
 * 钉不住"缩略图糊不糊、✕ 是不是压在图上了"。而后者只有看才知道。
 *
 * 产物在 `build/attachments-probe-*.png`。改了附件条就跑一下看一眼。
 */
class ComposerAttachmentsRenderProbe {

    @Test
    fun `四种形态各画一张`() = render()

    private fun render() = SwingUtilities.invokeAndWait {
        val cases = listOf(
            "空 —— 整条不占高度" to listOf<ImageAttachment>(),
            "一张" to listOf(img(400, 300)),
            "三张（含竖图与长截图）" to listOf(img(400, 300), img(300, 500), img(1200, 200)),
        )
        cases.forEachIndexed { i, (name, images) ->
            write(strip(images), "build/attachments-probe-$i.png", name)
        }

        // 带提示行的单独一张：提示行与缩略图同时在场时才看得出高度对不对
        val withNotice = strip(listOf(img(400, 300))).apply {
            setNotice(attachmentNotice(ImageIntake(emptyList(), 2, "单张超过 5MB")))
        }
        write(withNotice, "build/attachments-probe-notice.png", "带超限提示")
    }

    private fun strip(images: List<ImageAttachment>) =
        ComposerAttachments(onRemove = {}).apply { setImages(images) }

    /** 内存里的假图，走和真实路径同一套 base64 编码。 */
    private fun img(w: Int, h: Int): ImageAttachment {
        val buffered = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        buffered.createGraphics().apply {
            color = Color(0x33, 0x66, 0x99)
            fillRect(0, 0, w, h)
            dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(buffered, "png", out)
        return ImageAttachment("image/png", Base64.getEncoder().encodeToString(out.toByteArray()))
    }

    private fun write(strip: ComposerAttachments, path: String, caption: String) {
        val root = JPanel(BorderLayout()).apply {
            background = JBColor.panelBackground
            add(JLabel("  $caption").apply { foreground = JBColor.foreground }, BorderLayout.NORTH)
            add(strip, BorderLayout.CENTER)
        }
        root.setSize(460, 200)
        root.doLayout()
        val height = root.preferredSize.height.coerceAtLeast(40)
        root.setSize(460, height)
        root.doLayout()

        val image = BufferedImage(460, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = JBColor.panelBackground
            fillRect(0, 0, image.width, image.height)
            root.paint(this)
            dispose()
        }
        File(path).parentFile?.mkdirs()
        ImageIO.write(image, "png", File(path))
    }
}
```

Run: `./gradlew test --tests "*ComposerAttachmentsRenderProbe*" -x buildWebUi --console=plain`

**跑完打开 `build/attachments-probe-*.png` 看一眼**：空的那张应当**只有标题行没有内容**（整条隐藏）；`✕` 不该压住图；竖图不该被拉扁。

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/com/ccoder/ui/ComposerAttachments.kt src/test/kotlin/com/ccoder/ui/ComposerAttachmentsTest.kt src/test/kotlin/com/ccoder/ui/ComposerAttachmentsRenderProbe.kt
git commit -m "feat(image): 附件条（缩略图 / 删除 / 超限提示）

无图时整条隐藏，否则输入区永远多一条空白。"
```

---

### Task 4: 接进输入区（粘贴 / 拖拽 / 按钮）

**Files:**
- Modify: `src/main/kotlin/com/ccoder/ui/ComposerInput.kt`
- Modify: `src/main/kotlin/com/ccoder/ui/Composer.kt`
- Modify: `src/main/kotlin/com/ccoder/ui/ComposerToolbar.kt`
- Modify: `src/main/kotlin/com/ccoder/ui/ClaudePanel.kt`（**只改 `buildComposerCard` 的调用点**：新建附件条实例并传进去，行为接线在 Task 6）
- Modify: `src/test/kotlin/com/ccoder/ui/ComposerRulesTest.kt`（`buildComposerCard` 调用点）

**Interfaces:**
- Consumes: Task 2（`clipHasImage` / `readClipboardImages` / `readImageFiles` / `isImageFile`）、Task 3（`ComposerAttachments`）
- Produces:
  - `internal fun installImagePaste(area: JTextArea, onImages: () -> Unit)`
  - `internal fun installImageDrop(component: JComponent, onFiles: (List<File>) -> Unit)`
  - `internal fun buildAttachButton(onClick: () -> Unit): JComponent`
  - `internal fun buildComposerCard(inputScroll: JComponent, attachments: JComponent, toolbar: JComponent): ComposerCard`

- [ ] **Step 1: 装粘贴拦截**

`ComposerInput.kt` 追加：

```kotlin
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
```

- [ ] **Step 2: 装拖拽**

`ComposerInput.kt` 追加：

```kotlin
/**
 * 拖拽落点。只接图片文件；拖一堆别的进来不会粘上任何东西。
 *
 * 与粘贴共用同一条下游（[onFiles] 拿到的也是原始文件），读盘与归一化同样
 * 交出去在后台做。
 */
internal fun installImageDrop(component: JComponent, onFiles: (List<File>) -> Unit) {
    component.transferHandler = object : TransferHandler() {
        override fun canImport(support: TransferSupport): Boolean =
            support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) &&
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    (support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
                        .any { isImageFile(it.name) }
                }.getOrDefault(false)

        override fun importData(support: TransferSupport): Boolean {
            val files = runCatching {
                @Suppress("UNCHECKED_CAST")
                (support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
            }.getOrNull() ?: return false
            onFiles(files)
            return true
        }
    }
}
```

- [ ] **Step 3: 附件按钮**

`ComposerToolbar.kt` 追加（并让它出现在状态行左侧）：

```kotlin
/** 回形针。做成安静的次要色 —— 它是入口，不是动作，不该跟发送键抢注意力。 */
internal fun buildAttachButton(onClick: () -> Unit): JComponent = JLabel("📎").apply {
    foreground = UIUtil.getInactiveTextColor()
    toolTipText = "添加图片（也可以直接粘贴或拖进来）"
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) = onClick()
    })
}
```

`buildStatusRow` 变成三参：`buildStatusRow(attach: JComponent, model: JComponent, mode: JComponent)`，把 attach 加在最前。**它的两个调用点（`buildComposerToolbar` 与测试）都要改。**

- [ ] **Step 4: 卡片布局加一层**

`Composer.kt` 的 `buildComposerCard` 从两参变三参：

```kotlin
/**
 * 卡片内部自上而下三段：
 *
 *   NORTH 附件条（无图时它自己隐藏，不占高度）
 *   CENTER 输入框（撑满可用高度）
 *   SOUTH  控件工具栏
 */
internal fun buildComposerCard(
    inputScroll: JComponent,
    attachments: JComponent,
    toolbar: JComponent,
): ComposerCard = ComposerCard().apply {
    add(attachments, BorderLayout.NORTH)
    add(inputScroll, BorderLayout.CENTER)
    add(toolbar, BorderLayout.SOUTH)
}
```

- [ ] **Step 5: 改调用点，并钉住"无图时不该变高"**

`ComposerRulesTest.kt` 里三处 `buildComposerCard(inputScroll, toolbar)` 改成三参：

```kotlin
    private fun card(toolbar: JComponent = JPanel()) =
        buildComposerCard(JBScrollPane(JTextArea()), ComposerAttachments(onRemove = {}), toolbar)
```

再补一条断言（这是"无图时整条隐藏"的可观察证据，也是**不隐藏就会永远多一条空白**那个坑的钉子）：

```kotlin
    @Test
    fun `没有附件时输入卡的最小高度不受影响`() {
        val empty = ComposerAttachments(onRemove = {})
        val card = buildComposerCard(JBScrollPane(JTextArea()), empty, JPanel())
        val baseline = card.minimumSize.height

        empty.setImages(listOf(ImageAttachment("image/png", "AAAA")))
        card.doLayout()
        assertTrue(card.minimumSize.height > baseline, "有图时应当变高")
    }
```

同样改掉探针里的调用点：`ComposerRenderProbe`、`TopRowRenderProbe`。

Run: `./gradlew test -x buildWebUi --console=plain`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/com/ccoder/ui/ComposerInput.kt src/main/kotlin/com/ccoder/ui/Composer.kt src/main/kotlin/com/ccoder/ui/ComposerToolbar.kt src/test/kotlin/com/ccoder/ui/ComposerRulesTest.kt
git commit -m "feat(image): 三个入口接进输入区

粘贴替换 ActionMap 的 paste（覆写 paste() 拦不住 Ctrl+V）；拖拽只收图片文件；
工具栏加回形针。三入口共用一条下游，读图与归一化一律离开 EDT。"
```

---

### Task 5: 协议与 sidecar

**Files:**
- Modify: `src/main/kotlin/com/ccoder/sidecar/Protocol.kt`（`encodeSend`）
- Modify: `src/test/kotlin/com/ccoder/sidecar/ProtocolTest.kt`
- Modify: `sidecar/session.js`（`send`）
- Modify: `sidecar/test/session.test.js`
- Modify: `sidecar/index.js`（`send` 分支 + `preStartQueue`）
- Modify: `sidecar/test/index.test.js`

**Interfaces:**
- Consumes: Task 1 的 `ImageAttachment`
- Produces:
  - `Protocol.encodeSend(id: String, text: String, images: List<ImageAttachment> = emptyList()): String`
  - sidecar `session.send(text, images = [])`

- [ ] **Step 1: 写失败的 Kotlin 测试**

`ProtocolTest.kt` 追加：

```kotlin
    @Test
    fun `带图发送：images 是对象数组，字段名与 SDK 对齐`() {
        val line = Protocol.encodeSend("req-1", "这报错什么意思", listOf(ImageAttachment("image/png", "AAA")))
        val params = JsonParser.parseString(line.trim()).asJsonObject.getAsJsonObject("params")

        assertEquals("这报错什么意思", params.get("text").asString)
        val img = params.getAsJsonArray("images")[0].asJsonObject
        assertEquals("image/png", img.get("mediaType").asString)
        assertEquals("AAA", img.get("data").asString)
    }

    @Test
    fun `不带图时一个字段都不多 —— 纯文本行为不变`() {
        val line = Protocol.encodeSend("req-1", "你好")
        val params = JsonParser.parseString(line.trim()).asJsonObject.getAsJsonObject("params")
        assertFalse(params.has("images"))
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew test --tests "*ProtocolTest*" -x buildWebUi --console=plain`
Expected: 编译失败（`encodeSend` 不接受三个参数）

- [ ] **Step 3: 改 `Protocol.encodeSend`**

```kotlin
    /**
     * 发送一条用户消息。
     *
     * [images] 为空时**一个字段都不多写** —— 纯文本走的就是老形状，
     * 老版本 sidecar 照常工作。
     */
    fun encodeSend(id: String, text: String, images: List<ImageAttachment> = emptyList()): String =
        line(id, "send", JsonObject().apply {
            addProperty("text", text)
            if (images.isNotEmpty()) {
                add("images", JsonArray().apply {
                    images.forEach { img ->
                        add(JsonObject().apply {
                            addProperty("mediaType", img.mediaType)
                            addProperty("data", img.base64)
                        })
                    }
                })
            }
        })
```

（把 `работа` 改成 `工作` —— 那是笔误。）

- [ ] **Step 4: 写失败的 sidecar 测试**

`sidecar/test/session.test.js` 追加：

```js
  it('带图发送：content 是内容块数组，图在前文字在后', () => {
    const sent = [];
    const q = { [Symbol.asyncIterator]: () => ({ next: async () => ({ done: true }) }) };
    const s = createSession({ queryFn: () => q });
    s.send('看看这个', [{ mediaType: 'image/png', data: 'AAA' }]);
    // queue 不对外暴露，这里通过 inputStream 拿不到 —— 见下方 index.test.js 的断言方式
  });
```

**注意**：`createSession` 的 queue 是闭包私有的。要断言 content 形状，用 `index.test.js` 里注入的假 session 抓 `send(text, images)` 的参数（见 Step 5 的测试），**不要**为测试去改 `session.js` 的结构。

- [ ] **Step 5: 改 sidecar 并写 `index.test.js` 的断言**

`sidecar/session.js`：

```js
    send(text, images = []) {
      if (stopped) return;
      // 图在前、文字在后 —— 与 CLI 自己写进历史的顺序一致（真实会话 jsonl 实测）。
      // 无图时退回字符串形式：形状与老版本完全一致，省掉一次无用解析
      const content = images.length === 0
        ? text
        : [
            ...images.map((i) => ({
              type: 'image',
              source: { type: 'base64', media_type: i.mediaType, data: i.data },
            })),
            ...(text ? [{ type: 'text', text }] : []),
          ];
      queue.push({
        type: 'user',
        message: { role: 'user', content },
        parent_tool_use_id: null,
      });
      notifyInput?.('go');
    },
```

`sidecar/index.js`：`case 'send'` 改成

```js
      case 'send': {
        const images = Array.isArray(params.images) ? params.images : [];
        if (!session) {
          // 排队而非丢弃 —— 用户可能抢在 ready 之前就发了消息
          preStartQueue.push({ text: params.text ?? '', images });
          return session;
        }
        session.send(params.text ?? '', images);
        return session;
      }
```

`start` 分支里的补发同步改成：

```js
        for (const item of preStartQueue.splice(0)) session.send(item.text, item.images);
```

`sidecar/test/index.test.js` 追加两条：

```js
  it('send 把图透传给 session', () => {
    const calls = [];
    const d = createDispatcher({
      sessionFactory: () => ({ send: (t, i) => calls.push([t, i]) }),
      out: () => {},
    });
    d.handle({ method: 'start', params: { cwd: '/x' } });
    d.handle({ method: 'send', params: { text: '看', images: [{ mediaType: 'image/png', data: 'A' }] } });
    expect(calls[0][0]).toBe('看');
    expect(calls[0][1]).toEqual([{ mediaType: 'image/png', data: 'A' }]);
  });

  it('start 之前到达的 send 连图一起补发', () => {
    const calls = [];
    const d = createDispatcher({
      sessionFactory: () => ({ send: (t, i) => calls.push([t, i]) }),
      out: () => {},
    });
    d.handle({ method: 'send', params: { text: '看', images: [{ mediaType: 'image/png', data: 'A' }] } });
    expect(calls).toHaveLength(0);
    d.handle({ method: 'start', params: { cwd: '/x' } });
    expect(calls[0][1]).toEqual([{ mediaType: 'image/png', data: 'A' }]);
  });
```

- [ ] **Step 6: 跑两侧测试**

Run: `./gradlew test -x buildWebUi --console=plain` 与 `cd sidecar && node --test test/`
Expected: 全绿；`session.test.js` 里既有的纯文本断言**一条都不该改**

- [ ] **Step 7: 提交**

```bash
git add src/main/kotlin/com/ccoder/sidecar/Protocol.kt src/test/kotlin/com/ccoder/sidecar/ProtocolTest.kt sidecar/
git commit -m "feat(image): 协议与 sidecar 传图

带图时 content 是内容块数组（图在前、文字在后，与 CLI 写进历史的顺序一致）；
不带图仍走字符串形式，纯文本路径一个字没变。"
```

---

### Task 6: 面板接线 —— 采集与附件条（**不含发送**）

**Files:**
- Modify: `src/main/kotlin/com/ccoder/ui/ClaudePanel.kt`
- Test: `src/test/kotlin/com/ccoder/ui/ComposerRulesTest.kt`

**Interfaces:**
- Consumes: Task 1–5 全部
- Produces: `ClaudePanel.addImages(read: () -> List<RawImage>)`

**发送那一段刻意不放在这个任务里**：它要用到 Task 7 才加上的 `RenderItem.UserText(images)`，
按文件里的顺序执行会编译不过。整条发送路径在 Task 10。

- [ ] **Step 1: 抽出可测的判定（TDD）**

先写测试。`ComposerRulesTest.kt` 追加：

```kotlin
    @Test
    fun `空的输入框发不出任何东西`() {
        assertFalse(hasSendableContent("", 0))
        assertFalse(hasSendableContent("   \n ", 0))
    }

    @Test
    fun `只有图没有文字也能发 —— 纯图提问是合法的`() {
        assertTrue(hasSendableContent("", 1))
    }

    @Test
    fun `有文字就能发`() {
        assertTrue(hasSendableContent("你好", 0))
    }
```

Run: `./gradlew test --tests "*ComposerRulesTest*" -x buildWebUi --console=plain`
Expected: 编译失败（`hasSendableContent` 未定义）

再在 `ClaudePanel.kt` 里实现：

```kotlin
/** 一条消息有没有东西可发。纯图无文字是合法的（spec §5），所以不能只看文字。 */
internal fun hasSendableContent(text: String, imageCount: Int): Boolean =
    text.isNotBlank() || imageCount > 0
```

- [ ] **Step 2: 采集入口（EDT → 后台 → EDT）**

```kotlin
    /**
     * 收图。三个入口（粘贴 / 拖拽 / 选文件）都走这里。
     *
     * [read] 在**后台线程**上跑：读剪贴板、解字节、缩放都在里面，几十到几百毫秒。
     * 在 EDT 上做会卡住整个 IDE。
     */
    fun addImages(read: () -> List<RawImage>) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val raw = runCatching { read() }.getOrDefault(emptyList())
            if (raw.isEmpty()) return@executeOnPooledThread

            val intake = acceptImages(attachments.imageCount(), raw)
            ApplicationManager.getApplication().invokeLater {
                if (intake.accepted.isNotEmpty()) {
                    attachList += intake.accepted
                    attachments.setImages(attachList)
                }
                attachments.setNotice(attachmentNotice(intake))
            }
        }
    }
```

其中 `attachList` 是 `private var attachList: List<ImageAttachment> = emptyList()`，与 `attachments`（组件）并存——**组件不持有真相**，发送后两边一起清。

- [ ] **Step 3: 按钮与文件选择器接线**

在 `init` 里把附件条、粘贴、拖拽、按钮都连上：

```kotlin
        installImagePaste(input, onImages = { addImages { readClipboardImages() } })
        installImageDrop(this, onFiles = { files -> addImages { readImageFiles(files) } })
```

附件按钮：

```kotlin
    private fun onAttachClicked() {
        val chooser = FileChooserFactory.getInstance().createFileChooser(
            FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
                .withTitle("选择图片")
                .withFileFilter { it.extension?.lowercase() in IMAGE_EXTENSIONS },
            null,
            this,
        )
        val files = chooser.choose(project).map { java.io.File(it.path) }
        if (files.isNotEmpty()) addImages { readImageFiles(files) }
    }
```

- [ ] **Step 4: 跑全部 Kotlin 测试**

Run: `./gradlew test -x buildWebUi --console=plain`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/ccoder/ui/ClaudePanel.kt src/test/kotlin/com/ccoder/ui/ComposerRulesTest.kt
git commit -m "feat(image): 采集接线 —— 粘贴 / 拖拽 / 选文件

读图与归一化全部在后台线程，EDT 上只做 flavor 判定。"
```

---

### Task 7: 转写区契约（Kotlin → op）

**Files:**
- Modify: `src/main/kotlin/com/ccoder/sidecar/TranscriptOp.kt`
- Modify: `src/main/kotlin/com/ccoder/ui/MessageRenderer.kt`（`RenderItem.UserText` 加两个带默认值的字段 —— 现有构造点一个都不破）
- Modify: `src/main/kotlin/com/ccoder/ui/TranscriptOpCodec.kt`
- Modify: `src/test/kotlin/com/ccoder/sidecar/TranscriptOpCodecTest.kt`（契约 fixture 检查）
- Modify: `src/test/kotlin/com/ccoder/ui/TranscriptOpCodecTest.kt`
- Modify: `shared/transcript-ops.json`

**Interfaces:**
- Produces:
  - `TranscriptItem.User(id, ts, text, images: List<TranscriptImage> = emptyList(), omittedImages: Int = 0)`
  - `data class TranscriptImage(val mediaType: String, val base64: String)`
  - `RenderItem.UserText(text: String, images: List<TranscriptImage> = emptyList(), omittedImages: Int = 0)`

- [ ] **Step 1: 改数据模型（带默认值，现有构造点一个都不破）**

```kotlin
/** 转写区里的一张图。与 [ImageAttachment] 同形 —— 后者是插件内部模型，这个是对外契约。 */
data class TranscriptImage(val mediaType: String, val base64: String)

    data class User(
        override val id: String,
        override val ts: Long,
        val text: String,
        val images: List<TranscriptImage> = emptyList(),
        /** 回放时因体积预算被省掉的张数（见 MessageRenderer 的 replayBudget）。 */
        val omittedImages: Int = 0,
    ) : TranscriptItem
```

同一个任务里，`MessageRenderer.kt` 的 `RenderItem.UserText` 也要带上（它是 op 的源头；默认值保证现有构造点全不破）。注意补一句 `import com.ccoder.sidecar.TranscriptImage`：

```kotlin
    /**
     * 用户发出的一条消息。
     *
     * [images] 是随消息一起发出去的图；[omittedImages] 只在**回放**时非零
     * （超出体积预算被省掉的张数）。
     */
    data class UserText(
        val text: String,
        val images: List<TranscriptImage> = emptyList(),
        val omittedImages: Int = 0,
    ) : RenderItem
```

- [ ] **Step 2: 写失败的测试**

`ui/TranscriptOpCodecTest.kt` 追加：

```kotlin
    @Test
    fun `带图的用户项输出 images 数组`() {
        val json = TranscriptOpCodec.encodeBatch(
            listOf(
                TranscriptOp.Append(
                    TranscriptItem.User(
                        "m1", 1L, "看这个",
                        listOf(TranscriptImage("image/png", "AAA")),
                    )
                )
            )
        )
        val item = JsonParser.parseString(json).asJsonArray[0].asJsonObject.getAsJsonObject("item")
        assertEquals("看这个", item.get("text").asString)
        assertEquals("image/png", item.getAsJsonArray("images")[0].asJsonObject.get("mediaType").asString)
        assertEquals("AAA", item.getAsJsonArray("images")[0].asJsonObject.get("data").asString)
    }

    @Test
    fun `零值字段一个都不写 —— 老前端照常работа`() {
        val json = TranscriptOpCodec.encodeBatch(
            listOf(TranscriptOp.Append(TranscriptItem.User("m1", 1L, "纯文字")))
        )
        val item = JsonParser.parseString(json).asJsonArray[0].asJsonObject.getAsJsonObject("item")
        assertFalse(item.has("images"))
        assertFalse(item.has("omittedImages"))
    }
```

- [ ] **Step 3: 实现编码**

`TranscriptOpCodec.encodeItem` 的 `is TranscriptItem.User` 分支：

```kotlin
            is TranscriptItem.User -> {
                obj.addProperty("kind", "user")
                obj.addProperty("text", item.text)
                // 零值不写：老前端只认 text，多余字段它本来就会忽略，
                // 但契约 fixture 是两侧共用的，多写等于把噪音钉进契约
                if (item.images.isNotEmpty()) {
                    obj.add("images", JsonArray().apply {
                        item.images.forEach { img ->
                            add(JsonObject().apply {
                                addProperty("mediaType", img.mediaType)
                                addProperty("data", img.base64)
                            })
                        }
                    })
                }
                if (item.omittedImages > 0) obj.addProperty("omittedImages", item.omittedImages)
            }
```

- [ ] **Step 4: 更新共享 fixture**

`shared/transcript-ops.json` 把第 2 条换成带图的：

```json
  {"op":"append","item":{"kind":"user","id":"m0","ts":1726050000000,"text":"你好","images":[{"mediaType":"image/png","data":"iVBORw0KGgo="}]}},
```

再追加一条只有图没有文字的：

```json
  {"op":"append","item":{"kind":"user","id":"m0b","ts":1726050000500,"text":"","images":[{"mediaType":"image/jpeg","data":"/9j/4AAQ"}],"omittedImages":2}},
```

两侧测试（`TranscriptOpCodecTest` 与 `web/src/codec.test.ts`）读的是同一个文件，改完两边都必须绿。

- [ ] **Step 5: 跑测试**

Run: `./gradlew test -x buildWebUi --console=plain` 与 `cd web && node node_modules/vitest/vitest.mjs run src/codec.test.ts`
Expected: Kotlin 绿；web 侧**此时应当红**（解析还没做）——如果没红，说明 fixture 的检查没生效，先查那一步

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/com/ccoder/sidecar/TranscriptOp.kt src/main/kotlin/com/ccoder/ui/TranscriptOpCodec.kt src/test/kotlin/com/ccoder/sidecar/TranscriptOpCodecTest.kt src/test/kotlin/com/ccoder/ui/TranscriptOpCodecTest.kt shared/transcript-ops.json
git commit -m "feat(image): 转写区契约带上图片

零值字段不写；共享 fixture 加带图与纯图两条用例，改契约两侧测试必红。"
```

---

### Task 8: web 渲染图片

**Files:**
- Modify: `web/src/types.ts`
- Modify: `web/src/codec.ts`
- Modify: `web/src/components/UserBubble.tsx`
- Modify: `web/src/styles.css`
- Modify: `web/src/codec.test.ts`

**Interfaces:**
- Consumes: Task 7 的契约
- Produces: `UserItem.images?: TranscriptImage[]` / `UserItem.omittedImages?: number`

- [ ] **Step 1: 写失败的测试**

`web/src/codec.test.ts` 追加：

```ts
describe('用户消息里的图片', () => {
  it('合法 images 解析进 item', () => {
    const ops = parseOps([
      {
        op: 'append',
        item: {
          kind: 'user', id: 'u1', ts: 1, text: '看',
          images: [{ mediaType: 'image/png', data: 'AAA' }],
          omittedImages: 2,
        },
      },
    ])
    const item = ops[0].item as { images?: unknown[]; omittedImages?: number }
    expect(item.images).toHaveLength(1)
    expect(item.omittedImages).toBe(2)
  })

  it('坏掉的单项丢掉、整条消息保留', () => {
    const ops = parseOps([
      {
        op: 'append',
        item: {
          kind: 'user', id: 'u1', ts: 1, text: '看',
          images: [{ mediaType: 'image/png', data: 'AAA' }, { mediaType: 5 }, 'garbage'],
        },
      },
    ])
    const item = ops[0].item as { images?: unknown[] }
    expect(item.images).toHaveLength(1)
  })

  it('没有 images 字段时是 undefined，不是空数组', () => {
    const ops = parseOps([{ op: 'append', item: { kind: 'user', id: 'u1', ts: 1, text: '看' } }])
    expect((ops[0].item as { images?: unknown }).images).toBeUndefined()
  })
})
```

新建 `web/src/components/UserBubble.test.tsx`：

```tsx
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { UserBubble } from './UserBubble'

describe('UserBubble 的图片', () => {
  it('渲染成 data URI 的 img', () => {
    render(<UserBubble text="看这个" images={[{ mediaType: 'image/png', base64: 'AAA' }]} />)
    const img = screen.getByTestId('user-image-0')
    expect(img).toHaveAttribute('src', 'data:image/png;base64,AAA')
  })

  it('只有图没有文字时不渲染空文字块', () => {
    render(<UserBubble text="" images={[{ mediaType: 'image/png', base64: 'AAA' }]} />)
    expect(screen.queryByTestId('user-bubble-text')).not.toBeInTheDocument()
  })

  it('被省掉的张数要说出来，不能装作没有', () => {
    render(<UserBubble text="看" images={[]} omittedImages={3} />)
    expect(screen.getByText('3 张图已省略')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd web && node node_modules/vitest/vitest.mjs run src/codec.test.ts src/components/UserBubble.test.tsx`
Expected: FAIL

- [ ] **Step 3: 实现**

`types.ts`：

```ts
/** 转写区里的一张图。与 Kotlin 侧 TranscriptImage 同形。 */
export interface TranscriptImage {
  mediaType: string
  base64: string
}

export interface UserItem extends Base {
  kind: 'user'
  text: string
  images?: TranscriptImage[]
  /** 回放时因体积预算被省掉的张数。 */
  omittedImages?: number
}
```

`codec.ts` 的 user 分支（原来是和 assistant 等共用一个 case，现在拆出来）：

```ts
    case 'user': {
      if (typeof it.text !== 'string') return null
      const item: TranscriptItem = { ...base, kind: 'user', text: it.text }
      // 逐项校验、坏的丢单项不丢整条：客户端与服务端版本不一定同步，
      // 一条脏图不该让整句提问消失
      if (Array.isArray(it.images)) {
        const images = it.images.filter(
          (v): v is TranscriptImage =>
            !!v && typeof v === 'object' &&
            typeof (v as TranscriptImage).mediaType === 'string' &&
            typeof (v as TranscriptImage).base64 === 'string',
        )
        if (images.length > 0) item.images = images
      }
      if (typeof it.omittedImages === 'number' && it.omittedImages > 0) {
        item.omittedImages = it.omittedImages
      }
      return item
    }
```

`UserBubble.tsx`：

```tsx
import type { TranscriptImage } from '../types'

interface Props {
  text: string
  images?: TranscriptImage[]
  omittedImages?: number
}

export function UserBubble({ text, images, omittedImages }: Props) {
  return (
    <div className="row row--user" data-testid="user-bubble">
      <div className="bubble bubble--user">
        {images?.map((img, i) => (
          <img
            key={i}
            className="bubble__image"
            data-testid={`user-image-${i}`}
            src={`data:${img.mediaType};base64,${img.base64}`}
            alt="粘贴的图片"
          />
        ))}
        {text !== '' && (
          <div className="bubble__text" data-testid="user-bubble-text">
            {text}
          </div>
        )}
        {omittedImages !== undefined && omittedImages > 0 && (
          <div className="bubble__omitted">{omittedImages} 张图已省略</div>
        )}
      </div>
    </div>
  )
}
```

`styles.css`：

```css
/* 气泡里的图。限宽不限高：长截图会很高，但那是用户自己粘的，不该被裁掉 */
.bubble__image {
  display: block;
  max-width: 100%;
  max-height: 320px;
  border-radius: 8px;
  margin: 2px 0;
  object-fit: contain;
}

.bubble__omitted {
  margin-top: 4px;
  font-size: 11px;
  color: var(--text-dim, #8c8c8c);
}
```

`Transcript.tsx` 的 user 分支要用新 props —— `UserBubble` 的 `text` 现在可能为空串（纯图），**`Item` 里不能再假设有文字**。

- [ ] **Step 4: 跑测试**

Run: `cd web && node node_modules/vitest/vitest.mjs run && node node_modules/typescript/bin/tsc -b`
Expected: 全绿 + tsc 退出码 0（vitest 不做类型检查，`tsc -b` 才是构建里真正把关的那一步）

- [ ] **Step 5: 提交**

```bash
git add web/src/types.ts web/src/codec.ts web/src/components/UserBubble.tsx web/src/components/UserBubble.test.tsx web/src/components/Transcript.tsx web/src/styles.css web/src/codec.test.ts
git commit -m "feat(image): 转写区渲染图片

逐项校验、坏的丢单项不丢整条；纯图消息不渲染空文字块；省略的张数要说出来。"
```

---

### Task 9: 回放 —— 读回真图与限流

**Files:**
- Modify: `src/main/kotlin/com/ccoder/ui/MessageRenderer.kt`（`renderPrompt`）
- Modify: `src/test/kotlin/com/ccoder/ui/MessageRendererTest.kt`
- Modify: `src/main/kotlin/com/ccoder/ui/ClaudePanel.kt`（`replayItems`）

**Interfaces:**
- Produces:
  - `internal data class PromptContent(val text: String, val images: List<TranscriptImage>, val omitted: Int)`
  - `internal fun MessageRenderer.renderPrompt(item: JsonObject): PromptContent?`
  - `internal fun replayBudget(images: List<TranscriptImage>, maxCount: Int, maxBytes: Int): Pair<List<TranscriptImage>, Int>`

- [ ] **Step 1: 写失败的测试**

`MessageRendererTest.kt` 追加：

```kotlin
    @Test
    fun `纯图无文字的提问不再被丢掉 —— 这是一条既有 bug 的钉子`() {
        val content = renderPrompt(
            """
            {"type":"user","message":{"content":[
              {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAA"}}
            ]}}
            """
        )
        assertNotNull(content)
        assertEquals("", content!!.text)
        assertEquals(1, content.images.size)
        assertEquals("image/png", content.images[0].mediaType)
    }

    @Test
    fun `图文混排：两样都取到`() {
        val content = renderPrompt(
            """
            {"type":"user","message":{"content":[
              {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAA"}},
              {"type":"text","text":"这报错什么意思"}
            ]}}
            """
        )
        assertEquals("这报错什么意思", content!!.text)
        assertEquals(1, content.images.size)
    }

    @Test
    fun `含工具结果的仍然返回 null`() {
        val content = renderPrompt(
            """
            {"type":"user","message":{"content":[
              {"type":"tool_result","tool_use_id":"t1","content":"out"}
            ]}}
            """
        )
        assertNull(content)
    }

    @Test
    fun `回放预算：超出张数与字节的部分只计数不传数据`() {
        val images = List(5) { TranscriptImage("image/png", "A".repeat(100)) }
        val (kept, omitted) = replayBudget(images, maxCount = 3, maxBytes = 1000)
        assertEquals(3, kept.size)
        assertEquals(2, omitted)
    }

    @Test
    fun `回放预算：字节先超时后面的一律省略`() {
        val images = List(4) { TranscriptImage("image/png", "A".repeat(100)) }
        val (kept, omitted) = replayBudget(images, maxCount = 10, maxBytes = 250)
        assertEquals(2, kept.size)
        assertEquals(2, omitted)
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew test --tests "*MessageRendererTest*" -x buildWebUi --console=plain`
Expected: 编译失败（`PromptContent` 未定义）

- [ ] **Step 3: 实现**

```kotlin
/**
 * 一条真实提问的内容：文字 + 图 + 被省掉的张数。
 *
 * 文字可以是空串（纯图提问是合法的），但**不能没有内容** —— 两者都空时
 * [MessageRenderer.renderPrompt] 返回 null。
 */
internal data class PromptContent(
    val text: String,
    val images: List<TranscriptImage> = emptyList(),
    val omitted: Int = 0,
)

/**
 * 回放预算：按张数**与**字节双重封顶，超出的只计数不传数据。
 *
 * 为什么必须限流：一个图片多的老会话，历史原样推给 JCEF 就是几十 MB 的注入
 * 脚本，界面会卡死几秒甚至白屏。超出的显示成「N 张图已省略」——
 * 说了没显示是遗憾，不说才是欺骗。
 */
internal fun replayBudget(
    images: List<TranscriptImage>,
    maxCount: Int,
    maxBytes: Int,
): Pair<List<TranscriptImage>, Int> {
    val kept = mutableListOf<TranscriptImage>()
    var bytes = 0
    for (img in images) {
        if (kept.size >= maxCount || bytes + img.base64.length > maxBytes) break
        kept += img
        bytes += img.base64.length
    }
    return kept to (images.size - kept.size)
}
```

`renderPrompt` 改成：

```kotlin
    /**
     * 从一条**历史**消息里取出"真实提问"的内容。
     *
     * **只供回放路径调用。** live 路径下用户气泡是 `sendCurrentInput()` 直接推的，
     * 这里再产一次就会变成两条。
     *
     * 必须过滤工具结果：实测最大会话的 247 条 user 消息里，236 条是工具结果，
     * 真实提问只有 11 条。全渲染出来会把转写区淹掉。
     *
     * 返回 null 的条件是**文字与图片都空** —— 纯图提问在过去会被末尾那句
     * `isBlank()` 整条丢掉，回放时那幅图连同消息一起消失，这是一条既有 bug。
     */
    fun renderPrompt(item: JsonObject): PromptContent? {
        if (item.str("type") != "user") return null
        val content = item.obj("message")?.get("content") ?: return null

        // 形式一：纯文本提问
        if (content.isJsonPrimitive && content.asJsonPrimitive.isString) {
            return content.asString.takeIf { it.isNotBlank() }?.let { PromptContent(it) }
        }
        if (!content.isJsonArray) return null

        val blocks = content.asJsonArray.filter { it.isJsonObject }.map { it.asJsonObject }

        // 含工具结果即判定为工具回合。真实提问不会和 tool_result 混在一条里
        if (blocks.any { it.str("type") == "tool_result" }) return null

        val text = blocks
            .filter { it.str("type") == "text" }
            .mapNotNull { it.str("text") }
            .joinToString("\n")
            .trim()

        val images = blocks
            .filter { it.str("type") == "image" }
            .mapNotNull { b ->
                val src = b.obj("source") ?: return@mapNotNull null
                if (src.str("type") != "base64") return@mapNotNull null
                val data = src.str("data") ?: return@mapNotNull null
                TranscriptImage(src.str("media_type") ?: "image/png", data)
            }

        if (text.isEmpty() && images.isEmpty()) return null
        return PromptContent(text, images)
    }
```

- [ ] **Step 4: 接进回放**

`ClaudePanel.replayItems`（预算用两个**递减**的计数器，跨条目累计）：

```kotlin
        // 回放预算：图片多的老会话不能整包推给 JCEF（spec §7.1）。
        // 两个计数器跨条目递减 —— 限的是**整次回放**，不是单条消息
        var budgetCount = MAX_REPLAY_IMAGES
        var budgetBytes = MAX_REPLAY_BYTES

        for (item in items) {
            MessageRenderer.renderPrompt(item)?.let { content ->
                val (kept, omitted) = replayBudget(content.images, budgetCount, budgetBytes)
                budgetCount -= kept.size
                budgetBytes -= kept.sumOf { it.base64.length }
                pushOp(toOp(RenderItem.UserText(content.text, kept, omitted)))
                rendered++
            }
            val rest = MessageRenderer.render(SidecarMessage.Event(item))
            rest.forEach { pushOp(toOp(it)) }
            rendered += rest.size
        }
```

常量放在 `ClaudePanel` 的 companion 里：

```kotlin
        /**
         * 回放一次最多带回多少张图 / 多少 base64 字符。
         *
         * **这两个数是估的**（一张图 base64 约 90–300KB，实测），上线后拿图片多的
         * 会话量一遍再定。限流本身不是可选项：整包推给 JCEF 就是几十 MB 的注入
         * 脚本，界面会卡死几秒甚至白屏。
         */
        private const val MAX_REPLAY_IMAGES = 12
        private const val MAX_REPLAY_BYTES = 6_000_000
```

`toOp` 的 `UserText` 分支同步改成带图（live 侧那一处在 Task 10 改）：

```kotlin
            is RenderItem.UserText ->
                TranscriptOp.Append(
                    TranscriptItem.User(
                        nextMessageId(), now(), item.text, item.images, item.omittedImages,
                    )
                )
```

- [ ] **Step 5: 跑测试**

Run: `./gradlew test -x buildWebUi --console=plain`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/com/ccoder/ui/MessageRenderer.kt src/main/kotlin/com/ccoder/ui/ClaudePanel.kt src/test/kotlin/com/ccoder/ui/MessageRendererTest.kt
git commit -m "feat(image): 回放读回真图，并按体积预算限流

顺带修一条既有 bug：renderPrompt 末尾的 isBlank() 会把纯图提问整条判成 null，
回放时那幅图连同消息一起消失。"
```

---

### Task 10: 发送接线（含暂存与纯图消息）

**Files:**
- Modify: `src/main/kotlin/com/ccoder/ui/ClaudePanel.kt`

**Interfaces:**
- Consumes: Task 1–9 全部（尤其 Task 7 的 `RenderItem.UserText(text, images, omittedImages)` 与 Task 5 的 `Protocol.encodeSend(id, text, images)`）

- [ ] **Step 1: 发送路径**

`sendCurrentInput()`：

```kotlin
    private fun sendCurrentInput() {
        val text = input.text.trim()
        if (!hasSendableContent(text, attachList.size)) return

        // 先摘下来再发：发送途中用户可能又粘了图，那属于下一条消息
        val images = attachList
        input.text = ""
        attachList = emptyList()
        attachments.clear()

        pushOp(toOp(RenderItem.UserText(text, images)))

        if (!ready) {
            if (proc != null) stopSession()
            disconnected = false
            pendingFirstMessage = PendingSend(text, images)
            refreshMainButton()
            startSession()
            return
        }

        client?.sendLine(Protocol.encodeSend(nextId(), text, images))
        setBusy(true)
    }
```

- [ ] **Step 2: 暂存结构改带图**

字段声明：`private var pendingFirstMessage: PendingSend? = null`，配：

```kotlin
    /**
     * 会话就绪前暂存的第一次发送。
     *
     * **必须连图一起存**：只存文字的话，"粘了图 → 立刻发送"时那幅图会静默消失，
     * 而界面上气泡已经把它显示出来了（pushOp 是同步发生的）——
     * 那正是 spec §7.5 反对的静默丢失。
     */
    private data class PendingSend(val text: String, val images: List<ImageAttachment>)
```

Ready 分支里的补发：

```kotlin
                        pendingFirstMessage?.let { pending ->
                            pendingFirstMessage = null
                            client?.sendLine(
                                Protocol.encodeSend(nextId(), pending.text, pending.images)
                            )
                        }
```

- [ ] **Step 3: 跑测试**

Run: `./gradlew test -x buildWebUi --console=plain`
Expected: PASS

- [ ] **Step 4: 提交**

```bash
git add src/main/kotlin/com/ccoder/ui/ClaudePanel.kt
git commit -m "feat(image): 发送路径带图，暂存结构一起改

暂存的第一次发送必须连图一起存 —— 只存文字会让用户粘的图静默消失。"
```

---

### Task 11: 集成验证

**Files:** 无（只跑与看）

- [ ] **Step 1: 全量测试三侧**

```bash
./gradlew test -x buildWebUi --console=plain
cd sidecar && node --test test/
cd ../web && node node_modules/vitest/vitest.mjs run && node node_modules/typescript/bin/tsc -b
```
Expected: 三侧全绿，tsc 退出码 0

- [ ] **Step 2: 布局探针**

Run: `cd web && node tools/layout-probe.mjs`
Expected: `[通过]`。**附件条只在有图时出现**，所以探针里那条 `fits` 场景的 `tool=` 数字不该变

- [ ] **Step 3: 打包并安装**

```bash
./gradlew buildPlugin -x buildWebUi --console=plain
```
把 `build/distributions/CCoder-0.2.5.zip` 里的 `CCoder/` 解压覆盖到
`~/AppData/Roaming/JetBrains/PyCharm2026.1/plugins/`，重启 PyCharm。

- [ ] **Step 4: 手工冒烟（五种，缺一不可）**

| # | 操作 | 期望 |
|---|---|---|
| 1 | 截图工具截图 → Ctrl+V | 输入框上方出现缩略图，✕ 可删 |
| 2 | 粘图 + 打字 → 发送 | 转写区气泡里显示那张图 + 文字 |
| 3 | **只粘图不打字** → 发送 | 能发出去；气泡里只有图没有空文字块 |
| 4 | 拖两个 png 进来 | 两张缩略图；拖 .kt 文件进来则毫无反应 |
| 5 | 发图后重启 IDE、恢复该会话 | 那张图还在，不是占位 |

- [ ] **Step 5: 记录实测数字**

把 Task 9 里那两个"估的"常量用真实数据校验一遍：找一个图片多的会话，量 `loadHistory` 的字节数与本机 JCEF 的表现，把注释里的"估的"换成量出来的数。

---

## 明确不做的（spec §13）

- 不做图片点击放大 / 查看原图
- 不做图片在文字中间的混排（附件统一挂在气泡文字上方）
- 不做内置截图器
- 不做图片的持久化缓存（回放靠会话历史里的 base64）

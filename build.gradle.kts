// 显式 import：脚本作用域里 `java` 会被解析成 Gradle 的 java 扩展
// （JavaPluginExtension），写成 java.util.zip.ZipFile 会报 Unresolved reference 'util'
import java.io.File
import java.security.MessageDigest
import java.util.TreeMap
import java.util.zip.ZipFile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.18.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        pycharm("2025.3.1.1")
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // 测试执行器初始化时需要 org.junit.rules.TestRule（JUnit 4 的类）。
    // 缺了会以 "Could not start Gradle Test Executor 1" 失败，且不产出任何结果 XML。
    // 我们的用例都是纯 JVM 单测，不需要平台测试框架，但执行器本身要这个类在 classpath 上。
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // 不设 untilBuild —— 让插件在未来的 IDE 版本中仍可安装（spec §11.4）
            untilBuild = provider { null }
        }
    }

    // 发布到 JetBrains Marketplace（./gradlew publishPlugin）。
    //
    // token 是密码，**不放仓库**：先读 GRADLE_USER_HOME 下 gradle.properties 里的
    // intellijPlatformPublishingToken，没有再读 2.x 的默认来源 PUBLISH_TOKEN 环境变量。
    //
    // 注意是 GRADLE_USER_HOME，**不是 ~/.gradle**：本机把它设成了
    // C:\Users\CY\scoop\apps\gradle\current\.gradle（用户级环境变量），文件放
    // ~/.gradle 里 Gradle 根本不看 —— 实测踩过，探针报 present=false。而 current
    // 是指向 9.7.0 的软链，所以 scoop 更新 Gradle 之后要重放一次。
    //
    // 用文件而不是环境变量，是因为守护进程会缓存环境变量，
    // 那样每加一个变量都得 ./gradlew --stop 一次（PATH 上已经踩过这个坑）。
    //
    // 末尾那个 orElse("") 是必须的：token 是 required 属性，取不到值会让**整个构建**
    // 在配置阶段就失败 —— 连 ./gradlew test 都跑不了。给个空串，则只有真的执行
    // publishPlugin 时才会因为 token 为空而报错。
    //
    // 首版必须网页手传（市场规矩），从第 2 版起才走这里。
    publishing {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
            .orElse(providers.environmentVariable("PUBLISH_TOKEN"))
            .orElse("")
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()

    // 界面语言钉死。用例断言的就是**中文文案本身**（文案是这个仓库的产品），
    // 跟着 IDE 语言走会让同一份用例在中英文机器上得到不同结果 —— 那是"在我机器上是绿的"。
    // IdeLocale.detect() 先看这个系统属性（放在平台之前），所以测试永远不会去碰平台。
    // 要看英文那遍：./gradlew test -PtestLang=en
    systemProperty("ccoder.lang", providers.gradleProperty("testLang").getOrElse("zh"))
}

tasks {
    // 搜索选项的构建需要启动 IDE，对插件开发无必要且拖慢构建
    buildSearchableOptions { enabled = false }
}

// ---- sidecar 打包 ----
// sidecar 的 node_modules 无法被 node 从 jar 内直接运行（运行时由
// SidecarExtractor 提取到磁盘），但必须随插件分发。

val sidecarDir = layout.projectDirectory.dir("sidecar")

/**
 * Gradle 的资源处理会按内置默认排除集丢弃一批文件（VCS 元数据、编辑器临时文件）。
 * 清单是照磁盘目录生成的，必须同步剔除这些路径，否则清单会列出包内不存在的条目。
 *
 * 后果不是"少个无关紧要的文件"：SidecarLocator 读到清单列了、包里却没有的条目
 * 会直接抛 SidecarNotFoundException，sidecar 根本起不来——而构建全程正常。
 * 实测 sidecar/node_modules/fast-uri/.gitattributes 就命中了这条。
 *
 * 下列集合是**实测**得出的（2026-09-11 探针），不是照抄 Ant 文档：
 * .arch-ids、.darcs、.github、.eslintignore、.npmignore 实测**不会**被丢弃，
 * 故不在列。若 Gradle 将来改变行为，buildPlugin 末尾的一致性校验会拦住。
 */
fun isDroppedByPackaging(relative: String): Boolean {
    val segments = relative.split('/')
    // 目录名与同名文件都要拦：探针里 .svn/.git 作为文件时同样被丢弃
    if (segments.any { it in setOf(".git", ".svn", ".hg", ".bzr", "CVS", "SCCS") }) return true

    val name = segments.last()
    if (name in setOf(
            ".cvsignore", ".gitattributes", ".gitignore", ".gitmodules",
            ".hgignore", ".hgsub", ".hgsubstate", ".hgtags", ".bzrignore",
        )
    ) return true

    return name.endsWith("~") ||
        (name.startsWith("#") && name.endsWith("#")) ||
        name.startsWith(".#") ||
        (name.startsWith("%") && name.endsWith("%")) ||
        name.startsWith("._")
}

/**
 * 生成 sidecar 资源。
 *
 * 类加载器只能按条目读取 jar，无法遍历目录，所以必须预先列出全部文件
 * （manifest.txt），供 SidecarLocator 使用。
 *
 * 排除两类内容：
 *   1. 平台原生二进制包（@anthropic-ai/claude-agent-sdk-<platform>，约 212M）
 *      —— 由运行时解析用户已装的 claude 替代（设计文档 §8.2）
 *   2. .d.ts 类型声明 —— 只服务于 TypeScript 编译，运行时不需要
 */
val generateSidecarManifest by tasks.registering {
    val srcDir = sidecarDir.asFile
    val outDir = layout.buildDirectory.dir("generated/sidecar-resources")
    inputs.dir(srcDir)
    outputs.dir(outDir)

    doLast {
        val dest = outDir.get().asFile
        dest.deleteRecursively()
        val sidecarDest = dest.resolve("sidecar")
        sidecarDest.mkdirs()

        val entries = mutableListOf<String>()

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }

        // 逐个文件的内容摘要，最后按路径排序汇总成整包指纹。
        // 用 TreeMap 是为了与文件系统遍历顺序无关 —— 顺序一变指纹就变，
        // 会导致每次启动都无谓地重新提取
        val fileDigests = TreeMap<String, String>()

        fun emit(relative: String, bytes: ByteArray) {
            val target = sidecarDest.resolve(relative)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            entries += relative
            fileDigests[relative] = sha256(bytes)
        }

        fun emitFile(relative: String, file: File) {
            if (!file.isFile) return
            if (file.name.endsWith(".d.ts")) return
            // 注意用尾随连字符精确区分：claude-agent-sdk 自身不带平台后缀
            if (file.invariantSeparatorsPath.contains("claude-agent-sdk-")) return
            // Gradle 资源处理会按内置默认排除集丢弃这些文件（见 isDroppedByPackaging）。
            // 不在这里同步剔除，清单就会列着包内不存在的路径，运行时直接炸。
            if (isDroppedByPackaging(relative)) return
            emit(relative, file.readBytes())
        }

        // 根目录的 .js / .mjs 与 package.json **统统进包**，不列白名单。
        //
        // 2026-09-15 的事故就是白名单造成的：history-images.js 加进来之后这里没跟着改，
        // 于是包里的 index.js `import './history-images.js'` 找不到文件。构建全绿、
        // 测试也全绿（测试跑的是源码目录，那儿文件在），而插件在用户机器上一启动就是
        // ERR_MODULE_NOT_FOUND —— 整个 sidecar 起不来。
        // 白名单天生会漏，扫目录不会。tools/ 与 test/ 在子目录里，不受影响。
        val rootFiles = (srcDir.listFiles() ?: emptyArray())
            .filter {
                it.isFile &&
                    (it.name.endsWith(".js") || it.name.endsWith(".mjs") || it.name == "package.json")
            }
            .sortedBy { it.name }
        for (f in rootFiles) {
            emitFile(f.name, f)
        }

        val pkg = srcDir.resolve("package.json")
        val version = if (pkg.isFile) {
            Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
                .find(pkg.readText())?.groupValues?.get(1) ?: "0.0.0"
        } else "0.0.0"
        emit("version.txt", version.toByteArray())

        val nodeModules = srcDir.resolve("node_modules")
        if (nodeModules.isDirectory) {
            nodeModules.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relative = "node_modules/" +
                        file.relativeTo(nodeModules).invariantSeparatorsPath
                    emitFile(relative, file)
                }
        }

        // manifest 自身不进清单 —— 它由 ProductionSidecarResolver 优先读取
        sidecarDest.resolve("manifest.txt").writeText(entries.joinToString("\n"))

        // 内容指纹：SidecarExtractor 据此判断"版本号没变但内容变了"，
        // 否则新的 sidecar 永远不会被提取出来（旧的一直在用，且完全无声）。
        // 同样不进清单：它不是 sidecar 的文件，只是给提取器看的元数据。
        val digest = MessageDigest.getInstance("SHA-256")
        for ((path, hash) in fileDigests) {
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(hash.toByteArray(Charsets.UTF_8))
        }
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        sidecarDest.resolve("fingerprint.txt").writeText(fingerprint)

        logger.lifecycle("sidecar 资源已打包：${entries.size} 个文件，版本 $version，指纹 ${fingerprint.take(12)}")
    }
}

// ---- web 前端构建 ----
// 用 Vite 的代价是构建里多一个 npm 步骤，所以给一个开关：
// 只改 Kotlin 时用 -PskipWeb 跳过，不必每次都等前端构建。

val webDir = layout.projectDirectory.dir("web")

val buildWebUi by tasks.registering {
    val src = webDir.asFile
    val outDir = layout.buildDirectory.dir("generated/webui-resources")
    val skip = providers.gradleProperty("skipWeb").isPresent

    inputs.dir(src.resolve("src"))
    inputs.file(src.resolve("index.html"))
    inputs.file(src.resolve("package.json"))
    inputs.file(src.resolve("vite.config.ts"))
    // skipWeb 必须声明为输入。否则切换该开关时 Gradle 认为任务是最新的：
    // 用 -PskipWeb 跑一次会留下空的输出目录，之后不带它构建仍会沿用那份空输出，
    // 插件里就静默少了整个前端。
    inputs.property("skipWeb", skip.toString())
    outputs.dir(outDir)

    doLast {
        val dest = outDir.get().asFile
        dest.deleteRecursively()
        // 放进 webui/ 子目录而非根目录：ClaudeTranscriptView 读的是
        // /webui/index.html，且根目录平铺会与其它资源有重名风险
        val webuiDir = dest.resolve("webui")
        webuiDir.mkdirs()

        if (skip) {
            // 刻意不留半成品：没有 webui/index.html 时 ClaudeTranscriptView
            // 会显示"资源缺失"，那比一个过期的 UI 诚实
            logger.lifecycle("已跳过 web 构建（-PskipWeb）")
            return@doLast
        }

        // Windows 上 npm 是 npm.cmd，直接写 "npm" 会 CreateProcess error=2
        val npm = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"

        // 用 ProcessBuilder 而非 project.exec{}：后者在 Gradle 9 已从 Project API
        // 移除，而注入 ExecOperations 需要额外的抽象任务类，对这里不值当。
        fun run(vararg cmd: String) {
            val process = ProcessBuilder(*cmd)
                .directory(src)
                .inheritIO()
                .start()
            val code = process.waitFor()
            require(code == 0) { "命令失败（退出码 $code）：${cmd.joinToString(" ")}" }
        }

        if (!src.resolve("node_modules").isDirectory) {
            // 用 ci 而非 install：lockfile 已入库，构建应当可复现。
            // 只在 node_modules 缺失时执行，不会每次构建都重装。
            logger.lifecycle("web/node_modules 不存在，执行 npm ci…")
            run(npm, "ci", "--no-audit", "--no-fund")
        }

        run(npm, "run", "build")

        val dist = src.resolve("dist")
        require(dist.isDirectory) { "web 构建未产出 dist/ 目录" }
        dist.copyRecursively(webuiDir, overwrite = true)

        val index = webuiDir.resolve("index.html")
        require(index.isFile) { "web 构建未产出 index.html" }
        logger.lifecycle("web UI 已打包：${index.length()} 字节")
    }
}

// srcDir 接受 TaskProvider 并自动接上任务依赖，无需手动 dependsOn
sourceSets.named("main") {
    resources.srcDir(generateSidecarManifest)
    resources.srcDir(buildWebUi)
}

/**
 * 交付包一致性校验。
 *
 * 校验最终的 zip（用户实际安装的那个）里，sidecar/manifest.txt 所列的每一条
 * 都确实存在。这一条如果漏了，症状是"插件装上后 sidecar 完全起不来"，
 * 而构建、测试全程绿灯——2026-09-11 的 .gitattributes 事故正是这个形态：
 * 一个 80 字节的 VCS 元数据文件让整个插件在用户机器上失效。
 *
 * 放在 doLast 而非独立任务：让它无法被绕过。任务本身 UP-TO-DATE 时不执行，
 * 但那时 zip 未被重建，上次执行已经校验过。
 */
tasks.named<Zip>("buildPlugin") {
    doLast {
        val zipFile = archiveFile.get().asFile
        var jarName: String? = null
        var manifest: List<String> = emptyList()
        val jarEntries = mutableSetOf<String>()

        // 根目录那几个 .js 的正文 —— 末尾那道"import 指的文件在不在包里"的反向检查要用
        val sidecarSources = mutableMapOf<String, String>()

        ZipFile(zipFile).use { zip ->
            val jarEntry = zip.entries().toList()
                .firstOrNull { it.name.endsWith(".jar") && !it.name.contains("searchableOptions") }
                ?: error("插件包内找不到主 jar：${zipFile.name}")
            jarName = jarEntry.name

            val tmp = File.createTempFile("ccoder-verify", ".jar")
            try {
                tmp.outputStream().use { out -> zip.getInputStream(jarEntry).copyTo(out) }
                ZipFile(tmp).use { jar ->
                    // 用 toList() 而非直接 forEach：Enumeration 上的 forEach
                    // 重载在脚本作用域里推断不出 lambda 类型
                    jarEntries.addAll(jar.entries().toList().map { it.name })
                    val mf = jar.getEntry("sidecar/manifest.txt")
                        ?: error("插件包内缺少 sidecar/manifest.txt，sidecar 无法提取")
                    manifest = jar.getInputStream(mf)
                        .bufferedReader().readLines().filter { it.isNotBlank() }

                    // 只看 sidecar 根目录那几个：子目录里是 node_modules，
                    // 它们不 import 同级的运行时代码
                    for (e in jar.entries().toList()) {
                        val rel = e.name.removePrefix("sidecar/")
                        if (!e.name.startsWith("sidecar/") || rel.contains('/')) continue
                        if (!rel.endsWith(".js") && !rel.endsWith(".mjs")) continue
                        sidecarSources[rel] = jar.getInputStream(e).bufferedReader().readText()
                    }
                }
            } finally {
                tmp.delete()
            }
        }

        // 指纹也必须在包内：ProductionSidecarResolver 缺了它会直接抛异常，
        // 插件在用户机器上完全起不来。构建期拦住比运行时炸好
        if ("sidecar/fingerprint.txt" !in jarEntries) {
            error("交付包缺少 sidecar/fingerprint.txt —— sidecar 提取缓存无法判断内容是否变化")
        }

        val missing = manifest.filter { "sidecar/$it" !in jarEntries }
        if (missing.isNotEmpty()) {
            error(
                "交付包不一致：$jarName 内 sidecar/manifest.txt 列了 ${missing.size} " +
                    "个包中不存在的条目，例如 ${missing.take(5)}。\n" +
                    "这会让 sidecar 在用户机器上直接启动失败。请检查 " +
                    "generateSidecarManifest 的剔除规则是否与打包行为一致。"
            )
        }
        // 反向检查：包内每个根 .js 里 `from './x.js'` 指的文件，必须也在包里。
        //
        // 上面那条只查"清单里列的都在包里"，查不出"代码用了却没人列" —— 而后者
        // 正是 2026-09-15 history-images.js 那次事故的形态：清单没列它，于是没人
        // 发现它不在包里，直到用户在 IDE 里看见「sidecar 进程已退出（退出码 1）」。
        // 正向查不出反向的洞，这一条补的就是那个方向。
        val relativeImport = Regex("""(?:from|import|require)\s*\(?\s*['"]\./([^'"]+)['"]""")
        val missingImports = sidecarSources
            .flatMap { (file, text) ->
                relativeImport.findAll(text)
                    .map { it.groupValues[1] }
                    .filter { "sidecar/$it" !in jarEntries }
                    .map { "$file 里 import 了 ./$it" }
                    .toList()
            }
            .distinct()
        if (missingImports.isNotEmpty()) {
            error(
                "交付包不一致：sidecar 的代码 import 了包里没有的文件 —— " +
                    missingImports.joinToString("；") + "。\n" +
                    "用户在 IDE 里看到的会是「sidecar 进程已退出（退出码 1）」加 " +
                    "ERR_MODULE_NOT_FOUND，整个插件起不来。\n" +
                    "查 generateSidecarManifest 的根目录扫描规则，或那个文件是不是在子目录里没被收进来。"
            )
        }

        // [verify] 前缀是 ASCII 的：终端编码不一致时中文日志会变乱码，
        // 校验结果必须无论如何都读得出来
        logger.lifecycle("[verify] OK: manifest ${manifest.size} entries, all present in $jarName")
    }
}

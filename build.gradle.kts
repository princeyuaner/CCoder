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
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
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
 * 生成 sidecar 资源。
 *
 * 类加载器只能按条目读取 jar，无法遍历目录，所以必须预先列出全部文件
 * （manifest.txt），供 ProductionSidecarResolver 使用。
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

        fun emit(relative: String, bytes: ByteArray) {
            val target = sidecarDest.resolve(relative)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            entries += relative
        }

        fun emitFile(relative: String, file: java.io.File) {
            if (!file.isFile) return
            if (file.name.endsWith(".d.ts")) return
            // 注意用尾随连字符精确区分：claude-agent-sdk 自身不带平台后缀
            if (file.invariantSeparatorsPath.contains("claude-agent-sdk-")) return
            emit(relative, file.readBytes())
        }

        for (name in listOf(
            "index.js", "session.js", "env.js", "claude-path.js", "ndjson.js", "package.json"
        )) {
            emitFile(name, srcDir.resolve(name))
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
        logger.lifecycle("sidecar 资源已打包：${entries.size} 个文件，版本 $version")
    }
}

// srcDir 接受 TaskProvider 并自动接上任务依赖，无需手动 dependsOn
sourceSets.named("main") {
    resources.srcDir(generateSidecarManifest)
}

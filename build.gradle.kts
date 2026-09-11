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

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BF1AdminTool"
include(":app")

// Windows 上 JDK launcher 以本地编码（如 GBK）解析 @argfile，而 Gradle 以 UTF-8 写入。
// 项目路径含非 ASCII 字符时，test worker 的 classpath 条目会乱码，
// 导致 testDebugUnitTest 报 ClassNotFoundException。
// 把 build 目录挪到纯 ASCII 路径可规避。在 ~/.gradle/gradle.properties 中设置
// bf1.buildDir 启用；未设置时行为完全不变（CI / 其他机器不受影响）。
//
// 注意：该路径必须与项目位于同一盘符。跨盘符（项目在 D:、build 在 C:）会让
// KSP 抛 "lateinit property cleanFilenames has not been initialized"。
// 只在项目路径确实含非 ASCII 字符时才启用：放在纯英文目录的 checkout（例如
// worktree）本来就没这个问题，若也跟着重定向，多个 checkout 会争抢同一个
// build 目录，增量状态互相污染。
val rootPathIsAscii = rootDir.absolutePath.all { it.code < 128 }
val asciiBuildRoot: String? = providers.gradleProperty("bf1.buildDir").orNull
if (!rootPathIsAscii && asciiBuildRoot != null) {
    gradle.beforeProject {
        val relative =
            if (path == ":") "root"
            else path.removePrefix(":").replace(':', '/')
        layout.buildDirectory.set(File(asciiBuildRoot, relative))
    }
}

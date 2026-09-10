// 国内镜像只在本地构建时启用。
// GitHub Actions 等境外环境下 maven.aliyun.com 的 google / gradle-plugin 镜像会返回 HTTP 502，
// 而 Gradle 在插件解析过程中遇到仓库 5xx 会直接判定本次解析失败、不再继续尝试后续仓库，
// 因此 CI 自加入工作流起就一直卡在 "Plugin [id: 'com.google.devtools.ksp'] was not found"。
// CI 环境（GitHub Actions 会设置 CI=true）改为只使用官方仓库。
pluginManagement {
    repositories {
        if (System.getenv("CI") == null) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") == null) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Nekobot"
include(":app")

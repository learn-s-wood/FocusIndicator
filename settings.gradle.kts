pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FocusIndicator"

// 功能库：焦点飞框容器与指示器绘制层
include(":focusindicator")
// 示例 Demo：四种典型用法 + RecyclerView 网格
include(":app")
 
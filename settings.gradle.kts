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
// 可选伴生库：滚动列表的“焦点不动、列表动”跟随编排
include(":focusindicator-follow")
// 示例 Demo：四种典型用法 + RecyclerView 网格
include(":app")
 
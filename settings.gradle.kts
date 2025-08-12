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
        maven(url = "https://jitpack.io")
        maven(url = "http://raw.github.com/saki4510t/libcommon/master/repository/") {
            isAllowInsecureProtocol = true
        }
    }
}

rootProject.name = "YOLOv8 TfLite"
include(":app")

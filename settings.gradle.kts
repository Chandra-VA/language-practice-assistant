pluginManagement {
    repositories {
        if (providers.gradleProperty("wordmatch.useMirror").orNull == "true") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.gradleProperty("wordmatch.useMirror").orNull == "true") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "LanguagePracticeAssistant"
include(":app")

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
        // Pleos 그룹은 공식 Maven에서만 받는다 (공개 저장소의 동명 아티팩트로 대체되는 의존성 혼동 방지)
        exclusiveContent {
            forRepository { maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") } }
            filter { includeGroup("ai.pleos.playground") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "smart-journey-android"
include(":app")

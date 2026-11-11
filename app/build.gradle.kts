import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.smartjourney.vehicle"
    compileSdk = 35
    useLibrary("android.car") // CarUxRestrictionsManager (주행 규제 상태)

    defaultConfig {
        applicationId = "com.smartjourney.vehicle"
        minSdk = 28 // Vehicle SDK 최소 요구 (Pleos 문서: API 28 이상)
        targetSdk = 34
        // 심사 요청·배포 때마다 반드시 올린다 (Pleos 앱 버전 관리 정책). Gleo schema.json의 version과 맞춘다.
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "VEHICLE_SDK_VERSION", "\"${libs.versions.pleosVehicle.get()}\"")
        buildConfigField("String", "NAVI_SDK_VERSION", "\"${libs.versions.pleosNaviHelper.get()}\"")
    }

    // 배포용 서명 (Pleos 앱 보안 가이드: 디버그 인증서 금지). 키 정보는 환경변수나 local.properties에서만 읽는다.
    val localProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
    }
    fun secret(name: String): String? = System.getenv(name) ?: localProps.getProperty(name)
    val releaseStore = secret("SJ_RELEASE_STORE_FILE")
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = file(releaseStore)
            storePassword = secret("SJ_RELEASE_STORE_PASSWORD")
            keyAlias = secret("SJ_RELEASE_KEY_ALIAS")
            keyPassword = secret("SJ_RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 키가 없으면 서명되지 않은 APK가 나온다(심사 제출 불가)
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

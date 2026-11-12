package com.smartjourney.vehicle.core

import android.content.Context
import android.content.pm.PackageManager
import com.smartjourney.vehicle.BuildConfig

/** SDK AAR의 min_version_name과 차량에 설치된 서비스 버전을 비교한다. */
data class ServiceVersion(val label: String, val sdk: String, val installed: String?, val required: String) {
    val ok: Boolean get() = installed != null && versionAtLeast(installed, required)
}

fun readServiceVersions(context: Context): List<ServiceVersion> {
    val packages = context.packageManager
    val metadata = packages.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    fun installed(pkg: String) = runCatching { packages.getPackageInfo(pkg, 0).versionName }.getOrNull()
    fun required(key: String, fallback: String) = metadata?.getString(key) ?: fallback
    return listOf(
        ServiceVersion(
            "Vehicle", BuildConfig.VEHICLE_SDK_VERSION,
            installed("ai.pleos.playground.service.vehicle"),
            required("ai.pleos.playground.service.vehicle.min_version_name", "2.3.13"),
        ),
        ServiceVersion(
            "Navi", BuildConfig.NAVI_SDK_VERSION,
            installed("ai.pleos.playground.service.navi"),
            required("ai.pleos.playground.service.navi.helper.min_version_name", "2.0.1"),
        ),
    )
}

/** 숫자 버전을 비교한다. 빌드 접미사는 제외하며, 알 수 없는 버전은 지원된다고 판단하지 않는다. */
fun versionAtLeast(version: String, required: String): Boolean {
    fun parts(value: String): List<Long>? {
        val numeric = Regex("""^(\d+(?:\.\d+)*)(?:[-+].*)?$""").matchEntire(value.trim())
            ?.groupValues?.get(1) ?: return null
        return numeric.split('.').map { it.toLongOrNull() ?: return null }
    }
    val actual = parts(version) ?: return false
    val minimum = parts(required) ?: return false
    for (i in 0 until maxOf(actual.size, minimum.size)) {
        val a = actual.getOrElse(i) { 0 }
        val b = minimum.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return true
}

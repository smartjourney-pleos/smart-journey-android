package com.smartjourney.vehicle.core

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 주행 규제 상태. 정차 판별이 아닌 플랫폼 UX 규제 기준이며,
 * 규제 해제를 물리적 정차·안전한 주차 완료로 단정하지 않는다 (개발 규칙 1장).
 */
interface DrivingStateGateway {
    /** true면 주행 규제 상태(동의·방문지·인원·설정 편집 제한). */
    val restricted: Flow<Boolean>
}

/**
 * Smart Journey 정차 전용 UI(동의·방문지 선택·인원/대기 설정 편집)에 영향을 주는
 * CarUxRestrictions 세부 플래그 마스크.
 */
internal const val SMART_JOURNEY_UX_RESTRICTION_MASK: Int =
    CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP or
        CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD or
        CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT or
        CarUxRestrictions.UX_RESTRICTIONS_NO_FILTERING

/**
 * `isRequiresDistractionOptimization`과 지역별 정책의 `activeRestrictions` 플래그를 함께 반영한다.
 * 규제 상태를 읽지 못하면(null) 안전을 위해 제한 상태(true)로 간주한다.
 */
internal fun isUiRestricted(
    requiresDistractionOptimization: Boolean?,
    activeRestrictions: Int?,
): Boolean {
    if (requiresDistractionOptimization == null || activeRestrictions == null) return true
    if (requiresDistractionOptimization) return true
    return (activeRestrictions and SMART_JOURNEY_UX_RESTRICTION_MASK) != 0
}

internal fun isUiRestricted(restrictions: CarUxRestrictions?): Boolean =
    if (restrictions == null) true
    else isUiRestricted(restrictions.isRequiresDistractionOptimization, restrictions.activeRestrictions)

/** AAOS `CarUxRestrictionsManager` 구현. 리스너는 매니저당 하나라 한 곳에서만 구독한다. */
class CarUxDrivingStateGateway(context: Context) : DrivingStateGateway {

    private val appContext = context.applicationContext

    init {
        // 생성 시점에 차량 UX 규제 서비스 지원 여부만 확인하고, 실제 구독 연결은 callbackFlow 생명주기에 맞춘다.
        val probeCar = checkNotNull(Car.createCar(appContext)) { "Car 서비스에 연결하지 못함" }
        try {
            check(probeCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) is CarUxRestrictionsManager) {
                "CarUxRestrictionsManager를 가져오지 못함"
            }
        } finally {
            runCatching { probeCar.disconnect() }
        }
    }

    override val restricted: Flow<Boolean> = callbackFlow {
        val car = runCatching { Car.createCar(appContext) }.getOrNull()
        val manager = car?.let { runCatching { it.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager }.getOrNull() }
        if (car == null || manager == null) {
            runCatching { car?.disconnect() }
            // 규제 서비스를 읽지 못하면 제한 상태로 유지한다.
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }

        // 초기 상태를 먼저 읽고 변경 리스너를 등록한다. 읽지 못하면 제한 상태로 본다.
        val initial = runCatching { isUiRestricted(manager.currentCarUxRestrictions) }.getOrDefault(true)
        trySend(initial)
        runCatching {
            manager.registerListener { restrictions ->
                trySend(isUiRestricted(restrictions))
            }
        }.onFailure {
            trySend(true)
        }
        awaitClose {
            runCatching { manager.unregisterListener() }
            runCatching { car.disconnect() }
        }
    }.distinctUntilChanged()
}

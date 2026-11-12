package com.smartjourney.vehicle.core

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** 주행 규제 상태. 정차 판별은 CarUxRestrictions 기준이다 (정책 3). */
interface DrivingStateGateway {
    /** true면 주행 중(입력 제한). */
    val restricted: Flow<Boolean>
}

/** AAOS `CarUxRestrictionsManager` 구현. 리스너는 매니저당 하나라 한 곳에서만 구독한다. */
class CarUxDrivingStateGateway(context: Context) : DrivingStateGateway {

    private val car = checkNotNull(Car.createCar(context)) { "Car 서비스에 연결하지 못함" }
    private val manager = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager

    override val restricted: Flow<Boolean> = callbackFlow {
        // 읽지 못하면 주행 중으로 본다
        trySend(runCatching { manager.currentCarUxRestrictions?.isRequiresDistractionOptimization }.getOrNull() ?: true)
        manager.registerListener { trySend(it.isRequiresDistractionOptimization) }
        awaitClose { manager.unregisterListener() }
    }.distinctUntilChanged()
}

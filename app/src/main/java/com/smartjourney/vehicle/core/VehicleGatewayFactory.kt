package com.smartjourney.vehicle.core

import android.content.Context
import android.util.Log

/** Pleos Vehicle 서비스에 붙을 수 있으면 실제 구현, 아니면 Fake로 대체한다. */
object VehicleGatewayFactory {
    private const val TAG = "VehicleGatewayFactory"

    fun create(context: Context): VehicleGateway =
        runCatching<VehicleGateway> { PleosVehicleGateway(context.applicationContext) }
            .onFailure { Log.w(TAG, "Pleos Vehicle 연결 실패, Fake로 대체합니다", it) }
            .getOrElse { FakeVehicleGateway() }
}

package com.smartjourney.vehicle.core

import android.content.Context
import android.util.Log

/** NaviHelper를 만들 수 있으면 실제 구현, 아니면 Fake로 대체한다. */
object NaviGatewayFactory {
    private const val TAG = "NaviGatewayFactory"

    fun create(context: Context): NaviGateway =
        runCatching<NaviGateway> { PleosNaviGateway(context.applicationContext) }
            .onFailure { Log.w(TAG, "Pleos NaviHelper 연결 실패, Fake로 대체합니다", it) }
            .getOrElse { FakeNaviGateway() }
}

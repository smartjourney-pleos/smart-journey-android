package com.smartjourney.vehicle.domain

// 서버 계약(서버 저장소 docs/server/02_기능_API_명세.md)의 DTO 이름을 따른다.

data class Store(
    val id: Long,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val poiId: String = "",
    val poiSubId: String = "",
)

data class QueueStatus(
    val storeId: Long,
    val aheadTeams: Int,
    val estimatedWaitMin: Int,
    val arrivalBufferMin: Int,
)

enum class WaitingStatus { REGISTERED, ARRIVED, CALLED, SEATED, NO_SHOW, CANCELLED }
enum class ArrivalResult { CONFIRMED, MANUAL, UNCONFIRMED }
enum class ApprovalMethod { TOUCH, VOICE }

data class Waiting(
    val waitingId: String,
    val storeId: Long,
    val ticketNo: Int,
    val partySize: Int,
    val status: WaitingStatus,
    val aheadTeams: Int,
    val estimatedWaitMin: Int,
    val qrPayload: String? = null,
)

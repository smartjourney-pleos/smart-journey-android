package com.smartjourney.vehicle.domain

/** 골든타임 판정 (명세서 5.2). target = 예상 대기 − 도착 여유. */
sealed interface GoldenTime {
    /** 지금 웨이팅을 걸 때다. */
    data object Prompt : GoldenTime
    data class Wait(val minutesUntilPrompt: Int) : GoldenTime
}

fun goldenTime(remainingMin: Int, waitMin: Int, bufferMin: Int): GoldenTime {
    val target = waitMin - bufferMin
    // target <= 0: 대기가 짧아 지금 걸어도 도착 전에 호출되지 않는다
    return if (target <= 0 || remainingMin <= target) GoldenTime.Prompt
    else GoldenTime.Wait(remainingMin - target)
}

/** 한 웨이팅당 알림은 1회. 거절·무응답 뒤에는 남은 시간이 5분 이상 더 줄었을 때 1회만 다시 묻는다. */
class PromptGate(private val repromptAfterMin: Int = 5) {
    private var prompts = 0
    private var declinedAtMin: Int? = null

    fun shouldPrompt(remainingMin: Int): Boolean = when (prompts) {
        0 -> true
        1 -> declinedAtMin?.let { remainingMin <= it - repromptAfterMin } ?: false
        else -> false
    }

    fun onPrompted() {
        prompts++
        declinedAtMin = null
    }

    fun onDeclined(remainingMin: Int) {
        declinedAtMin = remainingMin
    }

    /** 이번 매장은 더 묻지 않는다 (대기 허용 초과 질문을 보류했을 때). */
    fun close() {
        prompts = 2
    }
}

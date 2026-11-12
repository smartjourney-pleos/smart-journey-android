package com.smartjourney.vehicle.voice

import ai.pleos.playground.stt.constant.ErrorType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FinalSpeechListenerTest {
    @Test fun 인식_종료가_최종_텍스트보다_먼저_와도_답변을_버리지_않는다() = runTest {
        val done = CompletableDeferred<Result<String?>>()
        val listener = finalSpeechListener(done)
        listener.onUpdated("등록", false)
        listener.onEndedRecognition()
        assertFalse(done.isCompleted)
        listener.onUpdated("등록해 줘", true)
        assertEquals("등록해 줘", done.await().getOrThrow())
    }

    @Test fun 최종_텍스트_후의_중복_종료와_에러는_답변을_덮어쓰지_않는다() = runTest {
        val done = CompletableDeferred<Result<String?>>()
        val listener = finalSpeechListener(done)
        listener.onUpdated("나중에", true)
        listener.onEndedRecognition()
        listener.onError(ErrorType.MIC_ERROR)
        listener.onUpdated("네", true)
        assertEquals("나중에", done.await().getOrThrow())
    }

    @Test fun 마이크_오류를_무응답과_구분한다() = runTest {
        val done = CompletableDeferred<Result<String?>>()
        finalSpeechListener(done).onError(ErrorType.MIC_ERROR)
        assertEquals("MIC_ERROR", done.await().exceptionOrNull()?.message)
    }
}

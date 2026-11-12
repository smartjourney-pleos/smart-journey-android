package com.smartjourney.vehicle.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.smartjourney.vehicle.MainActivity
import com.smartjourney.vehicle.R
import com.smartjourney.vehicle.engine.Prompt
import com.smartjourney.vehicle.engine.statusLine
import com.smartjourney.vehicle.engine.text
import com.smartjourney.vehicle.graph
import com.smartjourney.vehicle.voice.PleosVoiceGateway
import com.smartjourney.vehicle.voice.VoiceIssue
import com.smartjourney.vehicle.voice.VoicePromptController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 앱의 본체. 내비 뒤에서 엔진을 살려 두고 상태 알림 한 줄(N-01)을 유지한다 (명세서 2.1).
 * 앱이 뒤로 가면 프로세스가 멈출 수 있어, 엔진이 도는 동안은 포그라운드 서비스로 떠 있는다.
 */
class JourneyService : Service() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private val voiceDelegate = lazy { PleosVoiceGateway(this) }
    private val voice by voiceDelegate

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val engine = graph.engine
        Notices.createChannels(this)
        ServiceCompat.startForeground(
            this, Notices.ID_STATUS, Notices.status(this, "여정을 준비하고 있어요"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        val manager = getSystemService(NotificationManager::class.java)

        var wasActive = false
        scope.launch {
            engine.state.map { it.active to it.statusLine() }.distinctUntilChanged().collect { (active, line) ->
                if (active) {
                    wasActive = true
                    manager.notify(Notices.ID_STATUS, Notices.status(this@JourneyService, line))
                } else if (wasActive) {
                    stopSelf()
                }
            }
        }
        val prompts = engine.state.map { it.prompt }.distinctUntilChanged { a, b -> a?.id == b?.id }
        scope.launch {
            prompts.collect { prompt ->
                if (prompt == null) Notices.cancelPrompt(this@JourneyService)
                else Notices.showPrompt(this@JourneyService, prompt)
            }
        }
        // 주행 중 결정은 음성으로만 한다 (명세서 v4 5장). 승인 화면·헤드업·오버레이를 띄우지 않는다.
        scope.launch {
            val controller = VoicePromptController(voice, { engine.state.value.prompt }, graph::respondByVoice,
                onUnavailable = {
                    graph.voiceIssue.value = it
                    Notices.voiceUnavailable(this@JourneyService, it)
                })
            combine(prompts, graph.gleoPromptId) { prompt, gleoId -> prompt?.takeUnless { it.id == gleoId } }
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collectLatest { prompt -> if (prompt != null) controller.handle(prompt) }
        }
        scope.launch {
            // 여정이 복원되지 않았거나 시작되지 않은 상태에서 서비스만 부활한 경우 안전하게 자동 종료
            delay(5_000)
            graph.preparingJourney.first { !it } // 경로 입력이 5초를 넘을 수 있다 (에뮬레이터 7초 확인)
            if (!wasActive && !engine.state.value.active) {
                stopSelf()
            }
        }
        scope.launch { engine.notices.collect { Notices.result(this@JourneyService, it) } }
    }

    /**
     * 프로세스가 LMK 등으로 종료되어도 복원된 여정 상태로 알림과 감시를 유지하도록 START_STICKY 적용 (명세서 4.4).
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        // 듣기/읽기의 finally가 리스너를 제거한 뒤 SDK 연결을 해제한다.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            serviceJob.join()
            if (voiceDelegate.isInitialized()) voice.release()
        }
        Notices.cancelPrompt(this)
        super.onDestroy()
    }

    companion object {
        /** 앱이 뒤에 있으면 Android 12+가 포그라운드 서비스 시작을 막는다. 앱을 죽이지 않고 실패로 돌려준다. */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, JourneyService::class.java))
        }.onFailure { android.util.Log.w("SJ", "여정 서비스를 시작하지 못함", it) }.isSuccess

        fun stop(context: Context) = context.stopService(Intent(context, JourneyService::class.java))
    }
}

object Notices {
    const val ID_STATUS = 1
    private const val ID_PROMPT = 2
    private const val ID_RESULT = 3
    private const val CH_STATUS = "status"
    // 기존 HIGH 채널은 재생성해도 중요도가 내려가지 않아 새 ID를 쓴다. 지도 위 헤드업을 띄우지 않는다.
    private const val CH_PROMPT = "voice_prompt_v1"
    private const val CH_RESULT = "voice_result_v1"
    private val OLD_CHANNELS = listOf("prompt", "result")

    private const val IMMUTABLE = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun createChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CH_STATUS, "여정 상태", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_PROMPT, "음성 응답 요청", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_RESULT, "결과 알림", NotificationManager.IMPORTANCE_LOW),
            ),
        )
        OLD_CHANNELS.forEach { context.getSystemService(NotificationManager::class.java).deleteNotificationChannel(it) }
    }

    /** 상태 한 줄 (N-01). 분 단위로 계속 바뀌므로 헤드업으로 띄우지 않고 알림 센터에만 둔다. */
    fun status(context: Context, line: String): Notification =
        builder(context, CH_STATUS, "스마트 저니", line)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), IMMUTABLE),
            )
            .build()

    /** 시스템 알림 영역에만 남긴다. 전체 화면·헤드업·터치 승인으로 지도를 덮지 않는다. */
    fun showPrompt(context: Context, prompt: Prompt) {
        val text = prompt.text()
        val notification = builder(context, CH_PROMPT, text.title, "${text.detail} · 음성으로 답해 주세요")
            .setOngoing(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_PROMPT, notification)
    }

    fun cancelPrompt(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_PROMPT)
    }

    fun voiceUnavailable(context: Context, issue: VoiceIssue) = result(context, when (issue) {
        VoiceIssue.SPEECH_UNAVAILABLE -> "음성 안내에 연결하지 못했어요. 실행하지 않았어요. 안전하게 정차한 뒤 확인해 주세요"
        VoiceIssue.MICROPHONE_UNAVAILABLE -> "마이크에 연결하지 못했어요. 실행하지 않았어요. 안전하게 정차한 뒤 확인해 주세요"
        VoiceIssue.NO_RESPONSE -> "응답이 없어 실행하지 않았어요. 안전하게 정차한 뒤 확인해 주세요"
        VoiceIssue.UNRECOGNIZED -> "답변을 이해하지 못해 실행하지 않았어요. 안전하게 정차한 뒤 확인해 주세요"
    })

    /** 결과 알림 (C-17). 잠깐 보이고 사라진다. */
    fun result(context: Context, message: String) {
        val notification = builder(context, CH_RESULT, "스마트 저니", message)
            .setTimeoutAfter(5_000)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_RESULT, notification)
    }

    private fun builder(context: Context, channel: String, title: String, text: String) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_journey)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
}

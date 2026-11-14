package com.smartjourney.vehicle.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import com.smartjourney.vehicle.ui.createPromptPopup
import com.smartjourney.vehicle.ui.popupStates
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
import com.smartjourney.vehicle.voice.LateVoiceResultController
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 앱의 본체. 내비 뒤에서 엔진을 살려 두고 상태 알림 한 줄(N-01)을 유지한다 (명세서 2.1).
 * 앱이 뒤로 가면 프로세스가 멈출 수 있어, 엔진이 도는 동안은 포그라운드 서비스로 떠 있는다.
 */
class JourneyService : Service() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private val voiceDelegate = lazy { PleosVoiceGateway(this) }
    private val voice by voiceDelegate
    private val speechMutex = Mutex()
    private val popup by lazy { createPromptPopup(this) }

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
        scope.launch {
            popupStates(engine.state, graph.demo.enabled, graph.signals.restricted, graph.popupViewport,
                graph.appVisible, graph.consented).collect { state ->
                popup.render(state.prompt, state.demo, state.restricted, state.viewport)
            }
        }
        val prompts = engine.state.map { it.prompt }.distinctUntilChanged { a, b -> a?.id == b?.id }
        scope.launch {
            prompts.collect { prompt ->
                if (prompt == null) Notices.cancelPrompt(this@JourneyService)
                else Notices.showPrompt(this@JourneyService, prompt,
                    visualFallback = !android.provider.Settings.canDrawOverlays(this@JourneyService) || graph.popupViewport.value == null)
            }
        }
        // 주행 규제 중 터치 승인은 막고 음성 응답을 유지한다. 팝업은 별도 권한을 허용한 경우에만 표시한다.
        scope.launch {
            val controller = VoicePromptController(voice, { engine.state.value.prompt }, graph::respondByVoice,
                onSpoken = { graph.spokenPromptId.value = it },
                onUnavailable = {
                    graph.voiceIssue.value = it
                    Notices.voiceUnavailable(this@JourneyService, it)
                })
            combine(prompts, graph.voiceProbePromptId) { prompt, probeId -> prompt?.takeUnless { it.id == probeId } }
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collectLatest { prompt -> if (prompt != null) speechMutex.withLock { controller.handle(prompt) } }
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
        scope.launch {
            val announcer = LateVoiceResultController(voice, engine.state, graph.gleoQuietUntil, speechMutex,
                graph.gleoActions::shouldAnnounce, graph.gleoActions::markAnnounced,
                latestText = graph.gleoActions::lateResultText)
            for (result in graph.lateVoiceResults) announcer.announce(result)
        }

    }

    /**
     * 프로세스가 LMK 등으로 종료되어도 복원된 여정 상태로 알림과 감시를 유지하도록 START_STICKY 적용 (명세서 4.4).
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); popup.refresh() }

    override fun onDestroy() {
        popup.close()
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
    // 표시 권한이 없는 기기의 시각 안내는 OS가 관리하는 알림으로 제공한다.
    private const val CH_VISUAL_PROMPT = "waiting_visual_prompt_v1"
    private const val CH_PROMPT = "voice_prompt_v1"
    private const val CH_RESULT = "voice_result_v1"
    private val OLD_CHANNELS = listOf("prompt", "result")

    private const val IMMUTABLE = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun createChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CH_STATUS, "여정 상태", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_PROMPT, "음성 응답 요청", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_VISUAL_PROMPT, "웨이팅 질문 표시", NotificationManager.IMPORTANCE_HIGH),
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

    /** 표시 권한이 없으면 표준 시스템 알림으로 대체한다. 배너 여부는 차량 OS가 결정한다. */
    fun showPrompt(context: Context, prompt: Prompt, visualFallback: Boolean) {
        val text = prompt.text()
        val notification = builder(context, if (visualFallback) CH_VISUAL_PROMPT else CH_PROMPT, text.title, "${text.detail} · 음성으로 답해 주세요")
            .setPriority(if (visualFallback) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setSilent(!visualFallback)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), IMMUTABLE))
            .setTimeoutAfter((prompt.expiresAt?.minus(System.currentTimeMillis()) ?: 60_000L).coerceAtLeast(1))
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

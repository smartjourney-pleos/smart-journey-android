package com.smartjourney.vehicle.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
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
import com.smartjourney.vehicle.ui.ApprovalActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 앱의 본체. 내비 뒤에서 엔진을 살려 두고 상태 알림 한 줄(N-01)을 유지한다 (명세서 2.1).
 * 앱이 뒤로 가면 프로세스가 멈출 수 있어, 엔진이 도는 동안은 포그라운드 서비스로 떠 있는다.
 */
private const val CARD_GRACE_MS = 800L

class JourneyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
            engine.state.map { it.prompt }.distinctUntilChanged { a, b -> a?.id == b?.id }.collectLatest { prompt ->
                Notices.cancelPrompt(this@JourneyService)
                if (prompt == null) return@collectLatest
                // 카드 화면을 바로 띄워 본다. 앱이 보이는 중이거나 "다른 앱 위에 표시"가 허용돼 있으면 뜬다.
                Notices.openCard(this@JourneyService)
                // 백그라운드에서 시스템이 막았으면(Android 14 기본 동작) 헤드업 알림으로 묻는다. 누르면 카드가 열린다.
                delay(CARD_GRACE_MS)
                if (!ApprovalActivity.isShowing) Notices.postPrompt(this@JourneyService, prompt)
            }
        }
        scope.launch { engine.notices.collect { Notices.result(this@JourneyService, it) } }
    }

    // 여정 상태는 메모리에만 있어서, 프로세스가 죽은 뒤 서비스만 되살아나도 할 일이 없다
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        Notices.cancelPrompt(this)
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, JourneyService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, JourneyService::class.java))
    }
}

/** 알림 버튼(등록 / 나중에)을 엔진에 전달한다. */
class PromptActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val promptId = intent.getStringExtra(Notices.EXTRA_PROMPT_ID)
        when (intent.action) {
            Notices.ACTION_APPROVE -> context.graph.engine.approve(promptId = promptId)
            Notices.ACTION_DEFER -> context.graph.engine.defer(promptId = promptId)
        }
    }
}

object Notices {
    const val ID_STATUS = 1
    private const val ID_PROMPT = 2
    private const val ID_RESULT = 3
    private const val CH_STATUS = "status"
    private const val CH_PROMPT = "prompt"
    private const val CH_RESULT = "result"
    const val ACTION_APPROVE = "com.smartjourney.vehicle.action.APPROVE"
    const val ACTION_DEFER = "com.smartjourney.vehicle.action.DEFER"
    const val EXTRA_PROMPT_ID = "com.smartjourney.vehicle.extra.PROMPT_ID"

    private const val IMMUTABLE = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun createChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CH_STATUS, "여정 상태", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_PROMPT, "승인 요청", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_RESULT, "결과 알림", NotificationManager.IMPORTANCE_HIGH),
            ),
        )
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

    private fun cardIntent(context: Context) =
        Intent(context, ApprovalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 승인 카드 화면을 띄워 본다. 백그라운드에서는 시스템이 조용히 막을 수 있다. */
    fun openCard(context: Context) {
        runCatching { context.startActivity(cardIntent(context)) }
    }

    /**
     * 승인 요청 헤드업 알림. Pleos 에뮬레이터에서는 category가 navigation이어야 헤드업으로 뜬다
     * (없으면 중요도가 높아도 안 뜬다). 제목·내용과 버튼 2개가 내비 위 배너로 보인다 (C-04 ⓑ).
     * 배너 자체를 누르면 승인 카드가 열린다.
     */
    fun postPrompt(context: Context, prompt: Prompt) {
        val text = prompt.text()
        val open = PendingIntent.getActivity(context, 1, cardIntent(context), IMMUTABLE)
        val notification = builder(context, CH_PROMPT, text.title, text.detail)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(0, text.primary, action(context, ACTION_APPROVE, prompt.id))
            .apply { text.secondary?.let { addAction(0, it, action(context, ACTION_DEFER, prompt.id)) } }
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_PROMPT, notification)
    }

    fun cancelPrompt(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_PROMPT)
    }

    /** 결과 알림 (C-17). 잠깐 보이고 사라진다. */
    fun result(context: Context, message: String) {
        val notification = builder(context, CH_RESULT, "스마트 저니", message)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(5_000)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_RESULT, notification)
    }

    private fun builder(context: Context, channel: String, title: String, text: String) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_journey)
            .setContentTitle(title)
            .setContentText(text)

    private fun action(context: Context, action: String, promptId: String? = null) = PendingIntent.getBroadcast(
        context,
        if (action == ACTION_APPROVE) 100 else 101,
        Intent(context, PromptActionReceiver::class.java).setAction(action).apply {
            if (promptId != null) putExtra(EXTRA_PROMPT_ID, promptId)
        },
        IMMUTABLE,
    )
}

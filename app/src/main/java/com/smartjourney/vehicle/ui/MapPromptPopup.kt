package com.smartjourney.vehicle.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.smartjourney.vehicle.engine.Prompt
import com.smartjourney.vehicle.engine.WAITING_PROMPT_TIMEOUT_MS
import com.smartjourney.vehicle.graph

/** 사용자에게 다른 앱 위 표시 권한을 받은 뒤 사용하는 웨이팅 카드. 차량/시스템 UI를 복제하지 않는다. */
class MapPromptPopup(private val context: Context) : PromptPopup {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var card: PromptCard? = null
    private var latest: Prompt? = null
    private var demo = false
    private var restricted = true
    private var viewport: PopupViewport? = null
    private val density get() = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    override fun render(prompt: Prompt?, demo: Boolean, restricted: Boolean, viewport: PopupViewport?) {
        latest = prompt; this.demo = demo; this.restricted = restricted; this.viewport = viewport
        close()
        if (prompt == null || prompt.kind != Prompt.Kind.GOLDEN_TIME || viewport == null || !Settings.canDrawOverlays(context)) return
        if (prompt.expiresAt?.let { System.currentTimeMillis() >= it } == true) return
        // 화면 생성 시 질문 ID로 답하고, 최신 규제·동의·만료를 실행 직전에 확인한다.
        val built = PromptCard.create(context, prompt, demo, restricted) { approve ->
            val done = context.graph.respondFromPopup(prompt.id, approve, this.demo, this.restricted)
            if (done) close()
            done
        }
        val card = built.view
        val width = minOf(dp(720), viewport.width - dp(48))
        if (width <= 0) return
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val placement = popupPlacement(viewport, width, card.measuredHeight, dp(24), dp(420)) ?: return
        val params = WindowManager.LayoutParams(placement.width, card.measuredHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = placement.x; y = placement.y
            title = "SmartJourney waiting popup"
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0) // 좌표는 이미 앱 창 영역 안으로 제한했다.
        }
        runCatching {
            manager.addView(card, params); view = card
            val deadline = prompt.expiresAt ?: (System.currentTimeMillis() + WAITING_PROMPT_TIMEOUT_MS)
            built.startCountdown(deadline); this.card = built
        }
            .onFailure { Log.w("SJ-POPUP", "웨이팅 팝업을 표시하지 못했어요") }
    }
    override fun refresh() = render(latest, demo, restricted, viewport)
    override fun close() {
        card?.stop(); card = null
        view?.let { runCatching { manager.removeViewImmediate(it) } }; view = null
    }
}

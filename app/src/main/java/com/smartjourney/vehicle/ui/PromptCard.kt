package com.smartjourney.vehicle.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.smartjourney.vehicle.R
import com.smartjourney.vehicle.engine.Prompt
import com.smartjourney.vehicle.engine.WAITING_PROMPT_TIMEOUT_MS
import com.smartjourney.vehicle.graph

/** 웨이팅 질문 카드. 오버레이 창과 투명 Activity가 같은 View를 쓴다. */
class PromptCard(
    val view: LinearLayout,
    private val ring: CountdownRing,
    private val laterButton: Button,
) {
    private var countdown: ValueAnimator? = null

    /** [deadline]까지 링과 숫자를 10→0으로 줄인다. 0이 되어도 접수는 실행하지 않는다. */
    fun startCountdown(deadline: Long) {
        val remaining = (deadline - System.currentTimeMillis()).coerceIn(0L, WAITING_PROMPT_TIMEOUT_MS)
        countdown = ValueAnimator.ofFloat(remaining.toFloat() / WAITING_PROMPT_TIMEOUT_MS, 0f).apply {
            duration = remaining
            interpolator = LinearInterpolator()
            addUpdateListener {
                val seconds = popupRemainingSeconds(deadline, System.currentTimeMillis())
                ring.update(it.animatedValue as Float, seconds)
                laterButton.contentDescription = "나중에 · ${seconds}초 후 닫힘"
            }
            start()
        }
    }

    fun stop() { countdown?.cancel(); countdown = null }

    companion object {
        /** [onRespond]는 눌린 버튼(true=등록)을 받아 처리됐으면 true를 돌려준다. 최신 규제·동의·만료 확인은 호출한 쪽 몫이다. */
        fun create(context: Context, prompt: Prompt, demo: Boolean, restricted: Boolean, onRespond: (Boolean) -> Boolean): PromptCard {
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val font = ResourcesCompat.getFont(context, R.font.asta_sans)
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val ink = if (night) Color.WHITE else Color.rgb(19, 20, 23)
            val surface = if (night) Color.rgb(19, 20, 23) else Color.WHITE
            val primary = if (night) Color.rgb(224, 225, 230) else Color.BLACK
            val onPrimary = if (night) Color.BLACK else Color.WHITE
            val muted = if (night) Color.rgb(190, 191, 194) else Color.rgb(85, 86, 90)
            fun shape(color: Int, outline: Boolean = false) = GradientDrawable().apply {
                setColor(color); cornerRadius = dp(24).toFloat()
                if (outline) setStroke(dp(1), if (night) Color.DKGRAY else Color.LTGRAY)
            }
            fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
                text = value; textSize = size; setTextColor(color)
                typeface = Typeface.create(font, if (bold) 700 else 400, false)
            }
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(20), dp(24), dp(20))
                background = shape(surface); elevation = dp(12).toFloat()
            }
            if (context.graph.backend.isDemo) card.addView(label("시연용 데이터 · 실제 매장 미접수", 16f, if (night) Color.rgb(255,181,128) else Color.rgb(154,64,0), true))
            card.addView(label("지금 ${prompt.storeName} 웨이팅 걸까요?", 27f, ink, true).apply {
                maxLines = 3; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            card.addView(label("${prompt.partySize}명 · 대기 ${prompt.waitMin}분 · 도착 ${prompt.remainingMin}분 후", 22f, muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            prompt.waitLimitMin?.let { limit -> card.addView(label("설정한 ${limit}분보다 길어요", 20f, muted)) }
            val allowed = popupTouchAllowed(demo, restricted)
            val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            fun action(text: String, approve: Boolean): Button = Button(context).apply {
                this.text = text; textSize = 25f; isAllCaps = false
                typeface = Typeface.create(font, 700, false)
                setTextColor(if (approve) onPrimary else ink)
                background = shape(if (approve) primary else surface, !approve)
                minHeight = dp(72); isEnabled = allowed
                // 다른 창이 버튼을 가린 채 눌린 터치로 접수·보류가 실행되지 않게 한다.
                filterTouchesWhenObscured = true
                alpha = if (allowed) 1f else 0.55f
                setOnClickListener {
                    if (!popupTouchAllowed(demo, restricted)) return@setOnClickListener
                    onRespond(approve)
                }
            }
            val laterButton = action("나중에", false).apply {
                setPadding(dp(20), 0, dp(56), 0)
                stateListAnimator = null; elevation = 0f
            }
            val ring = CountdownRing(context, ink, if (night) Color.DKGRAY else Color.rgb(224, 225, 230), font).apply { elevation = dp(1).toFloat() }
            val later = FrameLayout(context).apply {
                addView(laterButton, FrameLayout.LayoutParams(-1, -1))
                addView(ring, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(20) })
            }
            actions.addView(later, LinearLayout.LayoutParams(0, dp(76), 1f).apply { rightMargin = dp(12) })
            actions.addView(action("등록", true), LinearLayout.LayoutParams(0, dp(76), 1f))
            card.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
            card.addView(label(if (!allowed) "주행 규제 중 · 음성으로 답해 주세요" else if (demo) "데모 신호 · 답하지 않으면 접수 없이 닫혀요" else "답하지 않으면 접수 없이 닫혀요", 16f, muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            return PromptCard(card, ring, laterButton)
        }
    }
}

/** 남은 응답 시간만 표시하며, 0이 되어도 접수를 실행하지 않는다. */
class CountdownRing @JvmOverloads constructor(
    context: Context, private val ink: Int = Color.BLACK, private val track: Int = Color.LTGRAY, font: Typeface? = null,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 1f
    private var seconds = 10
    private val density = resources.displayMetrics.density
    private val ringFont = Typeface.create(font, 700, false)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false }

    fun update(progress: Float, seconds: Int) {
        this.progress = progress.coerceIn(0f, 1f); this.seconds = seconds
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = 2.5f * density
        val inset = stroke / 2 + density
        paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke; paint.strokeCap = Paint.Cap.ROUND
        paint.color = track
        canvas.drawOval(inset, inset, width - inset, height - inset, paint)
        paint.color = ink
        canvas.drawArc(inset, inset, width - inset, height - inset, -90f, 360f * progress, false, paint)
        paint.style = Paint.Style.FILL; paint.typeface = ringFont; paint.textSize = 16f * resources.displayMetrics.scaledDensity
        paint.textAlign = Paint.Align.CENTER
        val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(seconds.toString(), width / 2f, baseline, paint)
    }
}

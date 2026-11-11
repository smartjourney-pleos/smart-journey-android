package com.smartjourney.vehicle.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartjourney.vehicle.engine.Prompt
import com.smartjourney.vehicle.engine.text
import com.smartjourney.vehicle.graph
import com.smartjourney.vehicle.service.Notices
import kotlinx.coroutines.delay

/**
 * 승인 카드 한 장 (V-07 · V-11 · V-12). 엔진이 띄우고, 답하면 바로 닫혀 아래 화면(내비)만 남는다.
 * 주행 중에도 보이는 유일한 화면이라 버튼은 2개까지, 애니메이션 없이 숫자만 바뀐다.
 */
class ApprovalActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = graph.engine
        setContent {
            SmartJourneyTheme {
                val state by engine.state.collectAsStateWithLifecycle()
                val prompt = state.prompt
                // 답했거나 시간이 지나 질문이 사라지면 닫는다
                LaunchedEffect(prompt == null) { if (prompt == null) finish() }
                if (prompt != null) ApprovalCard(prompt, onPrimary = { engine.approve() }, onSecondary = { engine.defer() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isShowing = true
        // 카드가 보이는 동안에는 같은 내용의 알림을 지운다
        Notices.cancelPrompt(this)
    }

    override fun onPause() {
        isShowing = false
        super.onPause()
    }

    companion object {
        /** 카드가 화면에 떠 있는지. 서비스가 헤드업 알림을 따로 올릴지 정할 때 본다. */
        @Volatile
        var isShowing = false
            private set
    }
}

@Composable
private fun ApprovalCard(prompt: Prompt, onPrimary: () -> Unit, onSecondary: () -> Unit) {
    val text = prompt.text()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    // Pleos 앱 영역에서는 아래 화면이 비쳐 보이지 않아, 앱 영역 전체를 카드 한 장으로 쓴다 (C-04 ⓐ)
    Box(Modifier.fillMaxSize().background(Palette.Background).padding(48.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = 1100.dp).fillMaxWidth(), shape = RoundedCornerShape(32.dp), color = Palette.Surface) {
            Column(Modifier.padding(56.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                Text("스마트 저니", style = Type.Label)
                Text(text.title, style = Type.Display)
                Text(text.detail, style = Type.Headline, color = Palette.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    BigButton(text.primary, onPrimary, Modifier.weight(2f).heightIn(min = 120.dp))
                    text.secondary?.let { BigButton(it, onSecondary, Modifier.weight(1f).heightIn(min = 120.dp), primary = false) }
                }
                prompt.expiresAt?.let { expiresAt ->
                    val seconds = ((expiresAt - now) / 1_000).coerceAtLeast(0)
                    Text("${seconds}초 뒤 자동으로 닫혀요", style = Type.Label)
                }
            }
        }
    }
}

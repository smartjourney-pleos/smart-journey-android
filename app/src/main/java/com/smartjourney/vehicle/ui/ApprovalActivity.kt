package com.smartjourney.vehicle.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartjourney.vehicle.engine.Prompt
import com.smartjourney.vehicle.engine.text
import com.smartjourney.vehicle.graph
import com.smartjourney.vehicle.service.Notices

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
                if (prompt != null) {
                    ApprovalCard(prompt, state.restricted, onPrimary = { engine.approve() }, onSecondary = { engine.defer() })
                }
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
private fun ApprovalCard(prompt: Prompt, restricted: Boolean, onPrimary: () -> Unit, onSecondary: () -> Unit) {
    val text = prompt.text()
    // Pleos 앱 영역에서는 아래 화면이 비쳐 보이지 않아, 앱 영역 전체를 카드 한 장으로 쓴다 (C-04 ⓐ)
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Background), contentAlignment = Alignment.Center) {
        // 세로 창(창 1개)처럼 좁을 때는 여백을 줄인다
        val narrow = maxWidth < 900.dp
        Surface(
            Modifier.padding(if (narrow) 16.dp else 48.dp).widthIn(max = 1100.dp).fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = Palette.Surface,
        ) {
            Column(Modifier.padding(if (narrow) 32.dp else 56.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                // 주행 중에는 질문·핵심 숫자·버튼만 남긴다 (텍스트 3줄 이내, 시선을 끄는 변화 없음)
                if (!restricted) Text("스마트 저니", style = Type.Label)
                Text(text.title, style = Type.Display, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(text.detail, style = Type.Headline, color = Palette.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    BigButton(text.primary, onPrimary, Modifier.weight(2f).heightIn(min = 120.dp))
                    text.secondary?.let { BigButton(it, onSecondary, Modifier.weight(1f).heightIn(min = 120.dp), primary = false) }
                }
                // 매초 바뀌는 카운트다운은 시선을 끌어서 고정 문구로 둔다
                if (!restricted && prompt.expiresAt != null) Text("답하지 않으면 1분 뒤 자동으로 닫혀요", style = Type.Label)
            }
        }
    }
}

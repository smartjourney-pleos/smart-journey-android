package com.smartjourney.vehicle

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 배포·심사 전 버전 동기화와 Gleo App Schema 규격(pleos-connect-rules 5장·7장)을 자동으로 검증한다.
 */
class ReleaseReadinessTest {

    private fun loadSchema(): JSONObject {
        val candidates = listOf(
            File("src/main/assets/schema.json"),
            File("app/src/main/assets/schema.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("assets/schema.json 파일을 찾을 수 없어요")
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    @Test
    fun `Gleo schema_json 버전은 앱 BuildConfig_VERSION_NAME 및 패키지명과 일치한다`() {
        val schema = loadSchema()
        assertEquals(BuildConfig.APPLICATION_ID, schema.getString("packageName"))
        assertEquals(BuildConfig.VERSION_NAME, schema.getString("version"))
    }

    @Test
    fun `Gleo App Schema는 최대 3개 action, 대문자·밑줄 명명, 승인 action 미포함 규칙을 지킨다`() {
        val schema = loadSchema()
        val actions = schema.getJSONArray("actionCalls")
        assertTrue("actionCalls는 1~3개여야 한다", actions.length() in 1..3)

        val names = mutableSetOf<String>()
        val nameRegex = Regex("^[A-Z][A-Z0-9_]*$")
        for (i in 0 until actions.length()) {
            val fn = actions.getJSONObject(i).getJSONObject("function")
            val name = fn.getString("name")
            assertTrue("중복되지 않은 action 이름이어야 한다: $name", names.add(name))
            assertTrue("대문자·밑줄 형식이어야 한다: $name", nameRegex.matches(name))
            assertFalse("Gleo를 통한 승인 action은 포함하지 않는다: $name", name.contains("RESPOND"))
            assertTrue("영어 description이 있어야 한다", fn.getString("description").isNotBlank())
        }
    }
}

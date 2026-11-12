package com.smartjourney.vehicle.voice

import com.smartjourney.vehicle.BuildConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Gleo 액션 스키마(assets/schema.json)의 version은 앱 versionName과 같아야 한다. */
class GleoSchemaVersionTest {
    @Test
    fun 스키마_버전은_앱_버전과_같다() {
        val schema = JSONObject(File("src/main/assets/schema.json").readText())
        assertEquals(BuildConfig.VERSION_NAME, schema.getString("version"))
    }
}

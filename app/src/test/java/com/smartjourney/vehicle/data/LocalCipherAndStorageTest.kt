package com.smartjourney.vehicle.data

import com.smartjourney.vehicle.domain.Store
import com.smartjourney.vehicle.engine.JourneyState
import com.smartjourney.vehicle.engine.StopState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

class LocalCipherAndStorageTest {

    private class MapKeyValueStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun testAesCipher(): TextCipher {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return AesGcmCodec { key }
    }

    @Test
    fun AES_GCM_암복호화는_한글과_JSON을_보존하고_매번_다른_IV를_생성한다() {
        val cipher = testAesCipher()
        val plain = """{"store":"판교 42dot 맛집","partySize":4}"""

        val enc1 = cipher.encrypt(plain)
        val enc2 = cipher.encrypt(plain)

        assertNotNull(enc1)
        assertNotNull(enc2)
        assertNotEquals("같은 평문도 매번 다른 IV로 암호화되어야 한다", enc1, enc2)
        assertFalse("암호문에 평문이 노출되지 않아야 한다", enc1!!.contains("42dot"))
        assertEquals(plain, cipher.decrypt(enc1))
        assertEquals(plain, cipher.decrypt(enc2!!))
    }

    @Test
    fun 변조되거나_손상된_암호문은_null을_반환한다() {
        val cipher = testAesCipher()
        val encrypted = cipher.encrypt("sensitive-device-token")!!
        val tampered = encrypted.dropLast(2) + if (encrypted.endsWith("AA")) "BB" else "AA"

        assertNull(cipher.decrypt(tampered))
        assertNull(cipher.decrypt("not-encrypted-text"))
        assertNull(cipher.decrypt("enc:v1:invalid:base64"))
    }

    @Test
    fun PreferencesJourneyStorage는_여정을_암호화해_저장하고_복원한다() {
        val backing = MapKeyValueStore()
        val cipher = testAesCipher()
        val storage = PreferencesJourneyStorage(SecurePreferenceStore(backing, cipher), now = { 1_000L })
        val state = JourneyState(
            active = true,
            partySize = 3,
            stops = listOf(StopState(Store(1L, "비밀맛집", "성남시 판교역로", 37.4, 127.1))),
        )

        storage.save(state)

        assertNull("평문 키에 저장되지 않아야 한다", backing.map["journey_state_json"])
        val storedRaw = backing.map["journey_state_enc"]
        assertNotNull(storedRaw)
        assertFalse("저장 값에 매장명 평문이 노출되지 않아야 한다", storedRaw!!.contains("비밀맛집"))

        val restored = storage.restore()
        assertNotNull(restored)
        assertEquals(3, restored?.partySize)
        assertEquals("비밀맛집", restored?.stops?.first()?.store?.name)
    }

    @Test
    fun 손상된_암호화_여정_데이터는_안전하게_삭제하고_null을_반환한다() {
        val backing = MapKeyValueStore()
        backing.map["journey_state_enc"] = "enc:v1:AAAA:corrupted"
        val storage = PreferencesJourneyStorage(SecurePreferenceStore(backing, testAesCipher()), now = { 1_000L })

        assertNull(storage.restore())
        assertTrue("손상 데이터는 복구 시 정리되어야 한다", backing.map.isEmpty())
    }

    @Test
    fun 암호화_실패시_평문으로_폴백_저장하지_않는다() {
        val backing = MapKeyValueStore()
        val brokenCipher = object : TextCipher {
            override fun encrypt(plainText: String): String? = null
            override fun decrypt(cipherText: String): String? = null
        }
        val storage = PreferencesJourneyStorage(SecurePreferenceStore(backing, brokenCipher), now = { 1_000L })
        val state = JourneyState(
            active = true,
            partySize = 2,
            stops = listOf(StopState(Store(1L, "테스트매장", "주소", 37.4, 127.1))),
        )

        storage.save(state)

        assertTrue("암호화 실패 시 어떤 평문도 남기지 않는다", backing.map.isEmpty())
    }

    @Test
    fun 이전_평문_여정은_1회_복원후_평문키를_제거하고_암호화키로_이전한다() {
        val backing = MapKeyValueStore()
        val cipher = testAesCipher()
        val state = JourneyState(
            active = true,
            partySize = 4,
            stops = listOf(StopState(Store(1L, "기존매장", "주소", 37.4, 127.1))),
        )
        backing.map["journey_state_json"] = JourneyStateJsonCodec.toJson(state, 1_000L)
        val storage = PreferencesJourneyStorage(SecurePreferenceStore(backing, cipher), now = { 2_000L })

        val restored = storage.restore()

        assertNotNull(restored)
        assertEquals("기존매장", restored?.stops?.first()?.store?.name)
        assertNull("이전 평문 키는 즉시 삭제되어야 한다", backing.map["journey_state_json"])
        assertNotNull("암호화 키로 이전되어야 한다", backing.map["journey_state_enc"])
    }

    @Test
    fun EncryptedDeviceTokenStore는_토큰을_암호화_저장하고_폐기시_삭제한다() {
        val backing = MapKeyValueStore()
        val store = EncryptedDeviceTokenStore(SecurePreferenceStore(backing, testAesCipher()))

        store.save("secret-device-token-123")
        val raw = backing.map.values.single()
        assertFalse(raw.contains("secret-device-token-123"))
        assertEquals("secret-device-token-123", store.load())

        store.clear()
        assertNull(store.load())
        assertTrue(backing.map.isEmpty())
    }
}

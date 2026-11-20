package com.smartjourney.vehicle.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 기기 내 저장소(SharedPreferences 등) 암호화 추상화.
 * Pleos 보안 가이드에 따라 내부 저장소에도 평문 민감정보를 남기지 않는다.
 */
interface TextCipher {
    fun encrypt(plainText: String): String?
    fun decrypt(cipherText: String): String?
}

/**
 * 표준 JCA AES-GCM 암복호화 코덱.
 * AndroidKeyStore와 일반 JVM 단위 테스트 키를 모두 지원하기 위해
 * 암호화 시 IV를 호출자가 지정하지 않고 JCA/Keystore가 매번 난수로 생성하게 둔다.
 */
class AesGcmCodec(
    private val keyProvider: () -> SecretKey,
) : TextCipher {

    override fun encrypt(plainText: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val iv = cipher.iv
        require(iv != null && iv.isNotEmpty()) { "GCM IV를 생성하지 못함" }
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val encoder = Base64.getEncoder()
        "$PREFIX:${encoder.encodeToString(iv)}:${encoder.encodeToString(encrypted)}"
    }.getOrNull()

    override fun decrypt(cipherText: String): String? = runCatching {
        val parts = cipherText.split(':')
        if (parts.size != 4 || "${parts[0]}:${parts[1]}" != PREFIX) return null
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(parts[2])
        val payload = decoder.decode(parts[3])
        if (iv.isEmpty() || payload.size <= TAG_LENGTH_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        String(cipher.doFinal(payload), StandardCharsets.UTF_8)
    }.getOrNull()

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
        private const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8
        private const val PREFIX = "enc:v1"
    }
}

/**
 * Android Keystore 기반 AES-256-GCM 암호화기 (외부 보안 라이브러리 의존성 없음, minSdk 28+).
 * 기기별 하드웨어/OS 키스토어에 키를 보관하며 키 자체는 앱 저장소에 기록하지 않는다.
 */
class KeystoreAesGcmCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : TextCipher {

    private val lock = Any()
    private val codec = AesGcmCodec(::getOrCreateKey)

    override fun encrypt(plainText: String): String? {
        codec.encrypt(plainText)?.let { return it }
        // OS 업데이트·보안 상태 변경으로 키가 영구 무효화되었을 때 1회 재생성해 저장 불능 상태를 복구한다.
        deleteKey()
        return codec.encrypt(plainText)
    }

    override fun decrypt(cipherText: String): String? = codec.decrypt(cipherText)

    private fun deleteKey() = synchronized(lock) {
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        }
    }

    private fun getOrCreateKey(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .build()
        generator.init(spec)
        generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_SIZE_BITS = 256
        const val DEFAULT_KEY_ALIAS = "smart_journey_local_aes_gcm_v1"
    }
}

/** SharedPreferences와 단위 테스트용 인메모리 맵을 동일하게 다루기 위한 최소 키-값 저장소. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

class SharedPreferencesKeyValueStore(
    private val prefs: SharedPreferences,
) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}

/**
 * 암호화된 문자열 저장·조회 및 0.2.13 이전 평문 키의 1회 마이그레이션/파기를 담당한다.
 * 암호화에 실패하면 평문으로 저장하지 않고 기존 값을 삭제해 안전하게 실패한다.
 */
class SecurePreferenceStore(
    private val store: KeyValueStore,
    private val cipher: TextCipher,
) {
    fun putEncryptedString(encryptedKey: String, value: String?, legacyPlainKey: String? = null) {
        legacyPlainKey?.let(store::remove)
        if (value == null) {
            store.remove(encryptedKey)
            return
        }
        val encrypted = cipher.encrypt(value)
        if (encrypted == null) {
            // 암호화 실패 시 평문으로 폴백 저장하지 않는다.
            store.remove(encryptedKey)
            return
        }
        store.putString(encryptedKey, encrypted)
    }

    fun getEncryptedString(encryptedKey: String, legacyPlainKey: String? = null): String? {
        val raw = store.getString(encryptedKey)
        if (raw != null) {
            val decrypted = cipher.decrypt(raw)
            if (decrypted == null) {
                // 키 무효화·변조·손상 데이터는 즉시 정리하고 복구 불가능으로 처리한다.
                store.remove(encryptedKey)
            }
            return decrypted
        }
        if (legacyPlainKey != null) {
            val legacy = store.getString(legacyPlainKey)
            if (legacy != null) {
                // 업그레이드 직후 1회만 평문을 읽어 암호화 키로 이전하고 평문 키는 즉시 삭제한다.
                store.remove(legacyPlainKey)
                cipher.encrypt(legacy)?.let { store.putString(encryptedKey, it) }
                return legacy
            }
        }
        return null
    }

    fun remove(encryptedKey: String, legacyPlainKey: String? = null) {
        store.remove(encryptedKey)
        legacyPlainKey?.let(store::remove)
    }
}

/** 서버 기기 토큰 영속화 추상화. 동의 철회·401 만료 시 즉시 폐기한다. */
interface DeviceTokenStore {
    fun load(): String?
    fun save(token: String)
    fun clear()
}

class InMemoryDeviceTokenStore : DeviceTokenStore {
    @Volatile private var value: String? = null
    override fun load(): String? = value
    override fun save(token: String) { value = token }
    override fun clear() { value = null }
}

class EncryptedDeviceTokenStore(
    private val secureStore: SecurePreferenceStore,
) : DeviceTokenStore {
    override fun load(): String? = secureStore.getEncryptedString(KEY_DEVICE_TOKEN_ENC)
    override fun save(token: String) = secureStore.putEncryptedString(KEY_DEVICE_TOKEN_ENC, token)
    override fun clear() = secureStore.remove(KEY_DEVICE_TOKEN_ENC)

    companion object {
        private const val KEY_DEVICE_TOKEN_ENC = "device_token_enc"
    }
}

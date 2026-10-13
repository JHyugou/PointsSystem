package com.pointshome.app

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal data class LocalControlState(
    val childId: String = "", val childName: String = "", val username: String? = null,
    val serverUrl: String = "", val deviceId: String = "", val deviceKey: String = "",
    val salt: String = "", val pinHash: String = "", val algorithm: String = "",
    val enabled: Boolean = false, val packages: Set<String> = emptySet(),
    val remainingMillis: Long = 0, val appliedGrants: Set<String> = emptySet(),
    val attempts: Int = 0, val retryAt: Long = 0, val damaged: Boolean = false,
) { val configured: Boolean get() = childId.isNotBlank() && pinHash.isNotBlank() }

internal class LocalControlStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("local_control_v1", Context.MODE_PRIVATE)
    private val alias = "points_home_local_control_v1"
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    private val mutableState = MutableStateFlow(load())
    val state = mutableState.asStateFlow()
    fun snapshot(): LocalControlState = mutableState.value

    private fun load(): LocalControlState {
        val blob = prefs.getString("state", null) ?: return LocalControlState()
        return try {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            decode(JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)))
        } catch (_: Exception) { LocalControlState(damaged = true) }
    }

    @Synchronized
    private fun update(transform: (LocalControlState) -> LocalControlState) {
        val next = transform(mutableState.value)
        if (next == mutableState.value) return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val data = cipher.doFinal(encode(next).toString().toByteArray(Charsets.UTF_8))
        // Persist before emitting a credit/reservation so interruptions never issue time twice.
        check(prefs.edit().putString("state", b64(cipher.iv + data)).commit()) { "本机设置保存失败，请重试" }
        mutableState.value = next
    }

    fun configure(child: AccountInfo, server: String, device: TimeDeviceRegistration, pin: String) {
        require(pin.length in 6..64) { "家长密码至少 6 位，最多 64 位" }
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val algorithm = if (Build.VERSION.SDK_INT >= 26) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"
        val hash = hash(pin, salt, algorithm)
        update { LocalControlState(childId = child.id, childName = child.displayName, username = child.username,
            serverUrl = server.trim().trimEnd('/'), deviceId = device.deviceId, deviceKey = device.deviceKey,
            salt = b64(salt), pinHash = b64(hash), algorithm = algorithm) }
    }

    fun verifyPin(pin: String): Boolean {
        val current = snapshot()
        require(current.configured) { "请先由家长完成本机设置" }
        val now = System.currentTimeMillis()
        if (now < current.retryAt) throw IllegalStateException("尝试次数过多，请 ${(current.retryAt - now + 999) / 1000} 秒后重试")
        val matches = MessageDigest.isEqual(Base64.decode(current.pinHash, Base64.NO_WRAP),
            hash(pin, Base64.decode(current.salt, Base64.NO_WRAP), current.algorithm))
        update { old ->
            if (matches) old.copy(attempts = 0, retryAt = 0)
            else { val count = old.attempts + 1; old.copy(attempts = count,
                retryAt = if (count >= 5) now + minOf(900_000L, 30_000L * (count - 4)) else 0) }
        }
        return matches
    }
    fun changePin(pin: String) {
        require(pin.length in 6..64) { "家长密码至少 6 位，最多 64 位" }
        val current = snapshot()
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hashed = hash(pin, salt, current.algorithm)
        update { it.copy(salt = b64(salt), pinHash = b64(hashed), attempts = 0, retryAt = 0) }
    }
    fun setRules(packages: Set<String>, enabled: Boolean) = update { it.copy(packages = packages, enabled = enabled) }
    fun setRemainingMillis(value: Long) = update { it.copy(remainingMillis = value.coerceAtLeast(0)) }
    fun adjustRemainingMillis(delta: Long): Long {
        var adjustment = 0L
        update {
            val next = Math.addExact(it.remainingMillis, delta).coerceAtLeast(0)
            adjustment = next - it.remainingMillis
            it.copy(remainingMillis = next)
        }
        return adjustment
    }
    fun applyGrant(grant: TimeGrant) = update {
        if (grant.id in it.appliedGrants) it else {
            require(grant.minutes in 1..1440) { "时间凭据无效" }
            it.copy(remainingMillis = Math.addExact(it.remainingMillis, grant.minutes * 60_000L),
                appliedGrants = it.appliedGrants + grant.id)
        }
    }
    fun matches(childId: String, server: String): Boolean = snapshot().let {
        it.configured && it.childId == childId && it.serverUrl == server.trim().trimEnd('/')
    }

    private fun hash(pin: String, salt: ByteArray, algorithm: String): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return try { SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun encode(s: LocalControlState) = JSONObject().apply {
        put("childId", s.childId); put("childName", s.childName); put("username", s.username)
        put("serverUrl", s.serverUrl); put("deviceId", s.deviceId); put("deviceKey", s.deviceKey)
        put("salt", s.salt); put("pinHash", s.pinHash); put("algorithm", s.algorithm)
        put("enabled", s.enabled); put("packages", JSONArray(s.packages.toList()))
        put("remainingMillis", s.remainingMillis); put("appliedGrants", JSONArray(s.appliedGrants.toList()))
        put("attempts", s.attempts); put("retryAt", s.retryAt)
    }
    private fun decode(j: JSONObject): LocalControlState {
        fun strings(name: String): Set<String> = j.optJSONArray(name)?.let { a ->
            (0 until a.length()).map { a.getString(it) }.toSet()
        } ?: emptySet()
        return LocalControlState(j.getString("childId"), j.getString("childName"),
            j.optString("username").takeIf { it.isNotBlank() && it != "null" }, j.getString("serverUrl"),
            j.getString("deviceId"), j.getString("deviceKey"), j.getString("salt"), j.getString("pinHash"),
            j.getString("algorithm"), j.getBoolean("enabled"), strings("packages"),
            j.getLong("remainingMillis").coerceAtLeast(0), strings("appliedGrants"),
            j.optInt("attempts"), j.optLong("retryAt"))
    }
    companion object {
        @Volatile private var instance: LocalControlStore? = null
        fun get(context: Context): LocalControlStore = instance ?: synchronized(this) {
            instance ?: LocalControlStore(context.applicationContext).also { instance = it }
        }
    }
}

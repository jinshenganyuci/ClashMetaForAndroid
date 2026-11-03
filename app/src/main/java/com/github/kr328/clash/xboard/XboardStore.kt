package com.github.kr328.clash.xboard

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

data class XboardBinding(
    val email: String,
    val bearer: String,
    val profileId: UUID? = null,
    val subscription: XboardSubscription? = null,
    val lastSync: Long? = null,
) {
    override fun toString() = "XboardBinding"
}

class XboardStore(context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.noBackupFilesDir, "xboard-account"))

    fun read(): XboardBinding? = synchronized(lock) {
        val bytes = try {
            file.readFully()
        } catch (_: FileNotFoundException) {
            return@synchronized null
        }
        try {
            val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
            val keys = keyStore()
            val key = if (envelope.has("wrapped")) {
                val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                rsa.init(Cipher.DECRYPT_MODE, keys.getKey(RSA_ALIAS, null))
                SecretKeySpec(rsa.doFinal(decode(envelope.getString("wrapped"))), "AES")
            } else {
                keys.getKey(AES_ALIAS, null) as SecretKey
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode(envelope.getString("iv"))))
            val plaintext = cipher.doFinal(decode(envelope.getString("ciphertext")))
            val json = JSONObject(plaintext.toString(Charsets.UTF_8))
            XboardBinding(
                email = json.getString("email"),
                bearer = json.getString("bearer"),
                profileId = if (json.isNull("profile")) null else UUID.fromString(json.getString("profile")),
                subscription = json.optJSONObject("subscription")?.let { XboardSubscription.fromJson(it) },
                lastSync = if (json.isNull("last_sync")) null else json.getLong("last_sync"),
            )
        } catch (_: Exception) {
            // JSON/URI parser exceptions may contain plaintext credentials.
            throw IOException("无法读取本机保存的账号信息")
        }
    }

    fun save(binding: XboardBinding) = synchronized(lock) {
        val encoded = try {
            val plaintext = JSONObject()
                .put("email", binding.email.trim().lowercase(Locale.ROOT))
                .put("bearer", binding.bearer)
                .put("profile", binding.profileId?.toString())
                .put("subscription", binding.subscription?.toJson())
                .put("last_sync", binding.lastSync)
                .toString().toByteArray(Charsets.UTF_8)
            val keys = keyStore()
            val envelope = JSONObject()
            val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                aesKey(keys)
            } else {
                // Android 5's Keystore only supports asymmetric keys. Wrap a fresh AES
                // key with its non-exportable RSA key, alongside the encrypted payload.
                val secret = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                rsa.init(Cipher.ENCRYPT_MODE, legacyWrappingKey(keys))
                envelope.put("wrapped", encode(rsa.doFinal(secret.encoded)))
                secret
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            envelope.put("iv", encode(cipher.iv))
                .put("ciphertext", encode(cipher.doFinal(plaintext)))
                .toString().toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            throw IOException("无法加密本机账号信息")
        }
        val out = file.startWrite()
        try {
            out.write(encoded)
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }

    fun clear() = synchronized(lock) {
        file.delete()
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            throw IOException("无法删除本机账号信息")
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun aesKey(keys: KeyStore): SecretKey {
        (keys.getKey(AES_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(AES_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance("AES", KEYSTORE).apply { init(spec) }.generateKey()
    }

    @Suppress("DEPRECATION")
    private fun legacyWrappingKey(keys: KeyStore): java.security.PublicKey {
        keys.getCertificate(RSA_ALIAS)?.let { return it.publicKey }
        val now = Calendar.getInstance()
        val spec = KeyPairGeneratorSpec.Builder(app)
            .setAlias(RSA_ALIAS)
            .setSubject(X500Principal("CN=Xboard"))
            .setSerialNumber(BigInteger.ONE)
            .setStartDate(now.time)
            .setEndDate((now.clone() as Calendar).apply { add(Calendar.YEAR, 50) }.time)
            .setKeySize(2048)
            .build()
        return KeyPairGenerator.getInstance("RSA", KEYSTORE).apply { initialize(spec) }.generateKeyPair().public
    }

    private companion object {
        val lock = Any()
        const val KEYSTORE = "AndroidKeyStore"
        const val AES_ALIAS = "xboard.account.aes"
        const val RSA_ALIAS = "xboard.account.rsa"
        fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
    }
}

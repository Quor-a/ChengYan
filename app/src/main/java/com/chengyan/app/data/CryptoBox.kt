package com.chengyan.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API key 落盘加密 —— Android Keystore AES/GCM。
 *
 * 仅加密静态存储（DataStore/SharedPreferences 里的值），传输层仍由用户自行配置
 * （http 供应商 / LAN Ollama 不受影响）。密钥存在 Android Keystore，应用卸载即销毁，
 * adb backup 已由 manifest allowBackup=false 排除。
 *
 * 格式："enc1:" + base64(iv[12] + ciphertext)；不带前缀的值视为历史明文，读取时
 * 原样返回（透明迁移：下一次 save 自动变密文）。
 */
object CryptoBox {
    private const val PREFIX = "enc1:"
    private const val ALIAS = "chengyan_api_key"
    private const val GCM_IV = 12
    private const val GCM_TAG = 128

    @Volatile private var cachedKey: SecretKey? = null

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let {
            cachedKey = it; return it
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey().also { cachedKey = it }
    }

    fun encrypt(plain: String): String {
        if (plain.isBlank()) return plain
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + java.util.Base64.getEncoder().encodeToString(iv + ct)
    }

    fun decrypt(value: String): String {
        if (!value.startsWith(PREFIX)) return value  // 历史明文：透明兼容
        return runCatching {
            val raw = java.util.Base64.getDecoder().decode(value.removePrefix(PREFIX))
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG, raw, 0, GCM_IV))
            String(c.doFinal(raw, GCM_IV, raw.size - GCM_IV), Charsets.UTF_8)
        }.getOrDefault(value.removePrefix(PREFIX))  // 解密失败兜底：不崩，返回原样
    }

    val isEncrypted: (String) -> Boolean = { it.startsWith(PREFIX) }
}

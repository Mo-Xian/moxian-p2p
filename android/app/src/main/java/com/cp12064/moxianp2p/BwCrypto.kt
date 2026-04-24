package com.cp12064.moxianp2p

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Bitwarden / Vaultwarden 零知识加密协议的最小客户端实现
 *
 * 参考：https://bitwarden.com/help/bitwarden-security-white-paper/
 *
 * 仅支持：
 *   - PBKDF2-SHA256（KDF type 0 默认 迭代数从 prelogin 返回）
 *   - 未支持：Argon2id（KDF type 1）此时弹提示让用户去 Web
 *   - AES-CBC-HMAC EncString type "2"（当前 BW 默认）
 */
object BwCrypto {

    /** 派生主密钥 = PBKDF2-SHA256(password, email_lowercase, iterations, 32 bytes) */
    fun deriveMasterKey(password: String, email: String, iterations: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(
            password.toCharArray(),
            email.lowercase().trim().toByteArray(Charsets.UTF_8),
            iterations,
            256
        )
        return factory.generateSecret(spec).encoded
    }

    /** 派生主密码哈希（发给服务器做登录） = Base64( PBKDF2-SHA256(masterKey, password, 1, 32 bytes) )
     *  不用 PBEKeySpec 因为它要求 password 是 char[] 这里 key 已经是任意 bytes */
    fun deriveMasterPasswordHash(masterKey: ByteArray, password: String): String =
        Base64.encodeToString(
            pbkdf2Raw(masterKey, password.toByteArray(Charsets.UTF_8), 1, 32),
            Base64.NO_WRAP
        )

    /** 低层 PBKDF2-SHA256 任意字节 key + salt（不用 PBEKeySpec 的 char 处理 以 bytes 精确控制）*/
    fun pbkdf2Raw(key: ByteArray, salt: ByteArray, iterations: Int, lengthBytes: Int): ByteArray {
        // PBKDF2: 多次 HMAC 迭代
        val blocks = (lengthBytes + 31) / 32
        val result = ByteArray(lengthBytes)
        for (i in 1..blocks) {
            val block = pbkdf2Block(key, salt, iterations, i)
            val off = (i - 1) * 32
            val copy = minOf(32, lengthBytes - off)
            System.arraycopy(block, 0, result, off, copy)
        }
        return result
    }

    private fun pbkdf2Block(key: ByteArray, salt: ByteArray, iterations: Int, blockIndex: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val saltBlock = salt + intToBytes(blockIndex)
        var u = mac.doFinal(saltBlock)
        val out = u.copyOf()
        for (i in 2..iterations) {
            u = mac.doFinal(u)
            for (j in out.indices) out[j] = (out[j].toInt() xor u[j].toInt()).toByte()
        }
        return out
    }

    private fun intToBytes(i: Int): ByteArray = byteArrayOf(
        ((i ushr 24) and 0xFF).toByte(),
        ((i ushr 16) and 0xFF).toByte(),
        ((i ushr 8) and 0xFF).toByte(),
        (i and 0xFF).toByte()
    )

    /** HKDF-Expand (SHA-256) per RFC 5869 */
    fun hkdfExpand(prk: ByteArray, info: String, length: Int): ByteArray {
        val infoBytes = info.toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val n = (length + 31) / 32
        var t = ByteArray(0)
        val okm = ByteArray(length)
        var offset = 0
        for (i in 1..n) {
            mac.reset()
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val input = t + infoBytes + byteArrayOf(i.toByte())
            t = mac.doFinal(input)
            val copy = minOf(32, length - offset)
            System.arraycopy(t, 0, okm, offset, copy)
            offset += 32
        }
        return okm
    }

    /** 解密 EncString type 2 格式 "2.iv|ct|mac" */
    fun decryptEncString(encStr: String, encKey: ByteArray, macKey: ByteArray): ByteArray? {
        if (!encStr.startsWith("2.")) return null
        val parts = encStr.substring(2).split("|")
        if (parts.size < 3) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ct = Base64.decode(parts[1], Base64.NO_WRAP)
        val expectedMac = Base64.decode(parts[2], Base64.NO_WRAP)

        // 验证 HMAC
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        mac.update(iv)
        mac.update(ct)
        val actualMac = mac.doFinal()
        if (!actualMac.contentEquals(expectedMac)) return null

        // 解密 AES-256-CBC
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv))
            cipher.doFinal(ct)
        } catch (e: Exception) { null }
    }

    /** 解密 EncString 并转为 UTF-8 字符串 */
    fun decryptEncStringToStr(encStr: String, encKey: ByteArray, macKey: ByteArray): String? =
        decryptEncString(encStr, encKey, macKey)?.toString(Charsets.UTF_8)

    /** 派生 stretched master key（32字节 enc + 32字节 mac）*/
    fun stretchMasterKey(masterKey: ByteArray): Pair<ByteArray, ByteArray> {
        val enc = hkdfExpand(masterKey, "enc", 32)
        val mac = hkdfExpand(masterKey, "mac", 32)
        return Pair(enc, mac)
    }

    /** 加密成 EncString type 2 格式 "2.iv|ct|mac"
     *  IV 16 字节随机 AES-256-CBC + HMAC-SHA256(iv || ct) */
    fun encryptToEncString(plain: ByteArray, encKey: ByteArray, macKey: ByteArray): String {
        // 随机 IV
        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)

        // AES-CBC 加密
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(plain)

        // HMAC(iv || ct)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        mac.update(iv)
        mac.update(ct)
        val macBytes = mac.doFinal()

        return "2." + Base64.encodeToString(iv, Base64.NO_WRAP) +
                "|" + Base64.encodeToString(ct, Base64.NO_WRAP) +
                "|" + Base64.encodeToString(macBytes, Base64.NO_WRAP)
    }

    /** String 重载 */
    fun encryptToEncString(plain: String, encKey: ByteArray, macKey: ByteArray): String =
        encryptToEncString(plain.toByteArray(Charsets.UTF_8), encKey, macKey)
}

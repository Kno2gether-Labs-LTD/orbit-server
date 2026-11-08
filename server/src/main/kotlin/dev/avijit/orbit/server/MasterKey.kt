package dev.avijit.orbit.server

import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Server master key (`master.key`, 32 random bytes, 0600) for secrets kept in the DB such as model API keys.
 * Ciphertext format: base64(iv[12] || ciphertext || tag[16]), AES-256-GCM.
 */
class MasterKey(home: ServerHome) {
    private val key: SecretKeySpec

    init {
        val path = home.file(FILE)
        if (!Files.exists(path)) {
            val bytes = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
            home.writePrivate(FILE, bytes)
            bytes.fill(0)
        }
        home.makePrivate(path)
        val bytes = Files.readAllBytes(path)
        require(bytes.size == KEY_BYTES) { "$path is not a 32-byte key; refusing to use it" }
        key = SecretKeySpec(bytes, "AES")
        bytes.fill(0)
    }

    fun encrypt(plain: String): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return Base64.getEncoder().encodeToString(iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** Throws if the input is malformed or was tampered with. */
    fun decrypt(encoded: String): String {
        val raw = Base64.getDecoder().decode(encoded)
        require(raw.size >= IV_BYTES + TAG_BITS / 8) { "ciphertext too short" }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
        return String(c.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
    }

    companion object {
        const val FILE = "master.key"
        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private val random = SecureRandom()
    }
}

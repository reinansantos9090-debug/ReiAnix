package com.reianix.reianix_local.data.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable authenticated envelope. Each record binds the header and index.
 * Plain ZIP is never published until every encrypted record authenticates. API 26+.
 */
internal object ReiAnixProtectedBackup {
    private val MAGIC = byteArrayOf(82, 69, 73, 65, 78, 73, 88, 1)
    private const val CHUNK = 64 * 1024
    private const val TAG = 16
    private const val LIMIT = 512L * 1024 * 1024
    private const val ITERATIONS = 600_000
    const val MAX_INPUT_BYTES = LIMIT + (LIMIT / CHUNK + 1) * TAG + 40

    fun transform(input: File, output: File, password: CharArray, encrypt: Boolean) {
        require(input.canonicalFile != output.canonicalFile)
        try {
            require(password.isNotEmpty())
            if (encrypt) encryptFile(input, output, password) else decryptFile(input, output, password)
        } catch (e: Exception) {
            output.delete()
            throw e
        } finally { password.fill('\u0000') }
    }

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { SecretKeySpec(raw, "AES") } finally { raw.fill(0) }
        } finally { spec.clearPassword() }
    }

    private fun header(salt: ByteArray, prefix: ByteArray, size: Long): ByteArray =
        java.nio.ByteBuffer.allocate(40).put(MAGIC).put(salt).put(prefix).putLong(size).array()

    private fun record(mode: Int, key: SecretKeySpec, header: ByteArray, prefix: ByteArray, index: Int, bytes: ByteArray): ByteArray {
        val nonce = java.nio.ByteBuffer.allocate(12).put(prefix).putInt(index).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        cipher.updateAAD(java.nio.ByteBuffer.allocate(4).putInt(index).array())
        return cipher.doFinal(bytes)
    }

    private fun encryptFile(input: File, output: File, password: CharArray) {
        val size = input.length()
        require(size in 1..LIMIT)
        val random = SecureRandom()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val prefix = ByteArray(8).also { random.nextBytes(it) }
        val header = header(salt, prefix, size)
        val key = key(password, salt)
        DataInputStream(input.inputStream().buffered()).use { source ->
            DataOutputStream(output.outputStream().buffered()).use { out ->
                out.write(header)
                var remaining = size
                var index = 0
                while (remaining > 0) {
                    val plain = ByteArray(minOf(remaining, CHUNK.toLong()).toInt())
                    source.readFully(plain)
                    try { out.write(record(Cipher.ENCRYPT_MODE, key, header, prefix, index++, plain)) }
                    finally { plain.fill(0) }
                    remaining -= plain.size
                }
                check(source.read() == -1)
            }
        }
    }

    private fun decryptFile(input: File, output: File, password: CharArray) {
        DataInputStream(input.inputStream().buffered()).use { source ->
            val header = ByteArray(40).also { source.readFully(it) }
            val reader = java.nio.ByteBuffer.wrap(header)
            val magic = ByteArray(8).also { reader.get(it) }
            require(magic.contentEquals(MAGIC))
            val salt = ByteArray(16).also { reader.get(it) }
            val prefix = ByteArray(8).also { reader.get(it) }
            val size = reader.long
            require(size in 1..LIMIT)
            val records = (size + CHUNK - 1) / CHUNK
            require(input.length() == 40 + size + records * TAG)
            val key = key(password, salt)
            output.outputStream().buffered().use { out ->
                var remaining = size
                var index = 0
                while (remaining > 0) {
                    val n = minOf(remaining, CHUNK.toLong()).toInt()
                    val ciphertext = ByteArray(n + TAG).also { source.readFully(it) }
                    val plain = record(Cipher.DECRYPT_MODE, key, header, prefix, index++, ciphertext)
                    try { out.write(plain) } finally { plain.fill(0) }
                    remaining -= n
                }
                check(source.read() == -1)
            }
        }
    }
}

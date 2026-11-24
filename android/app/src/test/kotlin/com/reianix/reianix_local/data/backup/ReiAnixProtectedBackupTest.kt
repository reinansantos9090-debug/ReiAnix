package com.reianix.reianix_local.data.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReiAnixProtectedBackupTest {
    @Test fun roundTripRejectsTamperingAndWrongPassword() {
        val dir = java.nio.file.Files.createTempDirectory("reianix-protected-backup").toFile()
        try {
            val original = File(dir, "original.zip").apply { writeBytes(ByteArray(140_000) { (it % 251).toByte() }) }
            val protected = File(dir, "backup.rxb")
            val recovered = File(dir, "recovered.zip")
            val password = "Senha de teste longa"
            ReiAnixProtectedBackup.transform(original, protected, password.toCharArray(), true)
            ReiAnixProtectedBackup.transform(protected, recovered, password.toCharArray(), false)
            assertArrayEquals(original.readBytes(), recovered.readBytes())
            assertThrows(Exception::class.java) {
                ReiAnixProtectedBackup.transform(protected, recovered, "wrong password".toCharArray(), false)
            }
            assertFalse(recovered.exists())
            val bytes = protected.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            protected.writeBytes(bytes)
            assertThrows(Exception::class.java) {
                ReiAnixProtectedBackup.transform(protected, recovered, password.toCharArray(), false)
            }
            assertFalse(recovered.exists())
            protected.writeBytes(bytes.copyOf(bytes.size - 1))
            assertThrows(Exception::class.java) {
                ReiAnixProtectedBackup.transform(protected, recovered, password.toCharArray(), false)
            }
            assertFalse(recovered.exists())
        } finally { dir.deleteRecursively() }
    }
}

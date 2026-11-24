package com.example.cloud

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object Sha256Helper {
    private const val BUFFER_SIZE = 16 * 1024

    fun computeFileSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

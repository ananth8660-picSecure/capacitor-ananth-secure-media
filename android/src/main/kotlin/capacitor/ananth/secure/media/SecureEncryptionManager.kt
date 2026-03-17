package com.ananth.picsecure.media

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object SecureEncryptionManager {

    private const val ALGORITHM = "AES/CBC/PKCS5Padding"

    private fun generateKey(password: String): SecretKeySpec {

        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray())

        return SecretKeySpec(hash, "AES")
    }

    fun encryptFile(password: String, input: File, output: File) {

        val key = generateKey(password)

        val cipher = Cipher.getInstance(ALGORITHM)

        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)

        val ivSpec = IvParameterSpec(iv)

        cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec)

        val fis = FileInputStream(input)
        val fos = FileOutputStream(output)

        // Write IV at beginning of file
        fos.write(iv)

        val cos = CipherOutputStream(fos, cipher)

        val buffer = ByteArray(4096)
        var read: Int

        while (fis.read(buffer).also { read = it } != -1) {
            cos.write(buffer, 0, read)
        }

        fis.close()
        cos.close()
    }

    fun decryptFile(password: String, input: File, output: File) {

        val key = generateKey(password)

        val fis = FileInputStream(input)

        val iv = ByteArray(16)
        fis.read(iv)

        val cipher = Cipher.getInstance(ALGORITHM)

        val ivSpec = IvParameterSpec(iv)

        cipher.init(Cipher.DECRYPT_MODE, key, ivSpec)

        val fos = FileOutputStream(output)

        val buffer = ByteArray(4096)
        var read: Int

        while (fis.read(buffer).also { read = it } != -1) {

            val decrypted = cipher.update(buffer, 0, read)

            if (decrypted != null)
                fos.write(decrypted)
        }

        val finalBytes = cipher.doFinal()

        if (finalBytes != null)
            fos.write(finalBytes)

        fis.close()
        fos.close()
    }
}
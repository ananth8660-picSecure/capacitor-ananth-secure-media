package com.ananth.picsecure.media

import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

object SecureDelete {

    fun wipe(file: File) {

        if (!file.exists()) return

        val size = file.length()

        val fos = FileOutputStream(file)

        val buffer = ByteArray(4096)

        var written = 0L

        val random = SecureRandom()

        while (written < size) {

            random.nextBytes(buffer)

            fos.write(buffer)

            written += buffer.size
        }

        fos.close()

        file.delete()
    }
}
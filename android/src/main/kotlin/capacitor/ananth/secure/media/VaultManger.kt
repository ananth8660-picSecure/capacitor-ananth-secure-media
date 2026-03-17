package com.ananth.picsecure.media

import android.content.Context
import java.io.File

object VaultManager {

    private const val VAULT_FOLDER = ".picsecure_vault"

    fun getVaultFolder(context: Context): File {

        val vault = File(context.filesDir, VAULT_FOLDER)

        if (!vault.exists()) {
            vault.mkdirs()
        }

        val noMedia = File(vault, ".nomedia")

        if (!noMedia.exists()) {
            noMedia.createNewFile()
        }

        return vault
    }
}
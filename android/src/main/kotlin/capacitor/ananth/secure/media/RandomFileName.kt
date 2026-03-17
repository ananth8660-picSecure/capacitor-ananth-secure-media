package com.ananth.secure.media

import java.util.UUID

object RandomFileName {

    fun generate(extension: String): String {

        return UUID.randomUUID()
            .toString()
            .replace("-", "") + "." + extension

    }

}
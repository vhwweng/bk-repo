package com.tencent.bkrepo.common.api.constant

import java.util.UUID

object StringPool {
    const val EMPTY = ""
    const val DOT = "."
    const val SLASH = "/"
    const val ROOT = SLASH
    const val COLON = ":"
    const val DASH = "-"
    const val QUESTION = "?"
    const val HTTP = "http://"
    const val HTTPS = "https://"
    const val UNKNOWN = "Unknown"
    const val TEMP = "temp"
    const val UTF_8 = "UTF-8"
    const val BYTES = "bytes"
    const val NO_CACHE = "no-cache"

    private val alphabet: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')

    fun randomString(size: Int) = List(size) { alphabet.random() }.joinToString("")

    fun uniqueId() = UUID.randomUUID().toString().replace(DASH, EMPTY).toLowerCase()
}
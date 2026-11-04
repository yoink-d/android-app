package com.maynkudu.yoinkd.core.model

import java.net.URI

@JvmInline
value class MediaLink private constructor(val value: String) {
    val host: String get() = URI(value).host

    companion object {
        fun parse(input: String): MediaLink {
            val text = input.trim()
            require(text.isNotEmpty()) { "Enter a link." }
            require(text.none(Char::isWhitespace)) { "Enter a full HTTPS link." }
            val uri = runCatching { URI(text) }.getOrElse {
                throw IllegalArgumentException("Enter a full HTTPS link.")
            }
            require(uri.scheme.equals("https", true)) { "Use an HTTPS link." }
            require(!uri.host.isNullOrEmpty()) { "Enter a full HTTPS link." }
            require(uri.rawUserInfo == null) { "Use a link without a username or password." }
            return MediaLink("https:" + text.substringAfter(':'))
        }
    }
}

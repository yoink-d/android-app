package com.maynkudu.yoinkd.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import com.maynkudu.yoinkd.core.model.MediaKind
import java.util.UUID

sealed interface IncomingRequest {
    val id: String
    data class Link(val text: String = "", val kind: MediaKind? = null,
        override val id: String = UUID.randomUUID().toString()) : IncomingRequest
    data class Files(val uris: List<Uri>, override val id: String = UUID.randomUUID().toString()) : IncomingRequest
}

fun incomingRequest(intent: Intent): IncomingRequest? {
    val data = intent.data
    if (intent.action == Intent.ACTION_VIEW && data?.scheme == "yoinkd" && data.host == "add") {
        return IncomingRequest.Link(data.getQueryParameter("url").orEmpty(),
            MediaKind.entries.firstOrNull { it.name.equals(data.getQueryParameter("kind"), true) })
    }
    if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val link = Regex("https://[^\\s<>]+", RegexOption.IGNORE_CASE).find(text)?.value ?: text
        return IncomingRequest.Link(link)
    }
    if (intent.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
        val uris = buildList {
            intent.clipData?.let { clip -> for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add) }
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::addAll)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(::addAll)
            }
        }.distinct()
        if (uris.isNotEmpty()) return IncomingRequest.Files(uris)
    }
    return null
}

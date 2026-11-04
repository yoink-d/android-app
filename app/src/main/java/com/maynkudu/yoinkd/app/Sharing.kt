package com.maynkudu.yoinkd.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.maynkudu.yoinkd.core.model.MediaItem

fun shareMedia(context: Context, item: MediaItem) {
    val uri = Uri.parse(item.uri)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = item.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, item.title)
        clipData = ClipData.newRawUri(item.title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share ${item.title}"))
}

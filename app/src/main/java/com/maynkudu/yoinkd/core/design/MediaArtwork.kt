package com.maynkudu.yoinkd.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.maynkudu.yoinkd.R
import com.maynkudu.yoinkd.core.model.MediaKind

@Composable
fun MediaArtwork(model: String?, kind: MediaKind, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        RuneIcon(if (kind == MediaKind.VIDEO) R.drawable.rune_video else R.drawable.rune_play,
            modifier = Modifier.size(40.dp))
        if (model != null) AsyncImage(model, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

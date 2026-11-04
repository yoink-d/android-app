package com.maynkudu.yoinkd.core.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maynkudu.yoinkd.R

@Composable
fun RuneIcon(resource: Int, description: String? = null, modifier: Modifier = Modifier) {
    Icon(painterResource(resource), description, modifier.size(24.dp))
}

@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.red < 0.5f
    Image(painterResource(if (dark) R.drawable.brand_mark else R.drawable.brand_mark_light), null, modifier)
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
        BrandMark(Modifier.size(80.dp))
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        action()
    }
}

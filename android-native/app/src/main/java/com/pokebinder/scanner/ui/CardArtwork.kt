package com.pokebinder.scanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ImageNotSupported
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.pokebinder.scanner.model.RecognizedCard

@Composable
fun CardArtwork(
    card: RecognizedCard,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    SubcomposeAsyncImage(
        model = card.imageHighUrl ?: card.imageUrl,
        contentDescription = contentDescription,
        contentScale = contentScale,
        loading = {
            ArtworkPlaceholder(showProgress = true)
        },
        error = {
            ArtworkPlaceholder(showProgress = false)
        },
        modifier = modifier,
    )
}

@Composable
private fun ArtworkPlaceholder(showProgress: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.ImageNotSupported,
                contentDescription = "카드 이미지 없음",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

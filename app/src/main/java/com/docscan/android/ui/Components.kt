package com.docscan.android.ui

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.docscan.android.R
import com.docscan.android.data.DocumentRepository
import com.docscan.android.data.PageEntity
import com.docscan.android.processing.ImagePipeline
import com.docscan.android.processing.ScanFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppIcon(@DrawableRes resource: Int, description: String?, modifier: Modifier = Modifier.size(22.dp)) {
    Icon(painterResource(resource), description, modifier)
}

@Composable
fun ActionButton(@DrawableRes icon: Int, text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        AppIcon(icon, null); Spacer(Modifier.width(7.dp)); Text(text)
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp)) {
            AppIcon(R.drawable.ic_pdf, null, Modifier.padding(24.dp).size(48.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class PreviewResult(val bitmap: Bitmap? = null, val failed: Boolean = false)

@Composable
fun PagePreview(page: PageEntity, repository: DocumentRepository, modifier: Modifier = Modifier, edge: Int = 640) {
    val preview by produceState(PreviewResult(), page.id, page.rotation, page.filter, edge) {
        value = try {
            PreviewResult(withContext(Dispatchers.Default) {
                ImagePipeline.render(repository.source(page), page.rotation, ScanFilter.valueOf(page.filter), edge)
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PreviewResult(failed = true) }
    }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        when {
            preview.bitmap != null -> Image(requireNotNull(preview.bitmap).asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            preview.failed -> Text(stringResource(R.string.preview_failed), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            else -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
fun filterLabel(filter: ScanFilter): String = stringResource(when (filter) {
    ScanFilter.ORIGINAL -> R.string.original
    ScanFilter.ENHANCED -> R.string.enhanced
    ScanFilter.GRAYSCALE -> R.string.grayscale
    ScanFilter.MONOCHROME -> R.string.monochrome
})

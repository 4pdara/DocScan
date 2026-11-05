package com.docscan.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.docscan.android.R

@Composable
fun DesignNavigation(screen: String, enabled: Boolean, onLibrary: () -> Unit, onCapture: () -> Unit, onPages: () -> Unit) {
    val selected = when (screen) { "home", "settings" -> 0; "camera" -> 1; else -> 2 }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup()) {
                val labels = listOf(R.string.nav_documents, R.string.nav_capture, R.string.nav_pages)
                val icons = listOf(R.drawable.ic_pdf, R.drawable.ic_camera, R.drawable.ic_image)
                val actions = listOf(onLibrary, onCapture, onPages)
                val tags = listOf("nav-documents", "nav-camera", "nav-pages")
                labels.forEachIndexed { index, label ->
                    val active = selected == index
                    Column(Modifier.weight(1f).testTag(tags[index]).selectable(active, enabled = enabled, role = Role.Tab,
                        onClick = { if (!active || screen == "settings") actions[index]() }).padding(top = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        CompositionLocalProvider(LocalContentColor provides if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) {
                            AppIcon(icons[index], null, Modifier.size(22.dp))
                            Text(stringResource(label), Modifier.padding(top = 3.dp, bottom = 7.dp), style = MaterialTheme.typography.labelMedium)
                        }
                        HorizontalDivider(Modifier.fillMaxWidth(.55f), thickness = if (active) 3.dp else 1.dp,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                    }
                }
            }
        }
    }
}

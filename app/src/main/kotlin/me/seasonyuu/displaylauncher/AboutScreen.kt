package me.seasonyuu.displaylauncher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch

internal enum class AboutLink(val url: String) {
    Project("https://github.com/seasonyuu/android-display-launcher"),
    Feedback("https://github.com/seasonyuu/android-display-launcher/issues"),
    License("https://github.com/seasonyuu/android-display-launcher/blob/main/LICENSE"),
    Shizuku("https://shizuku.rikka.app/"),
}

internal fun openAboutLink(context: Context, link: AboutLink): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, link.url.toUri()))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val packageInfo = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
    // Adaptive launcher icons are Drawables, not XML vector painters.
    val icon = remember(context) { context.packageManager.getApplicationIcon(context.packageName).toBitmap(192, 192).asImageBitmap() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.about_link_unavailable)
    val openLink: (AboutLink) -> Unit = { link ->
        if (!openAboutLink(context, link)) scope.launch { snackbar.showSnackbar(unavailable) }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Box(modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        Column(Modifier.fillMaxSize()) {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.about_back))
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
            Column(
                Modifier.weight(1f).align(Alignment.CenterHorizontally).widthIn(max = 720.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(icon, contentDescription = null, modifier = Modifier.size(80.dp))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.about_version, packageInfo.versionName.orEmpty(), packageInfo.longVersionCode),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyLarge)
                }
                AboutSection(stringResource(R.string.about_project)) {
                    AboutRow(stringResource(R.string.about_author), stringResource(R.string.about_author_name))
                    AboutRow(stringResource(R.string.about_homepage), stringResource(R.string.about_homepage_summary)) { openLink(AboutLink.Project) }
                    AboutRow(stringResource(R.string.about_feedback), stringResource(R.string.about_feedback_summary)) { openLink(AboutLink.Feedback) }
                    AboutRow(stringResource(R.string.about_license), stringResource(R.string.about_license_name)) { openLink(AboutLink.License) }
                }
                AboutSection(stringResource(R.string.about_privacy)) {
                    Text(stringResource(R.string.about_privacy_body), Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
                }
                AboutSection(stringResource(R.string.about_thanks)) {
                    AboutRow(stringResource(R.string.about_shizuku), stringResource(R.string.about_shizuku_summary)) { openLink(AboutLink.Shizuku) }
                    Text(stringResource(R.string.about_jetpack), Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.about_copyright), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.about_independent), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun AboutSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun AboutRow(title: String, summary: String, onClick: (() -> Unit)? = null) {
    val linkLabel = stringResource(R.string.about_external_link)
    ListItem(
        modifier = (if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = linkLabel, onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = if (onClick != null) ({ Text("↗", color = MaterialTheme.colorScheme.onSurfaceVariant) }) else null,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

package cloud.morris.wcast

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cloud.morris.wcast.ui.theme.WcastTheme

/**
 * A small settings/about screen reached from the gear icon on the main screen's
 * player bar.
 *
 * It keeps its scope tight: a shortcut into the Android system's text-to-speech
 * screen (so users can pick a voice/engine without hunting through system
 * settings), plus an About block exposing the build/version identity for
 * support and debugging.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WcastTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen(
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE,
                        buildTimeUtc = BuildConfig.BUILD_TIME_UTC,
                        onBack = { finish() },
                        onOpenTtsSettings = ::openTtsSettings
                    )
                }
            }
        }
    }

    /**
     * Opens the Android text-to-speech output settings screen ("Text-to-speech
     * output"), falling back to general settings if the device doesn't expose it.
     *
     * The settings app exposes this screen via the action
     * `com.android.settings.TTS_SETTINGS` (backed by
     * `com.android.settings.Settings$TextToSpeechSettingsActivity`). We use the
     * public action rather than the raw component name so it keeps working across
     * OEM/vendor Settings implementations.
     */
    private fun openTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                // The device exposes no settings UI we can reach; do nothing.
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    versionName: String,
    versionCode: Int,
    buildTimeUtc: String,
    onBack: () -> Unit,
    onOpenTtsSettings: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(title = "Voice")
            SettingsRow(
                icon = {
                    Icon(
                        imageVector = Icons.Filled.SettingsVoice,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                },
                title = "Text-to-speech settings",
                subtitle = "Choose your engine and voice in the Android settings",
                onClick = onOpenTtsSettings
            )

            Spacer(modifier = Modifier.height(24.dp))
            SettingsSection(title = "About")
            AboutBlock(versionName, versionCode, buildTimeUtc)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun SettingsRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AboutBlock(versionName: String, versionCode: Int, buildTimeUtc: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Wcast",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Listen to Wikipedia articles using the Android text-to-speech service.",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "Wcast is an open-source (MIT) Android app that lets you search " +
                "Wikipedia, read a clutter-free version of any article, and have it " +
                "read aloud \u2014 the whole article or one section at a time \u2014 using " +
                "the text-to-speech engine already installed on your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Content & Licensing",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Article content served by Wcast is sourced from Wikipedia. Text " +
                "is available under the Creative Commons Attribution-ShareAlike 4.0 " +
                "License; additional terms may apply.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Wcast is an independent application and is not affiliated with, " +
                "endorsed by, or sponsored by the Wikimedia Foundation.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        RepositoryLink()

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        AboutDetail("Version", versionName)
        AboutDetail("Version code", versionCode.toString())
        AboutDetail("Build (UTC)", buildTimeUtc)
    }
}

@Composable
private fun AboutDetail(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * A short "issues, feedback, or comments?" line with the repository URL
 * rendered as a tappable link. On tap it opens the repository in the browser
 * via an ACTION_VIEW intent.
 */
@Composable
private fun RepositoryLink() {
    val context = LocalContext.current
    val repoUrl = "https://github.com/xenotropic/wcast"

    val annotated = buildAnnotatedString {
        pushStyle(
            SpanStyle(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        )
        append("Issues, feedback, or comments? Visit ")
        pop()
        pushStringAnnotation(tag = "URL", annotation = repoUrl)
        pushStyle(
            SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            )
        )
        append(repoUrl)
        pop()
        pop()
    }

    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        onClick = { offset ->
            annotated
                .getStringAnnotations("URL", offset, offset)
                .firstOrNull()
                ?.let { link ->
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(link.item))
                        )
                    }
                }
        }
    )
}



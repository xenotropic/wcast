package cloud.morris.wcast

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cloud.morris.wcast.tts.SpeechControllerBridge
import cloud.morris.wcast.ui.theme.WcastTheme
import kotlin.math.roundToInt

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
        val settings = SettingsStore(this)
        DebugLog.enabled = settings.debugLogsEnabled
        val powerManager = getSystemService(PowerManager::class.java)
        setContent {
            var batteryOptimizationIgnored by remember {
                mutableStateOf(powerManager.isIgnoringBatteryOptimizations(packageName))
            }
            // Re-check the exemption when the user returns from the system settings
            // dialog so the row's subtitle reflects the new state immediately.
            LifecycleResumeEffect(Unit) {
                batteryOptimizationIgnored =
                    powerManager.isIgnoringBatteryOptimizations(packageName)
                onPauseOrDispose { }
            }
            WcastTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen(
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE,
                        buildTimePt = BuildConfig.BUILD_TIME_PT,
                        initialContinuity = settings.continuity,
                        onContinuityChange = { settings.continuity = it },
                        initialPlayerBarScale = settings.playerBarScale,
                        onPlayerBarScaleChange = { settings.playerBarScale = it },
                        initialProgressiveChunking = settings.progressiveChunking,
                        onProgressiveChunkingChange = { settings.progressiveChunking = it },
                        initialDebugLogsEnabled = settings.debugLogsEnabled,
                        onDebugLogsEnabledChange = { enabled ->
                            settings.debugLogsEnabled = enabled
                            DebugLog.enabled = enabled
                        },
                        initialBookmarkOnPlay = settings.bookmarkOnPlay,
                        onBookmarkOnPlayChange = { settings.bookmarkOnPlay = it },
                        onBack = { finish() },
                        onOpenTtsSettings = ::openTtsSettings,
                        onReloadVoice = ::reloadVoice,
                        onViewDebugLogs = ::openDebugLogs,
                        onViewNormalizedText = ::openNormalizedText,
                        batteryOptimizationIgnored = batteryOptimizationIgnored,
                        onOpenBatteryOptimization = ::requestBatteryOptimizationExemption
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

    /**
     * Forces the live [SpeechController] to rebuild its TTS engine, picking up a
     * newly selected engine (or voice) without a force-close. Routed through the
     * process-wide [SpeechControllerBridge] because the engine lives in the main
     * screen's ViewModel, not in this activity.
     */
    private fun reloadVoice() {
        val reloaded = SpeechControllerBridge.onReloadEngine?.invoke() ?: false
        val message = if (reloaded) {
            "Text-to-speech engine reloaded"
        } else {
            "Stop playback first, then reload the voice"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Lets the user exempt Wcast from battery optimization so background /
     * screen-off playback (and a paused media session) isn't killed. On most
     * devices this shows the system's "ignore battery optimizations" dialog
     * directly via [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS]; where
     * that action isn't available we fall back to the app-list page and then to
     * the battery-saver settings screen.
     */
    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(
                this,
                "Wcast is already exempt from battery optimization",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                try {
                    startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(
                        this,
                        "No battery optimization settings screen is available on this device",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /** Opens the in-app debug log viewer ([DebugLogActivity]). */
    private fun openDebugLogs() {
        startActivity(Intent(this, DebugLogActivity::class.java))
    }

    /** Opens the normalized-text viewer so users can inspect what TTS reads. */
    private fun openNormalizedText() {
        startActivity(Intent(this, NormalizedTextActivity::class.java))
    }
}

@Composable
private fun SettingsScreen(
    versionName: String,
    versionCode: Int,
    buildTimePt: String,
    initialContinuity: Boolean,
    onContinuityChange: (Boolean) -> Unit,
    initialPlayerBarScale: Int,
    onPlayerBarScaleChange: (Int) -> Unit,
    initialProgressiveChunking: Boolean,
    onProgressiveChunkingChange: (Boolean) -> Unit,
    initialDebugLogsEnabled: Boolean,
    onDebugLogsEnabledChange: (Boolean) -> Unit,
    initialBookmarkOnPlay: Boolean,
    onBookmarkOnPlayChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    onReloadVoice: () -> Unit,
    onViewDebugLogs: () -> Unit,
    onViewNormalizedText: () -> Unit,
    batteryOptimizationIgnored: Boolean,
    onOpenBatteryOptimization: () -> Unit
) {
    var continuity by remember { mutableStateOf(initialContinuity) }
    var playerBarScale by remember { mutableStateOf(initialPlayerBarScale) }
    var progressiveChunking by remember { mutableStateOf(initialProgressiveChunking) }
    var debugLogsEnabled by remember { mutableStateOf(initialDebugLogsEnabled) }
    var bookmarkOnPlay by remember { mutableStateOf(initialBookmarkOnPlay) }
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
            SettingsRow(
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                },
                title = "Reload voice",
                subtitle = "Re-detect the selected text-to-speech engine and voice",
                onClick = onReloadVoice
            )
            SettingsRow(
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                },
                title = "View normalized text",
                subtitle = "See the exact text the text-to-speech engine is reading",
                onClick = onViewNormalizedText
            )

            Spacer(modifier = Modifier.height(24.dp))
            SettingsSection(title = "Playback")
            SettingsToggleRow(
                title = "Continue to end",
                subtitle = "When playing a section, keep reading through the end of the article instead of stopping at the end of the section.",
                checked = continuity,
                onCheckedChange = { newValue ->
                    continuity = newValue
                    onContinuityChange(newValue)
                }
            )
            SettingsToggleRow(
                title = "Sentence-by-sentence start",
                subtitle = "Speak the first sentence right away, then ramp up to whole paragraphs once playback is underway.",
                checked = progressiveChunking,
                onCheckedChange = { newValue ->
                    progressiveChunking = newValue
                    onProgressiveChunkingChange(newValue)
                }
            )
            SettingsToggleRow(
                title = "Bookmark on play",
                subtitle = "Automatically bookmark an article the first time you start playing it.",
                checked = bookmarkOnPlay,
                onCheckedChange = { newValue ->
                    bookmarkOnPlay = newValue
                    onBookmarkOnPlayChange(newValue)
                }
            )
            SettingsSliderRow(
                title = "Player bar size",
                subtitle = "Scale the size of the playback controls.",
                value = playerBarScale,
                onValueChange = { newValue ->
                    playerBarScale = newValue
                    onPlayerBarScaleChange(newValue)
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
            SettingsSection(title = "Background playback")
            SettingsRow(
                icon = {
                    Icon(
                        imageVector = Icons.Filled.BatterySaver,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                },
                title = "Battery optimization",
                subtitle = if (batteryOptimizationIgnored) {
                    "Wcast is exempt from battery optimization"
                } else {
                    "Let Wcast keep playing (or stay paused) in the background and with the screen off"
                },
                onClick = onOpenBatteryOptimization
            )

            Spacer(modifier = Modifier.height(24.dp))
            SettingsSection(title = "Debug")
            SettingsToggleRow(
                title = "Debug logs",
                subtitle = "Record generation and playback timing from the text-to-speech engine (kept in memory).",
                checked = debugLogsEnabled,
                onCheckedChange = { newValue ->
                    debugLogsEnabled = newValue
                    onDebugLogsEnabledChange(newValue)
                }
            )
            SettingsRow(
                icon = {
                    Icon(
                        imageVector = Icons.Filled.BugReport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                },
                title = "View debug logs",
                subtitle = "Select and copy the captured lines to paste into a ticket",
                onClick = onViewDebugLogs
            )

            Spacer(modifier = Modifier.height(24.dp))
            SettingsSection(title = "About")
            AboutBlock(versionName, versionCode, buildTimePt)
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
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
        Spacer(modifier = Modifier.width(16.dp))
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsSliderRow(
    title: String,
    subtitle: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            Text(
                text = "$value%",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 50f..250f,
            steps = 19 // increments of 10%
        )
    }
}

@Composable
private fun AboutBlock(versionName: String, versionCode: Int, buildTimePt: String) {
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
        AboutDetail("Build (Pacific)", buildTimePt)
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



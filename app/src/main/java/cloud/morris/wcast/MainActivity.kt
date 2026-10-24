package cloud.morris.wcast

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import cloud.morris.wcast.ui.ArticleScreen
import cloud.morris.wcast.ui.theme.WcastTheme

class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsStore

    /** A bookmark title handed in via [BookmarksActivity.EXTRA_OPEN_BOOKMARK]. */
    private val pendingOpenTitle = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        DebugLog.enabled = settings.debugLogsEnabled
        OrientationLock.apply(this)
        pendingOpenTitle.value = intent.getStringExtra(BookmarksActivity.EXTRA_OPEN_BOOKMARK)
        requestNotificationPermissionIfNeeded()
        setContent {
            WcastTheme {
                var agreed by remember { mutableStateOf(settings.eulaAccepted) }
                if (!agreed) {
                    // Block the back button so the user can only proceed via
                    // "I Agree" or leave via "Exit".
                    BackHandler { /* consume back press while the EULA is shown */ }
                    EulaScreen(
                        onAgree = {
                            settings.eulaAccepted = true
                            agreed = true
                        },
                        onExit = {
                            settings.eulaAccepted = false
                            finishAffinity()
                        }
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        ArticleScreen(
                            pendingOpenTitle = pendingOpenTitle.value,
                            onPendingOpenConsumed = { pendingOpenTitle.value = null }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingOpenTitle.value = intent.getStringExtra(BookmarksActivity.EXTRA_OPEN_BOOKMARK)
    }

    override fun onResume() {
        super.onResume()
        // Re-apply the portrait lock so a change made in Settings takes effect
        // as soon as the user navigates back (ticket 27).
        OrientationLock.apply(this)
    }

    /**
     * Android 13+ requires the POST_NOTIFICATIONS permission to display the
     * playback notification / media-player card. The foreground service still
     * runs (and media buttons still route) without it, but the card stays hidden;
     * request it up front so the media controls appear like Spotify/AntennaPod's.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_POST_NOTIFICATIONS
        )
    }

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 1001
    }
}

@Composable
private fun EulaScreen(onAgree: () -> Unit, onExit: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "End User License Agreement: MIT license",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = LICENSE_TEXT,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onExit,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text("Exit")
                }
                Button(
                    onClick = onAgree,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                ) {
                    Text("I Agree")
                }
            }
        }
    }
}

/** The MIT license text shown in the first-launch agreement dialog. */
internal const val LICENSE_TEXT: String =
    "Copyright 2026 Joseph Morris\n" +
        "\n" +
        "Permission is hereby granted, free of charge, to any person obtaining a copy " +
        "of this software and associated documentation files (the \u201CSoftware\u201D), to deal " +
        "in the Software without restriction, including without limitation the rights to " +
        "use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of " +
        "the Software, and to permit persons to whom the Software is furnished to do so, " +
        "subject to the following conditions:\n" +
        "\n" +
        "The above copyright notice and this permission notice shall be included in all " +
        "copies or substantial portions of the Software.\n" +
        "\n" +
        "THE SOFTWARE IS PROVIDED \u201CAS IS\u201D, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR " +
        "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS " +
        "FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR " +
        "COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN " +
        "AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION " +
        "WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."

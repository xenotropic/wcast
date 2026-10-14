package cloud.morris.wcast

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.morris.wcast.ui.theme.WcastTheme

/**
 * A read-only view of the in-app [DebugLog] buffer, reached from Settings →
 * Debug → "View debug logs".
 *
 * The top bar offers "Select all" and "Copy" (both put the *entire* log on the
 * clipboard so it can be pasted into a ticket/file) plus "Clear" to empty the
 * buffer. The text itself is inside a [SelectionContainer], so individual lines
 * can also be selected and copied by long-press.
 */
class DebugLogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WcastTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DebugLogScreen(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun DebugLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lines by DebugLog.state.collectAsState()

    val copyAll: () -> Unit = {
        val text = lines.joinToString("\n")
        if (text.isBlank()) {
            Toast.makeText(context, "No debug logs to copy", Toast.LENGTH_SHORT).show()
        } else {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Wcast debug logs", text))
            Toast.makeText(context, "Copied ${lines.size} log line(s)", Toast.LENGTH_SHORT).show()
        }
    }

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
                text = "Debug logs",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = copyAll) {
                Icon(
                    imageVector = Icons.Filled.SelectAll,
                    contentDescription = "Select all",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = copyAll) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = {
                DebugLog.clear()
                Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
            }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Clear",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        HorizontalDivider()

        SelectionContainer(modifier = Modifier.fillMaxSize()) {
            Text(
                text = lines.joinToString("\n")
                    .ifEmpty {
                        "No debug logs yet.\n\n" +
                            "Enable \"Debug logs\" in Settings → Debug, then play an article."
                    },
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            )
        }
    }
}
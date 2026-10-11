package cloud.morris.wcast

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import cloud.morris.wcast.data.BookmarkStore
import cloud.morris.wcast.data.WikipediaRepository
import cloud.morris.wcast.ui.theme.WcastTheme
import kotlinx.coroutines.launch

/**
 * Shows all bookmarked (offline-saved) articles. Tapping a title opens the stored
 * copy in the main screen; the refresh button re-fetches and overwrites the stored
 * copy (preserving the saved listening position), per ticket 11.
 */
class BookmarksActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = BookmarkStore(this)
        val repository = WikipediaRepository()
        setContent {
            WcastTheme {
                BookmarkScreen(
                    onBack = { finish() },
                    summaries = store.summaries(),
                    onOpen = { title -> openInMain(title) },
                    onReload = { title ->
                        lifecycleScope.launch {
                            val sections = repository.fetchArticle(title)
                            store.saveCopy(title, sections) // overwrites copy, preserves position
                        }
                    }
                )
            }
        }
    }

    private fun openInMain(title: String) {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_BOOKMARK, title)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }

    companion object {
        const val EXTRA_OPEN_BOOKMARK = "cloud.morris.wcast.OPEN_BOOKMARK"
    }
}

@Composable
private fun BookmarkScreen(
    summaries: List<BookmarkStore.Summary>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onReload: (String) -> Unit
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
                text = "Bookmarks",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        HorizontalDivider()

        if (summaries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No bookmarks yet.\nBookmark an article to listen offline.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(summaries, key = { it.title }) { summary ->
                    BookmarkRow(
                        title = summary.title,
                        onOpen = { onOpen(summary.title) },
                        onReload = { onReload(summary.title) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BookmarkRow(
    title: String,
    onOpen: () -> Unit,
    onReload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { onOpen() }
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
        }
        IconButton(onClick = onReload) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Reload \"$title\"",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

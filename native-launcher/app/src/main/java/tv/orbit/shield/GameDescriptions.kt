package tv.orbit.shield

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class GameDescription(
    val text: String, val url: String, val licenseUrl: String, val language: String,
    val sourceName: String, val authors: String, val license: String, val modified: Boolean
)
internal data class GameMediaSource(val sourceName: String, val url: String)
private data class GameSources(val description: GameDescription? = null, val media: GameMediaSource? = null)
internal object GameDescriptions {
    private var data: JSONObject? = null
    private var media: JSONObject? = null
    private var bundled: List<Game>? = null
    @Synchronized fun find(context: Context, game: Game, language: String): GameDescription? {
        val root = data ?: JSONObject(context.assets.open("descriptions.json").bufferedReader().use { it.readText() }).also { data = it }
        val descriptions = root.optJSONObject(game.platform + "|" + game.title) ?: run {
            val games = bundled ?: LibraryImport.decode(context.assets.open("catalog.json").bufferedReader().use { it.readText() }).also { bundled = it }
            val match = GameMetadataMatcher.find(context, game.platform, game.title, games) ?: return null
            root.optJSONObject(match.platform + "|" + match.title) ?: return null
        }
        val item = descriptions.optJSONObject(language) ?: descriptions.optJSONObject("en") ?: return null
        return GameDescription(item.getString("text"), item.getString("url"), item.optString("licenseUrl"), item.getString("language"), item.optString("sourceName", "Wikipedia"), item.optString("authors", "Wikipedia contributors"), item.optString("license", "CC BY-SA 4.0"), item.optBoolean("modified"))
    }
    @Synchronized fun mediaSource(context: Context, game: Game): GameMediaSource? {
        val root = media ?: JSONObject(context.assets.open("media-sources.json").bufferedReader().use { it.readText() }).also { media = it }
        val item = root.optJSONObject(game.cover) ?: root.optJSONObject(game.scene) ?: return null
        return GameMediaSource(item.getString("sourceName"), item.getString("url"))
    }
}

@Composable internal fun GameDescriptionPanel(game: Game) {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    val sources by produceState(GameSources(), game.id, game.title, game.cover, language) {
        value = GameSources()
        value = withContext(Dispatchers.IO) { GameSources(
            runCatching { GameDescriptions.find(context, game, language) }.getOrNull(),
            runCatching { GameDescriptions.mediaSource(context, game) }.getOrNull()
        ) }
    }
    val loaded = sources.description
    val text = loaded?.text ?: description(game)
    if (text.isBlank() && sources.media == null) return
    val readLabel = tr(R.string.description_read)
    val adaptedLabel = tr(R.string.metadata_adapted)
    val artCreditLabel = tr(R.string.metadata_art_credit, sources.media?.sourceName.orEmpty())
    val artRights = tr(R.string.metadata_art_rights)
    val descriptionCreditLabel = tr(R.string.metadata_description_source, loaded?.sourceName.orEmpty())
    Surface(onClick = {
        val attribution = loaded?.let { entry -> listOf(entry.authors, entry.license, if (entry.modified) adaptedLabel else "", entry.url).filter { it.isNotBlank() }.joinToString("\n") }.orEmpty()
        val mediaCredit = sources.media?.let { artCreditLabel + "\n" + artRights + "\n" + it.url }.orEmpty()
        val dialog = AlertDialog.Builder(context).setTitle(game.title).setMessage(listOf(text, attribution, mediaCredit).filter { it.isNotBlank() }.joinToString("\n\n"))
            .setPositiveButton(R.string.close, null)
        fun open(url: String) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { android.widget.Toast.makeText(context, R.string.source_browser_missing, android.widget.Toast.LENGTH_SHORT).show() }
        }
        val links = buildList {
            loaded?.let { add(descriptionCreditLabel to it.url) }
            sources.media?.let { add(artCreditLabel to it.url) }
        }
        if (links.isNotEmpty()) dialog.setNeutralButton(R.string.description_source) { _, _ ->
            if (links.size == 1) open(links.single().second)
            else AlertDialog.Builder(context).setTitle(R.string.description_source)
                .setItems(links.map { it.first }.toTypedArray()) { _, which -> open(links[which].second) }
                .setNegativeButton(R.string.close, null).show()
        }
        loaded?.takeIf { it.licenseUrl.isNotBlank() }?.let { entry -> dialog.setNegativeButton(entry.license) { _, _ -> open(entry.licenseUrl) } }
        dialog.show()
    }, modifier = Modifier.width(540.dp), colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1F1F21), focusedContainerColor = Color(0xFF30351E))) {
        Column(Modifier.padding(12.dp)) {
            if (text.isNotBlank()) Text(text, color = Color(0xFFF5F5F5), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(listOf(readLabel, loaded?.sourceName.orEmpty(), loaded?.license.orEmpty(), loaded?.language?.uppercase(java.util.Locale.ROOT).orEmpty(), if (loaded?.modified == true) adaptedLabel else "").filter { it.isNotBlank() }.joinToString(" · "), color = Color(0xFFCCFF00), fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

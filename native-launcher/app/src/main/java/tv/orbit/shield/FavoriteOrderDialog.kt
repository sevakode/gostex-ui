package tv.orbit.shield

import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources

/** The same ordered list is available from a favorite's hold menu and from section settings. */
@Composable internal fun FavoriteOrderDialog(
    initialItemKey: String? = null,
    apps: List<TvApp>,
    games: List<Game>,
    movies: List<SavedMovie> = emptyList(),
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val dismiss by rememberUpdatedState(onDismiss)
    val names = remember(resources, apps, games, movies) {
        buildMap {
            apps.forEach { put(homeAppKey(it.packageName), resources.getString(R.string.sections_app_label, it.name)) }
            games.forEach { put(homeGameKey(it.id), resources.getString(R.string.sections_game_label, it.title)) }
            movies.forEach { put(homeMovieKey(it.id), resources.getString(R.string.sections_media_label, it.title)) }
        }
    }
    DisposableEffect(context, resources, names, initialItemKey) {
        val dialogs = SectionDialogs(context, resources) { dismiss() }
        fun itemName(key: String): String = names[key]
            ?: resources.getString(R.string.sections_unavailable_item, key.substringAfter(':'))

        lateinit var showItems: (String?) -> Unit

        fun showActions(itemKey: String) {
            // Both the action menu and its eventual mutation resolve the current saved order.
            val favorites = SectionPreferences.read(context).favorites
            val index = favorites.indexOf(itemKey)
            if (index < 0) {
                showItems(null)
                return
            }
            val actions = buildList {
                if (index > 0) add(R.string.favorites_move_left to { SectionPreferences.moveFavorite(context, itemKey, -1) })
                if (index < favorites.lastIndex) add(R.string.favorites_move_right to { SectionPreferences.moveFavorite(context, itemKey, 1) })
                if (index > 0) add(R.string.favorites_move_first to { SectionPreferences.prioritizeFavorite(context, itemKey) })
                if (index < favorites.lastIndex) add(R.string.favorites_move_last to { SectionPreferences.placeFavoriteLast(context, itemKey) })
            }
            val builder = AlertDialog.Builder(context).setTitle(itemName(itemKey))
            if (actions.isEmpty()) builder.setMessage(R.string.favorites_order_single)
            else builder.setItems(actions.map { resources.getString(it.first) }.toTypedArray()) { _, selected ->
                actions[selected].second()
                showItems(itemKey)
            }
            dialogs.show(builder
                .setNegativeButton(R.string.back) { _, _ -> showItems(itemKey) }
                .setOnCancelListener { showItems(itemKey) }
                .create())
        }

        showItems = { selectedKey ->
            val favorites = SectionPreferences.read(context).favorites
            val builder = AlertDialog.Builder(context)
            if (favorites.isEmpty()) builder.setTitle(R.string.favorites_order_title)
                .setMessage(R.string.favorites_order_empty)
            else {
                // Native AlertDialog replaces its list when a message is set, so the instruction
                // belongs in the custom title. Keep the list and its D-pad navigation intact.
                val inset = (24 * resources.displayMetrics.density).toInt()
                val title = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(inset, inset, inset, inset / 2)
                    addView(TextView(context).apply {
                        setText(R.string.favorites_order_title)
                        textSize = 20f
                    })
                    addView(TextView(context).apply {
                        setText(R.string.favorites_order_hint)
                        textSize = 14f
                        setPadding(0, inset / 3, 0, 0)
                    })
                }
                builder.setCustomTitle(title)
                    .setItems(favorites.mapIndexed { index, item -> "${index + 1}. ${itemName(item)}" }.toTypedArray()) { _, index ->
                        showActions(favorites[index])
                    }
            }
            val dialog = builder.setNegativeButton(R.string.close, null).create()
            dialogs.show(dialog)
            if (favorites.isNotEmpty()) {
                val position = favorites.indexOf(selectedKey).coerceAtLeast(0)
                dialog.listView.post {
                    if (dialog.isShowing) {
                        dialog.listView.requestFocus()
                        dialog.listView.setSelection(position)
                    }
                }
            }
        }

        showItems(initialItemKey)
        onDispose { dialogs.dispose() }
    }
}

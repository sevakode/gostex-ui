package tv.orbit.shield

import android.app.AlertDialog
import android.content.Context
import android.content.res.Resources
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun sectionTitle(section: HomeSection): String =
    sectionTitle(LocalResources.current, section)

internal fun sectionTitle(resources: Resources, section: HomeSection): String = when (section.kind) {
    HomeSectionKind.CUSTOM -> section.name
    HomeSectionKind.RECENT -> resources.getString(R.string.home_shelf_recent)
    HomeSectionKind.FAVORITES -> resources.getString(R.string.home_shelf_favorites)
    HomeSectionKind.GAMES -> resources.getString(R.string.home_shelf_games)
    HomeSectionKind.APPS -> resources.getString(R.string.home_shelf_apps)
}

/** Own every native dialog, including replacement dialogs, for the lifetime of its Compose owner. */
internal class SectionDialogs(
    private val context: Context,
    private val resources: Resources,
    private val onDone: () -> Unit
) {
    private var current: AlertDialog? = null

    fun show(dialog: AlertDialog) {
        current?.setOnDismissListener(null)
        current?.dismiss()
        current = dialog
        dialog.setOnDismissListener {
            if (current === dialog) {
                current = null
                onDone()
            }
        }
        dialog.show()
        dialog.enableListButtonNavigation()
    }

    fun dispose() {
        current?.setOnDismissListener(null)
        current?.dismiss()
        current = null
    }

    fun name(title: Int, original: String = "", onAccept: (String) -> Boolean) {
        val field = EditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            hint = resources.getString(R.string.sections_name_hint)
            setText(original)
            setSelection(text.length)
            val inset = (20 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset / 2, inset, inset / 2)
        }
        val dialog = AlertDialog.Builder(context).setTitle(title).setView(field)
            .setPositiveButton(R.string.sections_save, null)
            .setNegativeButton(R.string.cancel, null).create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        show(dialog)
        // The platform default positive listener dismisses even invalid input.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = field.text.toString().trim().replace(Regex("\\s+"), " ")
            if (name.isBlank() || name.codePointCount(0, name.length) > SectionPreferences.MAX_SECTION_NAME_LENGTH ||
                field.text.any { it.isISOControl() }) {
                field.error = resources.getString(R.string.sections_name_error, SectionPreferences.MAX_SECTION_NAME_LENGTH)
                field.requestFocus()
            } else if (!onAccept(name)) {
                field.error = resources.getString(R.string.sections_save_error)
            } else if (current === dialog) dialog.dismiss()
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else false
        }
        field.requestFocus()
        field.post {
            if (current === dialog && field.isAttachedToWindow) {
                context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
}

private data class SectionContentChoice(val key: String, val title: String)

private fun contentChoices(resources: Resources, apps: List<TvApp>, games: List<Game>, movies: List<SavedMovie>): List<SectionContentChoice> =
    apps.sortedWith(compareBy<TvApp> { it.name.lowercase(Locale.ROOT) }.thenBy { it.packageName }).map {
        SectionContentChoice(homeAppKey(it.packageName), resources.getString(R.string.sections_app_label, it.name))
    } + games.filterNot { it.demo }.sortedWith(compareBy<Game> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }).map {
        SectionContentChoice(homeGameKey(it.id), resources.getString(R.string.sections_game_label, it.title))
    } + movies.sortedWith(compareBy<SavedMovie> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }).map {
        SectionContentChoice(homeMovieKey(it.id), resources.getString(R.string.sections_media_label, it.title))
    }

@Composable internal fun SectionSettings(initialFocus: FocusRequester, apps: List<TvApp>, games: List<Game>) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val options = rememberHomeSections(context)
    val homeOptions = rememberHomeOptions(context)
    var returnFocus by remember { mutableStateOf(initialFocus) }
    var focusRevision by remember { mutableStateOf(0) }
    var orderFavorites by remember { mutableStateOf(false) }
    val dialogs = remember(context, resources) { SectionDialogs(context, resources) { focusRevision++ } }
    DisposableEffect(dialogs) { onDispose { dialogs.dispose() } }
    LaunchedEffect(focusRevision, orderFavorites) {
        if (focusRevision > 0 && !orderFavorites) {
            withFrameNanos { }
            runCatching { returnFocus.requestFocus() }
        }
    }
    val movies by produceState<List<SavedMovie>>(emptyList(), context) {
        value = withContext(Dispatchers.IO) { runCatching { MovieLibraryStore.read(context) }.getOrDefault(emptyList()) }
    }
    val choices = remember(resources, apps, games, movies) { contentChoices(resources, apps, games, movies) }
    val contentTitles = remember(choices) { choices.associate { it.key to it.title } }

    fun selectContents(section: HomeSection) {
        val selected = section.items.toMutableSet()
        dialogs.show(AlertDialog.Builder(context).setTitle(R.string.sections_choose_contents)
            .setMultiChoiceItems(choices.map { it.title }.toTypedArray(), choices.map { it.key in selected }.toBooleanArray()) { _, index, enabled ->
                if (enabled) selected.add(choices[index].key) else selected.remove(choices[index].key)
            }
            .setPositiveButton(R.string.sections_apply) { _, _ ->
                // Retain unavailable entries and the owner's existing order. New picks follow the displayed list.
                val ordered = section.items.filter { it in selected } + choices.map { it.key }.filter { it in selected && it !in section.items }
                SectionPreferences.setItems(context, section.id, ordered)
            }
            .setNegativeButton(R.string.cancel, null).create())
    }

    fun contentName(item: String): String = contentTitles[item] ?: resources.getString(R.string.sections_unavailable_item, item.substringAfter(':'))

    fun orderContents(sectionId: String) {
        val section = SectionPreferences.read(context).sections.firstOrNull { it.id == sectionId } ?: return
        if (section.items.isEmpty()) return
        dialogs.show(AlertDialog.Builder(context).setTitle(R.string.sections_order_contents)
            .setItems(section.items.mapIndexed { index, item -> "${index + 1}. ${contentName(item)}" }.toTypedArray()) { _, selectedIndex ->
                val actions = buildList {
                    if (selectedIndex > 0) add(R.string.home_shelf_move_up to -1)
                    if (selectedIndex < section.items.lastIndex) add(R.string.home_shelf_move_down to 1)
                    add(R.string.sections_remove_item to 0)
                }
                dialogs.show(AlertDialog.Builder(context).setTitle(contentName(section.items[selectedIndex]))
                    .setItems(actions.map { resources.getString(it.first) }.toTypedArray()) { _, actionIndex ->
                        val current = SectionPreferences.read(context).sections.firstOrNull { it.id == sectionId }
                        if (current != null) {
                            val item = section.items[selectedIndex]
                            val items = current.items.toMutableList()
                            val index = items.indexOf(item)
                            if (index >= 0) {
                                val offset = actions[actionIndex].second
                                if (offset == 0) items.removeAt(index)
                                else {
                                    val target = (index + offset).coerceIn(items.indices)
                                    items.removeAt(index)
                                    items.add(target, item)
                                }
                                SectionPreferences.setItems(context, sectionId, items)
                                if (items.isNotEmpty()) orderContents(sectionId)
                            }
                        }
                    }.setNegativeButton(R.string.cancel, null).create())
            }.setNegativeButton(R.string.close, null).create())
    }

    fun editSection(section: HomeSection, index: Int) {
        val actions = buildList {
            if (section.kind == HomeSectionKind.CUSTOM) {
                add("contents" to R.string.sections_choose_contents)
                if (section.items.isNotEmpty()) add("order" to R.string.sections_order_contents)
                add("rename" to R.string.sections_rename)
            }
            if (section.kind == HomeSectionKind.FAVORITES) add("favoritesOrder" to R.string.favorites_order_title)
            add("visibility" to if (section.visible) R.string.sections_hide else R.string.sections_show)
            if (index > 0) add("up" to R.string.home_shelf_move_up)
            if (index < options.sections.lastIndex) add("down" to R.string.home_shelf_move_down)
            if (section.kind == HomeSectionKind.CUSTOM) add("delete" to R.string.sections_delete)
        }
        dialogs.show(AlertDialog.Builder(context).setTitle(sectionTitle(resources, section))
            .setItems(actions.map { resources.getString(it.second) }.toTypedArray()) { _, actionIndex ->
                when (actions[actionIndex].first) {
                    "contents" -> selectContents(section)
                    "order" -> orderContents(section.id)
                    "favoritesOrder" -> orderFavorites = true
                    "rename" -> dialogs.name(R.string.sections_rename, section.name) { name ->
                        name == section.name || SectionPreferences.rename(context, section.id, name)
                    }
                    "visibility" -> SectionPreferences.setVisible(context, section.id, !section.visible)
                    "up" -> SectionPreferences.move(context, section.id, -1)
                    "down" -> SectionPreferences.move(context, section.id, 1)
                    "delete" -> dialogs.show(AlertDialog.Builder(context).setTitle(R.string.sections_delete)
                        .setMessage(resources.getString(R.string.sections_delete_hint, sectionTitle(resources, section)))
                        .setPositiveButton(R.string.sections_delete) { _, _ ->
                            returnFocus = initialFocus
                            SectionPreferences.delete(context, section.id)
                        }.setNegativeButton(R.string.cancel, null).create())
                }
            }.setNegativeButton(R.string.cancel, null).create())
    }

    Text(tr(R.string.sections_hint), color = Color(0xFFADADB0), fontSize = 13.sp, lineHeight = 19.sp)
    SettingRow(tr(R.string.sections_create), tr(R.string.sections_create_hint), Modifier.focusRequester(initialFocus), trailing = "+", onClick = {
        returnFocus = initialFocus
        dialogs.name(R.string.sections_create) { name ->
            val id = SectionPreferences.create(context, name)
            if (id != null) SectionPreferences.read(context).sections.firstOrNull { it.id == id }?.let { selectContents(it) }
            id != null
        }
    })
    options.sections.forEachIndexed { index, section -> key(section.id) {
        val target = remember(section.id) { FocusRequester() }
        val kindDescription = when (section.kind) {
            HomeSectionKind.CUSTOM -> tr(R.string.sections_item_count, section.items.size)
            HomeSectionKind.FAVORITES -> tr(R.string.favorites_order_description, options.favorites.size)
            else -> tr(R.string.sections_automatic)
        }
        SettingRow(sectionTitle(section),
            if (section.visible) tr(R.string.sections_position_description, index + 1, kindDescription) else tr(R.string.sections_hidden_description, kindDescription),
            Modifier.focusRequester(target), trailing = "⋯", onClick = {
                returnFocus = target
                editSection(section, index)
            })
    } }
    Text(tr(R.string.sections_automatic_hint), color = Color(0xFFADADB0), fontSize = 12.sp, lineHeight = 18.sp)
    SettingRow(tr(R.string.home_order), tr(homeOptions.order.titleRes), onClick = {
        HomePreferences.setOrder(context, HomeOrder.entries[(homeOptions.order.ordinal + 1) % HomeOrder.entries.size])
    })
    if (orderFavorites) FavoriteOrderDialog(apps = apps, games = games, movies = movies, onDismiss = {
        orderFavorites = false
        focusRevision++
    })
}

/** Assignment is transactional from the user's point of view: Cancel also discards draft sections. */
@Composable internal fun SectionMembershipDialog(itemKey: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(context, resources, itemKey) {
        val dialogs = SectionDialogs(context, resources) { dismiss() }
        val sections = SectionPreferences.read(context).sections.filter { it.kind == HomeSectionKind.CUSTOM }
        val selected = sections.filter { itemKey in it.items }.map { it.id }.toMutableSet()
        val drafts = mutableListOf<Pair<String, String>>()
        lateinit var showPicker: () -> Unit
        showPicker = {
            val entries = sections.map { it.id to sectionTitle(resources, it) } + drafts
            val builder = AlertDialog.Builder(context).setTitle(R.string.sections_assign)
            if (entries.isEmpty()) builder.setMessage(R.string.sections_no_custom)
            else builder.setMultiChoiceItems(entries.map { it.second }.toTypedArray(), entries.map { it.first in selected }.toBooleanArray()) { _, index, enabled ->
                if (enabled) selected.add(entries[index].first) else selected.remove(entries[index].first)
            }
            dialogs.show(builder
                .setPositiveButton(R.string.sections_apply) { _, _ ->
                    val saved = selected.filterNot { id -> drafts.any { it.first == id } }.toMutableSet()
                    drafts.filter { it.first in selected }.forEach { (_, name) ->
                        SectionPreferences.create(context, name)?.let { saved.add(it) }
                    }
                    SectionPreferences.setMembership(context, itemKey, saved)
                }
                .setNeutralButton(R.string.sections_create) { _, _ ->
                    dialogs.name(R.string.sections_create) { name ->
                        if (SectionPreferences.read(context).sections.size + drafts.size >= SectionPreferences.MAX_SECTIONS) false
                        else {
                            val id = "draft:${drafts.size}"
                            drafts.add(id to name)
                            selected.add(id)
                            showPicker()
                            true
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null).create())
        }
        showPicker()
        onDispose { dialogs.dispose() }
    }
}

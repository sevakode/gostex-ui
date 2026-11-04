package tv.orbit.shield

import android.content.Context
import android.content.SharedPreferences
import android.icu.text.TimeZoneNames
import android.text.format.DateFormat
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.json.JSONArray
import java.text.Collator
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.TimeZone

internal const val LOCAL_CLOCK_ZONE = "local"
private const val CLOCK_PAGE_SIZE = 12
private const val OTHER_ZONE_GROUP = "other"

internal enum class ClockFormat(val id: String, val titleRes: Int) {
    SYSTEM("system", R.string.clock_format_system),
    H12("12h", R.string.clock_format_12),
    H24("24h", R.string.clock_format_24)
}

internal data class ClockOptions(
    val visible: Boolean = true,
    val zones: List<String> = listOf(LOCAL_CLOCK_ZONE),
    val format: ClockFormat = ClockFormat.SYSTEM,
    val showDate: Boolean = false
)

/** The device's tzdb is the source of selectable IDs and daylight-saving rules. */
internal val availableClockZones: Set<String> by lazy { ZoneId.getAvailableZoneIds().toSet() }

internal fun validClockZone(id: String): Boolean = id == LOCAL_CLOCK_ZONE || id in availableClockZones

internal fun clockZone(id: String, localZone: ZoneId = ZoneId.systemDefault()): ZoneId =
    if (id == LOCAL_CLOCK_ZONE || id !in availableClockZones) localZone else ZoneId.of(id)

internal fun clockTimeAt(instant: Instant, id: String, localZone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
    instant.atZone(clockZone(id, localZone))

internal fun zoneTitle(context: Context, id: String): String {
    if (id == LOCAL_CLOCK_ZONE) return context.getString(R.string.clock_local)
    val locale = context.resources.configuration.locales[0]
    // ICU's location labels come from CLDR. Canonicalization lets aliases use the same labels.
    val canonical = android.icu.util.TimeZone.getCanonicalID(id) ?: id
    return runCatching { TimeZoneNames.getInstance(locale).getExemplarLocationName(canonical) }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: id.replace('_', ' ')
}

internal fun formatClockTime(context: Context, id: String, format: ClockFormat, instant: Instant = Instant.now()): String {
    val locale = context.resources.configuration.locales[0]
    val use24Hours = when (format) {
        ClockFormat.SYSTEM -> DateFormat.is24HourFormat(context)
        ClockFormat.H12 -> false
        ClockFormat.H24 -> true
    }
    val pattern = DateFormat.getBestDateTimePattern(locale, if (use24Hours) "Hm" else "hm")
    val formatter = SimpleDateFormat(pattern, locale)
    formatter.timeZone = TimeZone.getTimeZone(clockZone(id))
    return formatter.format(Date.from(instant))
}

internal object ClockPreferences {
    const val KEY_PREFIX = "clock."
    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    fun read(context: Context): ClockOptions {
        val saved = prefs(context)
        val rawZones = runCatching { saved.getString(KEY_PREFIX + "zones", null) }.getOrNull()
        val zones = rawZones?.let { raw -> runCatching {
            val array = JSONArray(raw)
            val valid = (0 until array.length()).mapNotNull { index ->
                (array.opt(index) as? String)?.takeIf(::validClockZone)
            }.distinct()
            // [] is a deliberate empty list. A nonempty but wholly invalid list is corrupt.
            if (array.length() > 0 && valid.isEmpty()) listOf(LOCAL_CLOCK_ZONE) else valid
        }.getOrNull() } ?: listOf(LOCAL_CLOCK_ZONE)
        val rawFormat = runCatching { saved.getString(KEY_PREFIX + "format", null) }.getOrNull()
        return ClockOptions(
            visible = runCatching { saved.getBoolean(KEY_PREFIX + "visible", true) }.getOrDefault(true),
            zones = zones,
            format = ClockFormat.entries.firstOrNull { it.id == rawFormat } ?: ClockFormat.SYSTEM,
            showDate = runCatching { saved.getBoolean(KEY_PREFIX + "showDate", false) }.getOrDefault(false)
        )
    }

    fun setVisible(context: Context, visible: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREFIX + "visible", visible).apply()
    }

    fun setFormat(context: Context, format: ClockFormat) {
        prefs(context).edit().putString(KEY_PREFIX + "format", format.id).apply()
    }

    fun setShowDate(context: Context, showDate: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREFIX + "showDate", showDate).apply()
    }

    private fun saveZones(context: Context, zones: List<String>) {
        prefs(context).edit().putString(KEY_PREFIX + "zones", JSONArray(zones.filter(::validClockZone).distinct()).toString()).apply()
    }

    fun addZone(context: Context, id: String) {
        if (!validClockZone(id)) return
        val zones = read(context).zones
        if (id !in zones) saveZones(context, zones + id)
    }

    fun removeZone(context: Context, id: String) {
        saveZones(context, read(context).zones.filterNot { it == id })
    }

    fun moveZone(context: Context, id: String, delta: Int) {
        val zones = read(context).zones.toMutableList()
        val from = zones.indexOf(id)
        if (from < 0 || delta == 0) return
        val to = (from.toLong() + delta).coerceIn(0, zones.lastIndex.toLong()).toInt()
        if (from == to) return
        zones.add(to, zones.removeAt(from))
        saveZones(context, zones)
    }
}

@Composable internal fun rememberClockOptions(): ClockOptions {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }
    var options by remember(context) { mutableStateOf(ClockPreferences.read(context)) }
    DisposableEffect(context, prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(ClockPreferences.KEY_PREFIX)) options = ClockPreferences.read(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        options = ClockPreferences.read(context)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return options
}

internal fun clockParent(section: String): String = when {
    section == "clock" -> "root"
    section == "clock/add" -> "clock"
    section.startsWith("clock/add/") -> "clock/add"
    else -> "clock"
}

internal fun clockSectionTitleRes(section: String): Int = when {
    section.startsWith("clock/manage/") -> R.string.clock_manage_title
    section.startsWith("clock/add") -> R.string.clock_add_title
    else -> R.string.clock_title
}

private fun clockGroup(id: String): String = if ('/' in id) id.substringBefore('/') else OTHER_ZONE_GROUP

private fun clockGroupTitle(context: Context, group: String): String {
    val resource = when (group) {
        "Africa" -> R.string.clock_group_africa
        "America" -> R.string.clock_group_america
        "Antarctica" -> R.string.clock_group_antarctica
        "Arctic" -> R.string.clock_group_arctic
        "Asia" -> R.string.clock_group_asia
        "Atlantic" -> R.string.clock_group_atlantic
        "Australia" -> R.string.clock_group_australia
        "Europe" -> R.string.clock_group_europe
        "Indian" -> R.string.clock_group_indian
        "Pacific" -> R.string.clock_group_pacific
        "Etc" -> R.string.clock_group_utc
        OTHER_ZONE_GROUP -> R.string.clock_group_other
        else -> return group.replace('_', ' ')
    }
    return context.getString(resource)
}

/** All routes live in the parent's scrollable drawer; only a bounded page is composed. */
@Composable internal fun ClockSettings(initialFocus: FocusRequester, section: String, onSection: (String) -> Unit) {
    val context = LocalContext.current
    val options = rememberClockOptions()
    var returnFocusZone by rememberSaveable {
        mutableStateOf(section.takeIf { it.startsWith("clock/manage/") }?.removePrefix("clock/manage/"))
    }
    val locale = LocalConfiguration.current.locales[0]
    val muted = Color(0xFFADADB0)
    val groups = remember { availableClockZones.groupBy(::clockGroup) }
    when {
        section == "clock" -> {
            val focusZone = returnFocusZone?.takeIf { it in options.zones }
            Text(tr(R.string.clock_hint), color = muted, fontSize = 13.sp, lineHeight = 19.sp)
            SettingRow(tr(R.string.clock_visibility), tr(if (options.visible) R.string.enabled else R.string.disabled),
                modifier = if (focusZone == null) Modifier.focusRequester(initialFocus) else Modifier,
                onClick = { ClockPreferences.setVisible(context, !options.visible) })
            SettingRow(tr(R.string.clock_format), tr(options.format.titleRes), onClick = {
                ClockPreferences.setFormat(context, ClockFormat.entries[(options.format.ordinal + 1) % ClockFormat.entries.size])
            })
            SettingRow(tr(R.string.clock_show_date), tr(if (options.showDate) R.string.enabled else R.string.disabled),
                onClick = { ClockPreferences.setShowDate(context, !options.showDate) })
            SettingRow(tr(R.string.clock_add), onClick = { onSection("clock/add") })
            Text(tr(R.string.clock_order_hint), color = muted, fontSize = 12.sp, lineHeight = 17.sp)
            if (options.zones.isEmpty()) Text(tr(R.string.clock_empty), color = muted, fontSize = 13.sp)
            options.zones.forEachIndexed { index, id -> key(id) {
                SettingRow(zoneTitle(context, id),
                    tr(R.string.clock_order_value, index + 1, if (id == LOCAL_CLOCK_ZONE) ZoneId.systemDefault().id else id),
                    modifier = if (focusZone == id) Modifier.focusRequester(initialFocus) else Modifier,
                    onClick = { returnFocusZone = id; onSection("clock/manage/$id") })
            } }
        }
        section == "clock/add" -> {
            Text(tr(R.string.clock_picker_hint), color = muted, fontSize = 13.sp, lineHeight = 19.sp)
            val localSelected = LOCAL_CLOCK_ZONE in options.zones
            SettingRow(tr(R.string.clock_local), ZoneId.systemDefault().id,
                modifier = Modifier.focusRequester(initialFocus), isSelected = localSelected,
                trailing = if (localSelected) "✓" else "+", onClick = {
                    ClockPreferences.addZone(context, LOCAL_CLOCK_ZONE)
                    returnFocusZone = LOCAL_CLOCK_ZONE
                    onSection("clock")
                })
            val sortedGroups = remember(groups, locale) {
                val collator = Collator.getInstance(locale)
                groups.keys.sortedWith { a, b ->
                    if (a == b) 0 else if (a == OTHER_ZONE_GROUP) 1 else if (b == OTHER_ZONE_GROUP) -1
                    else collator.compare(clockGroupTitle(context, a), clockGroupTitle(context, b))
                }
            }
            sortedGroups.forEach { group -> key(group) {
                SettingRow(clockGroupTitle(context, group), tr(R.string.clock_group_count, groups.getValue(group).size),
                    onClick = { onSection("clock/add/$group") })
            } }
        }
        section.startsWith("clock/add/") -> {
            val group = section.removePrefix("clock/add/")
            val zones = remember(group, locale) {
                val collator = Collator.getInstance(locale)
                groups[group].orEmpty().sortedWith { a, b ->
                    val byName = collator.compare(zoneTitle(context, a), zoneTitle(context, b))
                    if (byName == 0) a.compareTo(b) else byName
                }
            }
            var requestedPage by rememberSaveable(section) { mutableIntStateOf(0) }
            val lastPage = ((zones.size - 1).coerceAtLeast(0) / CLOCK_PAGE_SIZE)
            val page = requestedPage.coerceIn(0, lastPage)
            val pageZones = zones.drop(page * CLOCK_PAGE_SIZE).take(CLOCK_PAGE_SIZE)
            Text(clockGroupTitle(context, group), color = Color(0xFFCCFF00), fontSize = 13.sp)
            Text(tr(R.string.clock_page, page + 1, lastPage + 1), color = muted, fontSize = 12.sp)
            if (pageZones.isEmpty()) {
                SettingRow(tr(R.string.clock_back_to_groups), modifier = Modifier.focusRequester(initialFocus), onClick = { onSection("clock/add") })
            }
            pageZones.forEachIndexed { index, id -> key(id) {
                val selected = id in options.zones
                SettingRow(zoneTitle(context, id), id, modifier = if (index == 0) Modifier.focusRequester(initialFocus) else Modifier,
                    isSelected = selected, trailing = if (selected) "✓" else "+", onClick = {
                        ClockPreferences.addZone(context, id)
                        returnFocusZone = id
                        onSection("clock")
                    })
            } }
            if (page > 0) SettingRow(tr(R.string.clock_previous_page), trailing = "‹", onClick = { requestedPage = page - 1 })
            if (page < lastPage) SettingRow(tr(R.string.clock_next_page), onClick = { requestedPage = page + 1 })
            LaunchedEffect(section, page) { withFrameNanos { }; initialFocus.requestFocus() }
        }
        section.startsWith("clock/manage/") -> {
            val id = section.removePrefix("clock/manage/")
            val index = options.zones.indexOf(id)
            if (index < 0) {
                SettingRow(tr(R.string.clock_back_to_clocks), modifier = Modifier.focusRequester(initialFocus), onClick = { onSection("clock") })
            } else {
                Text(zoneTitle(context, id), color = Color(0xFFCCFF00), fontSize = 16.sp)
                Text(if (id == LOCAL_CLOCK_ZONE) ZoneId.systemDefault().id else id, color = muted, fontSize = 12.sp)
                Text(tr(R.string.clock_move_hint), color = muted, fontSize = 12.sp, lineHeight = 17.sp)
                if (index > 0) SettingRow(tr(R.string.clock_move_earlier), modifier = Modifier.focusRequester(initialFocus), trailing = "↑", onClick = {
                    ClockPreferences.moveZone(context, id, -1); returnFocusZone = id; onSection("clock")
                })
                if (index < options.zones.lastIndex) SettingRow(tr(R.string.clock_move_later),
                    modifier = if (index == 0) Modifier.focusRequester(initialFocus) else Modifier, trailing = "↓", onClick = {
                        ClockPreferences.moveZone(context, id, 1); returnFocusZone = id; onSection("clock")
                    })
                SettingRow(tr(R.string.clock_remove), modifier = if (options.zones.size == 1) Modifier.focusRequester(initialFocus) else Modifier,
                    trailing = "−", onClick = {
                        returnFocusZone = options.zones.getOrNull(index + 1) ?: options.zones.getOrNull(index - 1)
                        ClockPreferences.removeZone(context, id)
                        onSection("clock")
                    })
            }
        }
        else -> SettingRow(tr(R.string.clock_back_to_clocks), modifier = Modifier.focusRequester(initialFocus), onClick = { onSection("clock") })
    }
}

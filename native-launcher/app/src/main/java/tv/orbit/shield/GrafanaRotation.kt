package tv.orbit.shield

import java.net.URI

internal data class GrafanaRotationPage(val uid: String, val url: String)

/** A stable catalog order; stars only filter it. Never borrow variables from the last rendered page. */
internal fun grafanaRotationPages(
    catalog: GrafanaCatalogResult,
    mode: GrafanaRotationMode,
    favorites: Set<String>,
    anchorUrl: String
): List<GrafanaRotationPage> {
    if (mode == GrafanaRotationMode.SINGLE) return emptyList()
    return catalog.dashboards.asSequence()
        .filter { mode == GrafanaRotationMode.ALL || it.uid in favorites }
        .mapNotNull { dashboard ->
            grafanaDashboardUrl(catalog.server, catalog.orgId, dashboard, anchorUrl)
                ?.let { GrafanaRotationPage(dashboard.uid, it) }
        }.distinctBy { it.uid }.toList()
}

internal fun grafanaRotationAnchorUid(server: GrafanaCatalogServer, url: String): String? {
    if (!server.accepts(url)) return null
    val relative = runCatching { URI(url).rawPath.removePrefix(URI(server.baseUrl).rawPath) }.getOrNull() ?: return null
    val parts = relative.split('/')
    return parts.getOrNull(1)?.takeIf { parts.firstOrNull() == "d" && validGrafanaUid(it) }
}

internal fun grafanaRotationRemainingMillis(seconds: Int, visibleAt: Long, now: Long): Long {
    val duration = seconds.takeIf { it in GrafanaPreferences.rotationChoices } ?: 120
    return (duration * 1000L - (now - visibleAt).coerceAtLeast(0L)).coerceAtLeast(0L)
}

/** Selection survives star/filter changes if that dashboard remains in the queue. */
internal class GrafanaRotationQueue {
    var pages: List<GrafanaRotationPage> = emptyList()
        private set
    private var index = 0
    val current: GrafanaRotationPage? get() = pages.getOrNull(index)
    val canAdvance: Boolean get() = pages.size > 1

    fun replace(value: List<GrafanaRotationPage>, preferredUid: String?) {
        val previousUid = current?.uid
        pages = value.distinctBy { it.uid }
        index = pages.indexOfFirst { it.uid == previousUid }.takeIf { it >= 0 }
            ?: pages.indexOfFirst { it.uid == preferredUid }.takeIf { it >= 0 } ?: 0
    }

    fun advance(): GrafanaRotationPage? {
        if (canAdvance) index = (index + 1) % pages.size
        return current
    }

    fun clear() { pages = emptyList(); index = 0 }
}

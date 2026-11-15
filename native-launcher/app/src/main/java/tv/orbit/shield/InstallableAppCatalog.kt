package tv.orbit.shield

internal data class InstallableApp(val packageName: String, val titleRes: Int, val kindRes: Int)

/** Presentation for existing installer sources; package trust and release policy stay there. */
internal object InstallableAppCatalog {
    private val metadata = listOf(
        InstallableApp("com.brouken.player", R.string.app_catalog_just_player, R.string.app_catalog_video_player),
        InstallableApp("me.magnum.melonds", R.string.app_catalog_melonds, R.string.app_catalog_emulator),
        InstallableApp("com.flycast.emulator", R.string.app_catalog_flycast, R.string.app_catalog_emulator),
        InstallableApp("org.azahar_emu.azahar", R.string.app_catalog_azahar, R.string.app_catalog_emulator),
        InstallableApp(OfficialApkPolicy.VLC_PACKAGE, R.string.vlc_install_name, R.string.app_catalog_video_player),
    ).associateBy { it.packageName }
    val all: List<InstallableApp> = OfficialApkPolicy.sources.keys.mapNotNull(metadata::get)
    private val byPackage = all.associateBy { it.packageName }
    val packageNames: List<String> = all.map { it.packageName }

    fun ordered(options: AppsOptions): List<InstallableApp> =
        (options.catalogOrder + packageNames).distinct().mapNotNull(byPackage::get)

    fun visible(options: AppsOptions): List<InstallableApp> =
        ordered(options).filterNot { it.packageName in options.catalogHidden }
}

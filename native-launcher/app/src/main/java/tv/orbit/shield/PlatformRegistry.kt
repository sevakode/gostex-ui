package tv.orbit.shield

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import java.util.Locale

internal enum class LaunchKind { VIEW, MELON_DS, DUCKSTATION, DOLPHIN, RETROARCH }

/** Paths and Android runtime metadata resolved for the installed RetroArch variant. */
internal data class RetroArchLaunchArguments(
    val romPath: String, val corePath: String, val dataDir: String, val apkPath: String,
    val storageRoot: String, val externalFiles: String, val configFile: String, val inputMethod: String?
)

internal data class PlatformSpec(
    // These are persisted in existing roots, imports and catalog entries. Do not translate them.
    val id: String,
    val titleRes: Int,
    val emulator: String,
    val component: String,
    val extensions: Set<String>,
    val launchKind: LaunchKind = LaunchKind.VIEW,
    val siblingExtensions: Set<String> = emptySet(),
    val hintRes: Int? = null
) {
    fun accepts(filename: String): Boolean = filename.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions
}

/** Installed standalone emulators and their distinct, documented external launch contracts. */
internal object PlatformRegistry {
    private const val EX_ACTIVITY = "com.imagine.BaseActivity"
    private val cartridgeArchives = setOf("zip", "7z", "rar")
    private val dolphinImages = setOf("iso", "gcm", "gcz", "ciso", "rvz", "wia", "dol", "elf")

    val all: List<PlatformSpec> = listOf(
        PlatformSpec("SNES", R.string.platform_snes, "Snes9x EX+", "com.explusalpha.Snes9xPlus/$EX_ACTIVITY", setOf("sfc", "smc", "zip")),
        PlatformSpec("PSP", R.string.platform_psp, "PPSSPP", "org.ppsspp.ppsspp/.PpssppActivity", setOf("iso", "cso", "pbp", "elf")),
        PlatformSpec("Nintendo 64", R.string.platform_n64, "M64Plus FZ", "org.mupen64plusae.v3.fzurita/paulscode.android.mupen64plusae.SplashActivity", setOf("z64", "n64", "v64", "zip")),
        PlatformSpec("Dreamcast", R.string.platform_dreamcast, "Flycast", "com.flycast.emulator/.MainActivity", setOf("cdi", "gdi", "chd", "cue"), siblingExtensions = setOf("gdi", "cue")),
        PlatformSpec("NES", R.string.platform_nes, "NES.emu", "com.explusalpha.NesEmu/$EX_ACTIVITY", setOf("nes", "unf", "unif") + cartridgeArchives),
        PlatformSpec("Game Boy", R.string.platform_gb, "GBC.emu", "com.explusalpha.GbcEmu/$EX_ACTIVITY", setOf("gb", "dmg") + cartridgeArchives),
        PlatformSpec("Game Boy Color", R.string.platform_gbc, "GBC.emu", "com.explusalpha.GbcEmu/$EX_ACTIVITY", setOf("gbc") + cartridgeArchives),
        PlatformSpec("Game Boy Advance", R.string.platform_gba, "GBA.emu", "com.explusalpha.GbaEmu/$EX_ACTIVITY", setOf("gba", "mb") + cartridgeArchives),
        PlatformSpec("Mega Drive", R.string.platform_md, "MD.emu", "com.explusalpha.MdEmu/$EX_ACTIVITY", setOf("md", "gen", "smd") + cartridgeArchives),
        PlatformSpec("Master System", R.string.platform_sms, "MD.emu", "com.explusalpha.MdEmu/$EX_ACTIVITY", setOf("sms") + cartridgeArchives),
        PlatformSpec("PC Engine", R.string.platform_pce, "PCE.emu", "com.PceEmu/$EX_ACTIVITY", setOf("pce") + cartridgeArchives),
        PlatformSpec("Neo Geo Pocket", R.string.platform_ngp, "NGP.emu", "com.explusalpha.NgpEmu/$EX_ACTIVITY", setOf("ngp", "ngc", "npc") + cartridgeArchives),
        PlatformSpec("Nintendo DS", R.string.platform_nds, "melonDS", "me.magnum.melonds/.ui.emulator.EmulatorActivity", setOf("nds", "zip", "7z"), LaunchKind.MELON_DS, hintRes = R.string.platform_nds_hint),
        // DiscImport validates CUE/M3U before creating one descriptor launch entry.
        PlatformSpec("PlayStation", R.string.platform_ps1, "DuckStation", "com.github.stenzek.duckstation/.EmulationActivity", setOf("chd", "pbp", "exe", "cue", "m3u"), LaunchKind.DUCKSTATION, siblingExtensions = setOf("cue", "m3u", "chd", "pbp"), hintRes = R.string.platform_ps1_hint),
        PlatformSpec("GameCube", R.string.platform_gc, "Dolphin", "org.dolphinemu.dolphinemu/.ui.main.TvMainActivity", dolphinImages, LaunchKind.DOLPHIN),
        PlatformSpec("Wii", R.string.platform_wii, "Dolphin", "org.dolphinemu.dolphinemu/.ui.main.TvMainActivity", dolphinImages + "wbfs", LaunchKind.DOLPHIN),
        // Azahar 2126.2 EmulationFragment reads intent.data with a read-only file descriptor.
        // CIA is an installation package, not a directly launchable cartridge image.
        PlatformSpec("Nintendo 3DS", R.string.platform_3ds, "Azahar", "org.azahar_emu.azahar/org.citra.citra_emu.activities.EmulationActivity",
            setOf("cci", "cxi", "3ds", "3dsx", "app", "axf", "elf", "zcci", "zcxi", "z3dsx"), hintRes = R.string.platform_3ds_hint)
    )
    private val byId = all.associateBy { it.id }

    fun find(id: String): PlatformSpec? = byId[id]
    /** Resolve the launch backend without changing the canonical import format or saved game identity. */
    fun resolve(id: String, ndsEmulator: NdsEmulatorChoice): PlatformSpec? {
        val original = find(id) ?: return null
        return if (id == "Nintendo DS" && ndsEmulator == NdsEmulatorChoice.RETROARCH_MELON_DS_DS) {
            original.copy(
                emulator = "RetroArch · melonDS DS",
                component = "com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture",
                launchKind = LaunchKind.RETROARCH,
                hintRes = R.string.nds_retroarch_hint
            )
        } else original
    }
    fun title(context: Context, id: String): String = find(id)?.let { context.getString(it.titleRes) } ?: id

    fun unavailableReason(context: Context, spec: PlatformSpec): String? {
        val component = ComponentName.unflattenFromString(spec.component)
            ?: return context.getString(R.string.emulator_unavailable, spec.emulator)
        val info = try {
            @Suppress("DEPRECATION")
            context.packageManager.getActivityInfo(component, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return context.getString(R.string.emulator_unavailable, spec.emulator)
        }
        val requiredPermission = info.permission
        return if (!info.exported || !info.enabled || !info.applicationInfo.enabled ||
            (requiredPermission != null && context.checkSelfPermission(requiredPermission) != PackageManager.PERMISSION_GRANTED)) {
            context.getString(R.string.emulator_unavailable, spec.emulator)
        } else null
    }

    fun launchIntent(spec: PlatformSpec, gameUri: Uri, treeUri: Uri? = null, retroArch: RetroArchLaunchArguments? = null): Intent {
        val intent = Intent(when (spec.launchKind) {
            LaunchKind.MELON_DS -> "me.magnum.melonds.LAUNCH_ROM"
            LaunchKind.DOLPHIN, LaunchKind.RETROARCH -> Intent.ACTION_MAIN
            else -> Intent.ACTION_VIEW
        }).apply {
            component = ComponentName.unflattenFromString(spec.component)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = gameUri
        }
        if (spec.launchKind == LaunchKind.RETROARCH) {
            // Verified against RetroArch 1.22.2: RetroActivityFuture consumes these extras;
            // MainMenuActivity ignores an incoming ROM and raw content:// is not a ROM path.
            val args = requireNotNull(retroArch)
            intent.putExtra("ROM", args.romPath)
            intent.putExtra("LIBRETRO", args.corePath)
            intent.putExtra("DATADIR", args.dataDir)
            intent.putExtra("APK", args.apkPath)
            intent.putExtra("SDCARD", args.storageRoot)
            intent.putExtra("EXTERNAL", args.externalFiles)
            intent.putExtra("CONFIGFILE", args.configFile)
            args.inputMethod?.let { intent.putExtra("IME", it) }
        }
        if (spec.launchKind == LaunchKind.DUCKSTATION) {
            // The exact installed APK reads bootPath without converting content URIs.
            // Its FileHelper uses a path-based SAF representation for native file access.
            val path = if (gameUri.scheme == "content" && gameUri.authority == "com.android.externalstorage.documents") {
                DuckStationPath.externalDocument(gameUri.authority!!, treeUri?.let { DocumentsContract.getTreeDocumentId(it) }, DocumentsContract.getDocumentId(gameUri))
            } else gameUri.toString()
            intent.putExtra("bootPath", path)
        }
        if (gameUri.scheme == "content") {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val extension = gameUri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (extension in spec.siblingExtensions && treeUri?.scheme == "content") {
                // Read-only tree access covers CUE/M3U tracks/discs and optional SBI files.
                // Existing Flycast uses the same grant. Dolphin treats ClipData as discs,
                // so it receives only intent.data and never this directory grant.
                intent.clipData = ClipData.newRawUri("ROM directory", treeUri)
                intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }
        }
        return intent
    }
}

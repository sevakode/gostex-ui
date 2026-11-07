package tv.orbit.shield

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Text

/** The item itself is retained while its originating shelf can update underneath the dialog. */
internal sealed interface TileMenuTarget {
    data class GameItem(val game: Game) : TileMenuTarget
    data class AppItem(val app: TvApp) : TileMenuTarget
    data class MovieItem(val movie: SavedMovie) : TileMenuTarget
}

/**
 * Action callbacks own dismissal, so the parent can restore the originating card or open details.
 * This menu does not launch an app, change preferences, or delete content by itself.
 */
@Composable
internal fun TileContextMenu(
    target: TileMenuTarget,
    isFavorite: Boolean,
    currentSectionTitle: String? = null,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    onCover: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenApp: () -> Unit,
    onSections: () -> Unit,
    onFavoriteOrder: () -> Unit,
    onPrioritize: () -> Unit,
    onAppInfo: () -> Unit,
    onDismiss: () -> Unit
) {
    val title = when (target) {
        is TileMenuTarget.GameItem -> target.game.title
        is TileMenuTarget.AppItem -> target.app.name
        is TileMenuTarget.MovieItem -> target.movie.title
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val firstFocus = remember(target) { FocusRequester() }
        val confirmGate = remember(target) { MenuConfirmKeyGate() }
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)).safeDrawingPadding().padding(24.dp)
            .onPreviewKeyEvent {
                val event = it.nativeKeyEvent
                confirmGate.consume(event.keyCode, event.action, event.repeatCount, event.isCanceled)
            }) {
            Column(
                Modifier.align(Alignment.CenterEnd).widthIn(max = 360.dp).fillMaxWidth().heightIn(max = 480.dp)
                    .background(Color(0xFF0A0A0A)).border(1.dp, Color(0xFF424244))
                    .verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    tr(when (target) { is TileMenuTarget.GameItem -> R.string.tile_game_options; is TileMenuTarget.AppItem -> R.string.tile_app_options; is TileMenuTarget.MovieItem -> R.string.media_options }),
                    color = Color(0xFFCCFF00), fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
                Text(title, color = Color(0xFFF5F5F5), fontSize = 23.sp, fontWeight = FontWeight.Black,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                when (target) {
                    is TileMenuTarget.GameItem -> {
                        SettingRow(tr(R.string.tile_play), modifier = Modifier.focusRequester(firstFocus),
                            trailing = "▶", onClick = onPlay)
                        SettingRow(tr(R.string.tile_details), onClick = onDetails)
                        SettingRow(tr(R.string.cover_manage), onClick = onCover)
                    }
                    is TileMenuTarget.AppItem -> {
                        SettingRow(tr(R.string.tile_open), modifier = Modifier.focusRequester(firstFocus),
                            trailing = "▶", onClick = onOpenApp)
                    }
                    is TileMenuTarget.MovieItem -> {
                        SettingRow(tr(R.string.tile_open), modifier = Modifier.focusRequester(firstFocus),
                            trailing = "▶", onClick = onOpenApp)
                        SettingRow(tr(R.string.cover_manage), onClick = onCover)
                    }
                }
                SettingRow(tr(if (isFavorite) R.string.tile_favorite_remove else R.string.tile_favorite_add),
                    trailing = if (isFavorite) "−" else "+", onClick = onToggleFavorite)
                if (isFavorite) SettingRow(tr(R.string.favorites_order_title), trailing = "↔", onClick = onFavoriteOrder)
                SettingRow(tr(R.string.tile_sections), onClick = onSections)
                if (!currentSectionTitle.isNullOrBlank()) {
                    SettingRow(tr(R.string.tile_section_first, currentSectionTitle), trailing = "↑", onClick = onPrioritize)
                }
                if (target is TileMenuTarget.AppItem) SettingRow(tr(R.string.tile_app_info), onClick = onAppInfo)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF424244)))
                SettingRow(tr(R.string.close), trailing = "×", onClick = onDismiss)
                Spacer(Modifier.height(4.dp))
                ControlHints(R.string.control_select, R.string.close)
            }
        }
        LaunchedEffect(target) {
            withFrameNanos { }
            firstFocus.requestFocus()
        }
    }
}

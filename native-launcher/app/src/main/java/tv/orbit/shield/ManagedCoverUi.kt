package tv.orbit.shield

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text

@Composable internal fun ManagedCoverSettings(activity: MainActivity, games: List<Game>, initialFocus: FocusRequester) {
    val options = LocalManagedCoverOptions.current
    val folderLabel = if (options.folder.isEmpty()) tr(R.string.covers_no_folder) else runCatching {
        DocumentsContract.getTreeDocumentId(Uri.parse(options.folder)).substringAfter(':')
    }.getOrDefault(tr(R.string.covers_folder_selected))
    Text(tr(R.string.covers_hint), color=Color(0xFFADADB0), fontSize=13.sp, lineHeight=19.sp)
    OnlineCoverSourceSettings(activity,initialFocus)
    SettingRow(tr(R.string.covers_shared_folder), folderLabel, onClick=activity::chooseCoversFolder)
    if (options.folder.isNotEmpty()) {
        SettingRow(tr(R.string.covers_automatic), tr(if (options.automatic) R.string.enabled else R.string.disabled),
            onClick={ activity.setAutomaticCovers(!options.automatic) })
        SettingRow(tr(if (activity.coversBusy) R.string.covers_syncing else R.string.covers_sync_now),
            onClick={ if (!activity.coversBusy) activity.syncCovers(true) })
        Text(tr(R.string.covers_exchange_hint), color=Color(0xFFADADB0), fontSize=12.sp, lineHeight=18.sp)
        if (activity.coversStatus.isNotEmpty()) Text(activity.coversStatus, color=Color(0xFFCCFF00), fontSize=12.sp, lineHeight=18.sp)
        SettingRow(tr(R.string.covers_disconnect), onClick=activity::disconnectCoversFolder)
    }
    Text(tr(R.string.covers_games), color=Color(0xFFF5F5F5), fontSize=15.sp, fontWeight=FontWeight.Bold)
    if (games.isEmpty()) Text(tr(R.string.covers_empty),color=Color(0xFFADADB0),fontSize=13.sp)
    // Do not eagerly compose an unbounded library inside the settings scroll view.
    SettingRow(tr(R.string.covers_choose_game), tr(R.string.covers_hold_hint), onClick={
        if (games.isNotEmpty()) android.app.AlertDialog.Builder(activity).setTitle(activity.getString(R.string.covers_choose_game))
            .setItems(games.map { it.title }.toTypedArray()) { _, index -> activity.editGameCover(games[index].id) }
            .setNegativeButton(activity.getString(R.string.cancel), null).show()
    })
}

@Composable internal fun GameCoverEditor(activity: MainActivity, game: Game, onClose: () -> Unit) {
    var finding by remember(game.id) { mutableStateOf(false) }
    var returnToSearch by remember { mutableStateOf(false) }
    val firstFocus = remember(game.id) { FocusRequester() }
    val options = LocalManagedCoverOptions.current
    val entry = LocalManagedCovers.current[game.id]
    val choice = options.choices[game.id] ?: CoverChoice.AUTO
    Dialog(onDismissRequest=onClose, properties=DialogProperties(usePlatformDefaultWidth=false, decorFitsSystemWindows=false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.6f)).safeDrawingPadding()) {
            Column(Modifier.align(Alignment.CenterEnd).widthIn(max=400.dp).fillMaxWidth().fillMaxHeight().background(Color(0xFF0A0A0A))
                .border(1.dp,Color(0xFF424244)).padding(22.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(tr(R.string.cover_manage), color=Color(0xFFCCFF00),fontSize=12.sp,fontWeight=FontWeight.Bold)
                Text(game.title,color=Color(0xFFF5F5F5),fontSize=23.sp,fontWeight=FontWeight.Black,maxLines=2,overflow=TextOverflow.Ellipsis)
                CoverArtwork(game.cover.ifEmpty { game.scene }, Modifier.width(220.dp).align(Alignment.CenterHorizontally).aspectRatio(TileLayout.aspectRatio), game.id)
                Text(tr(when (choice) { CoverChoice.MANUAL -> R.string.cover_choice_manual; CoverChoice.ORIGINAL -> R.string.cover_choice_original; else -> R.string.cover_choice_auto }),
                    color=Color(0xFFADADB0),fontSize=13.sp)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                SettingRow(tr(R.string.cover_find_online), modifier=Modifier.focusRequester(firstFocus), onClick={ if (!activity.coverEditBusy) finding=true })
                if (options.folder.isNotEmpty()) SettingRow(tr(if (activity.coverEditBusy) R.string.covers_syncing else R.string.cover_choose_shared),
                    onClick={ activity.chooseSharedCover(game.id) })
                SettingRow(tr(if (activity.coverEditBusy) R.string.covers_syncing else R.string.cover_choose_file),
                    
                    onClick={ activity.chooseCover(game.id) })
                if (!entry?.manual.isNullOrEmpty()) SettingRow(tr(R.string.cover_use_manual),isSelected=choice==CoverChoice.MANUAL,
                    trailing=if(choice==CoverChoice.MANUAL) "✓" else "",onClick={ activity.selectCoverChoice(game.id,CoverChoice.MANUAL) })
                SettingRow(tr(R.string.cover_use_auto), isSelected=choice==CoverChoice.AUTO,
                    trailing=if(choice==CoverChoice.AUTO) "✓" else "",onClick={ activity.selectCoverChoice(game.id,CoverChoice.AUTO) })
                SettingRow(tr(R.string.cover_use_original), isSelected=choice==CoverChoice.ORIGINAL,
                    trailing=if(choice==CoverChoice.ORIGINAL) "✓" else "",onClick={ activity.selectCoverChoice(game.id,CoverChoice.ORIGINAL) })
                if (!entry?.sourceName.isNullOrEmpty() && choice == CoverChoice.MANUAL) Text(listOf(entry.sourceName,entry.author).filter { it.isNotBlank() }.joinToString(" · "),color=Color(0xFFADADB0),fontSize=12.sp)
                Text(tr(R.string.cover_original_hint),color=Color(0xFFADADB0),fontSize=12.sp,lineHeight=18.sp)
                SettingRow(tr(R.string.close), trailing="×",onClick=onClose)
                }
                ControlHints(R.string.control_select,R.string.back)
            }
        }
        LaunchedEffect(game.id) { withFrameNanos { }; firstFocus.requestFocus() }
        LaunchedEffect(returnToSearch) { if(returnToSearch) { withFrameNanos { }; runCatching { firstFocus.requestFocus() }; returnToSearch=false } }
        if (finding) OnlineCoverPicker(activity,game) { finding=false;returnToSearch=true }
    }
}

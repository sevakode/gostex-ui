package tv.orbit.shield

import android.app.AlertDialog
import android.graphics.Bitmap
import android.widget.EditText
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable internal fun OnlineCoverPicker(activity: MainActivity, game: Game, onClose: () -> Unit) {
    val options = rememberOnlineCoverOptions()
    val client = remember(activity,options.apiKey,options.landscapeFirst) { OnlineCoverClient(activity,options.apiKey,options.landscapeFirst) }
    val scope = rememberCoroutineScope()
    var query by remember(game.id) { mutableStateOf(coverSearchTitle(game.title)) }
    var titles by remember { mutableStateOf<List<OnlineCoverGame>>(emptyList()) }
    var images by remember { mutableStateOf<List<OnlineCoverImage>>(emptyList()) }
    var preview by remember { mutableStateOf<OnlineCoverImage?>(null) }
    var providerIndex by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val first = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    var generation by remember { mutableIntStateOf(0) }
    var resultVersion by remember { mutableIntStateOf(0) }
    fun errorText(e: Exception): String = activity.getString(when {
        e is CoverKeyRequired -> R.string.cover_key_missing
        e is CoverHttpError && e.status in setOf(401,403) -> R.string.cover_access_failed
        e is CoverHttpError && e.status == 429 -> R.string.cover_rate_limited
        else -> R.string.cover_network_error
    })
    fun search(start: Int = 0, refresh: Boolean = false) {
        job?.cancel()
        val ticket=++generation
        job = scope.launch {
            busy=true;message="";titles=emptyList();images=emptyList();preview=null
            val notices=mutableListOf<String>()
            try {
                if (options.providers.isEmpty()) { message=activity.getString(R.string.cover_online_disabled); return@launch }
                for (index in start until options.providers.size) {
                    providerIndex=index
                    val provider=options.providers[index]
                    val result=try { client.search(provider,game.platform,query,refresh) }
                    catch (e:CancellationException) { throw e }
                    catch (e:Exception) { notices += "${provider.label}: ${errorText(e)}"; continue }
                    if (!result.isEmpty) {
                        titles=result.games;images=result.images;preview=images.firstOrNull()
                        message=notices.joinToString("\n");return@launch
                    }
                    notices += "${provider.label}: ${activity.getString(R.string.cover_no_matches)}"
                }
                message=notices.joinToString("\n")
            } finally { if(ticket==generation) { busy=false;resultVersion++ } }
        }
    }
    fun loadGrids(title: OnlineCoverGame) {
        job?.cancel()
        val ticket=++generation
        job=scope.launch {
            busy=true;message="";titles=emptyList();images=emptyList();preview=null
            var fallback=false
            try {
                images=client.grids(title);preview=images.firstOrNull()
                fallback=images.isEmpty() && providerIndex+1<options.providers.size
                if(images.isEmpty()) message=activity.getString(R.string.cover_no_matches)
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { message=errorText(e) }
            finally { if(ticket==generation) { busy=false;resultVersion++ } }
            if(fallback && ticket==generation) search(providerIndex+1)
        }
    }
    fun apply(image: OnlineCoverImage) {
        job?.cancel()
        val ticket=++generation
        job=scope.launch {
            busy=true;message=""
            try {
                val bytes=client.image(image,false)
                activity.applyOnlineCover(game.id,bytes,image)
                onClose()
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { message=errorText(e) }
            finally { if(ticket==generation) busy=false }
        }
    }
    DisposableEffect(client,activity) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) { client.close();onClose() } }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer);client.close() }
    }
    LaunchedEffect(client,options.providers) { search() }
    val bitmap by produceState<Bitmap?>(null,preview,client) {
        value=null
        val image=preview ?: return@produceState
        delay(180)
        try { value=client.preview(image) } catch(e:CancellationException) { throw e } catch(_:Exception) { value=null }
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.85f)),contentAlignment=Alignment.Center) {
            Column(Modifier.width(850.dp).fillMaxHeight(.94f).background(Color(0xFF101012)).border(1.dp,Color(0xFF424244)).padding(20.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(tr(R.string.cover_find_online),color=Color(0xFFCCFF00),fontSize=23.sp,fontWeight=FontWeight.Bold)
                Text("${game.title} · ${game.platform}",color=Color.White,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    SettingRow(tr(R.string.cover_search_query),query,Modifier.weight(1f),onClick={
                        val field=EditText(activity).apply { setSingleLine(true);setText(query);setSelection(text.length) }
                        val dialog=AlertDialog.Builder(activity).setTitle(R.string.cover_search_query).setView(field)
                            .setPositiveButton(R.string.cover_search_again,null).setNegativeButton(R.string.cancel,null).create()
                        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val value=field.text.toString().trim().take(160)
                            if(value.isNotBlank()) { query=value;dialog.dismiss();search() }
                        } };dialog.show()
                    })
                    SettingRow(tr(R.string.close),modifier=Modifier.width(110.dp),onClick=onClose)
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    SettingRow(tr(R.string.cover_search_again),modifier=Modifier.weight(1f).focusRequester(retryFocus),onClick={ search(providerIndex, true) })
                    if(options.providers.size>1) SettingRow(tr(R.string.cover_next_source),modifier=Modifier.weight(1f),onClick={ search((providerIndex+1)%options.providers.size) })
                    if(options.apiKey.isEmpty() && CoverProvider.STEAMGRIDDB in options.providers)
                        SettingRow(tr(R.string.cover_api_key),modifier=Modifier.weight(1f),onClick={ editCoverApiKey(activity) })
                }
                Text(if(busy) tr(R.string.cover_searching) + " · " + options.providers.getOrNull(providerIndex)?.label.orEmpty() else options.providers.getOrNull(providerIndex)?.label.orEmpty(),color=Color(0xFFCCFF00),fontSize=12.sp)
                if(message.isNotEmpty()) Text(message,color=Color(0xFFADADB0),fontSize=12.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
                Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(titles,key={ _,it -> it.id }) { index,title ->
                            SettingRow(title.title,title.year,modifier=if(index==0) Modifier.focusRequester(first) else Modifier,onClick={ if(!busy) loadGrids(title) })
                        }
                        itemsIndexed(images,key={ _,it -> it.url }) { index,image ->
                            val info=listOf(if(image.width>0) "${image.width} × ${image.height}" else image.provider.label,image.author).filter { it.isNotBlank() }.joinToString(" · ")
                            SettingRow(image.title,info,modifier=(if(index==0) Modifier.focusRequester(first) else Modifier).onFocusChanged { if(it.hasFocus) preview=image },
                                onClick={ if(!busy) apply(image) })
                        }
                    }
                    Column(Modifier.width(300.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth().height(120.dp).background(Color(0xFF222226)),contentAlignment=Alignment.Center) {
                            bitmap?.let { Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit) }
                        }
                        Text(tr(if(images.isNotEmpty()) R.string.cover_preview_apply else R.string.cover_pick_title),color=Color.White,fontSize=12.sp,lineHeight=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        preview?.let { image ->
                            Text(listOf(image.provider.label,image.author).filter { it.isNotBlank() }.joinToString(" · "),color=Color(0xFFADADB0),fontSize=12.sp)
                        }
                        Text(tr(R.string.cover_online_hint),color=Color(0xFFADADB0),fontSize=11.sp,lineHeight=15.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
                    }
                }
                ControlHints(R.string.control_select,R.string.back)
            }
        }
        LaunchedEffect(resultVersion) {
            if(!busy) { withFrameNanos { };runCatching { if(titles.isNotEmpty() || images.isNotEmpty()) first.requestFocus() else retryFocus.requestFocus() } }
        }
    }
}

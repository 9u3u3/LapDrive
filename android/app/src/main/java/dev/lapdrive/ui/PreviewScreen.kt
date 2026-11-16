package dev.lapdrive.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import dev.lapdrive.AppLock
import dev.lapdrive.AppViewModel
import dev.lapdrive.Entry
import dev.lapdrive.Transfer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private enum class Kind { IMAGE, MEDIA, PDF, TEXT, OTHER }

private fun kindOf(e: Entry): Kind {
    val m = e.mime.orEmpty()
    val ext = e.name.substringAfterLast('.', "").lowercase()
    return when {
        m.startsWith("image/") && ext !in setOf("svg", "psd", "tif", "tiff") -> Kind.IMAGE
        m.startsWith("video/") || m.startsWith("audio/") -> Kind.MEDIA
        m == "application/pdf" -> Kind.PDF
        m.startsWith("text/") || ext in TEXT_EXT || m in setOf("application/json", "application/xml", "application/javascript") ||
            e.name.lowercase() in setOf("dockerfile", "makefile", "readme", "license") -> Kind.TEXT
        else -> Kind.OTHER
    }
}

/** Download into the private preview folder, then let the user pick an app to open it. */
fun openWith(ctx: Context, vm: AppViewModel, e: Entry) {
    val t = Transfer(e.name, upload = false)
    vm.transfers += t
    t.job = vm.viewModelScope.launch {
        try {
            val f = vm.fetchForPreview(e) { t.progress = it }
            launchViewer(ctx, f, e)
        } catch (ex: kotlinx.coroutines.CancellationException) {
            throw ex
        } catch (ex: Exception) {
            vm.message = "Cannot open: ${ex.message}"
        } finally {
            vm.transfers.remove(t)
        }
    }
}

private fun launchViewer(ctx: Context, f: File, e: Entry) {
    val uri = FileProvider.getUriForFile(ctx, "dev.lapdrive.files", f)
    val mime = e.mime ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension) ?: "*/*"
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        AppLock.expectExternal = true
        ctx.startActivity(Intent.createChooser(view, "Open ${e.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        AppLock.expectExternal = false
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(vm: AppViewModel, e: Entry) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    BackHandler { vm.preview = null }
    // Delete the decrypted temporary copy when the preview closes.
    DisposableEffect(e.path) { onDispose { vm.clearPreviewCache() } }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.preview = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { vm.download(e) }) { Icon(Icons.Filled.Download, "Download to phone") }
                    IconButton(onClick = { openWith(ctx, vm, e) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open with") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (kindOf(e)) {
                Kind.IMAGE -> ZoomableImage(vm, e)
                Kind.MEDIA -> MediaPlayer(vm, e)
                Kind.PDF -> PdfPreview(vm, e)
                Kind.TEXT -> TextPreview(vm, e)
                Kind.OTHER -> NoPreview(vm, e)
            }
            if (vm.transfers.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun ZoomableImage(vm: AppViewModel, e: Entry) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val api = vm.api ?: return
    AsyncImage(
        model = api.fileUrl(e.path),
        contentDescription = e.name,
        imageLoader = vm.imageLoader!!,
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun MediaPlayer(vm: AppViewModel, e: Entry) {
    val ctx = LocalContext.current
    val api = vm.api ?: return
    // ExoPlayer streams through the same pinned, authenticated HTTPS client.
    // It seeks with HTTP Range requests and keeps nothing on disk.
    val player = remember(e.path) {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(api.client)))
            .build().apply {
                setMediaItem(MediaItem.fromUri(api.fileUrl(e.path)))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
    )
}

@Composable
private fun TextPreview(vm: AppViewModel, e: Entry) {
    var text by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(e.path) {
        try {
            val bytes = vm.api!!.head(e.path, MAX_TEXT)
            text = String(bytes, Charsets.UTF_8) + if (e.size > MAX_TEXT) "\n\n… (preview shows the first 512 KB)" else ""
        } catch (ex: Exception) {
            err = ex.message ?: ex.toString()
        }
    }
    when {
        err != null -> Text(err!!, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
        text == null -> CircularProgressIndicator(Modifier.padding(48.dp))
        else -> SelectionContainer {
            Text(
                text!!,
                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()).padding(12.dp),
            )
        }
    }
}

private const val MAX_TEXT = 512 * 1024

@Composable
private fun PdfPreview(vm: AppViewModel, e: Entry) {
    var file by remember { mutableStateOf<File?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(e.path) {
        try { file = vm.fetchForPreview(e) { progress = it } } catch (ex: Exception) { err = ex.message ?: ex.toString() }
    }
    when {
        err != null -> Text(err!!, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
        file == null -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Loading ${(progress * 100).toInt()}%")
        }
        else -> PdfPages(file!!)
    }
}

@Composable
private fun PdfPages(file: File) {
    val renderer = remember(file) {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
    }
    if (renderer == null) {
        Text("Cannot open this PDF. It may be damaged or protected with a password.", Modifier.padding(24.dp))
        return
    }
    // PdfRenderer allows only one open page at a time.
    val lock = remember { Mutex() }
    DisposableEffect(renderer) {
        onDispose { CoroutineScope(Dispatchers.IO).launch { lock.withLock { renderer.close() } } }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Gray)) {
        val widthPx = constraints.maxWidth
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(renderer.pageCount) { i ->
                var bmp by remember { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(i) {
                    bmp = withContext(Dispatchers.IO) {
                        lock.withLock {
                            val page = renderer.openPage(i)
                            try {
                                val h = (page.height * widthPx.toFloat() / page.width).toInt().coerceAtLeast(1)
                                Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also {
                                    it.eraseColor(Color.WHITE)
                                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            } finally {
                                page.close()
                            }
                        }
                    }
                }
                val b = bmp
                if (b != null) Image(b.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxWidth())
                else Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(androidx.compose.ui.graphics.Color.White))
            }
        }
    }
}

@Composable
private fun NoPreview(vm: AppViewModel, e: Entry) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(iconFor(e), null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("The app cannot show this file type.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { openWith(ctx, vm, e) }) { Text("Open with another app") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.download(e) }) { Text("Download to phone") }
    }
}

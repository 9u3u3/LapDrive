package dev.lapdrive

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

enum class SortBy { NAME, DATE, SIZE }

class Transfer(val name: String, val upload: Boolean) {
    val id: String = UUID.randomUUID().toString()
    var progress by mutableFloatStateOf(-1f) // -1 = size unknown
    var detail by mutableStateOf("Waiting")
    var job: Job? = null

    private var lastTime = 0L
    private var lastBytes = 0L
    private var speed = 0.0

    /** Update progress, and the speed once each second. */
    fun update(ctx: Application, done: Long, total: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (total > 0) progress = done.toFloat() / total
        if (lastTime == 0L) { lastTime = now; lastBytes = done }
        val dt = now - lastTime
        if (dt >= 1000) {
            val inst = (done - lastBytes) * 1000.0 / dt
            speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
            lastTime = now; lastBytes = done
        }
        val f = { b: Long -> android.text.format.Formatter.formatShortFileSize(ctx, b) }
        detail = buildString {
            append(f(done))
            if (total > 0) append(" of ").append(f(total))
            if (speed > 0) append(" • ").append(f(speed.toLong().coerceAtLeast(0))).append("/s")
        }
    }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SecureStore(app)
    private val resolver = app.contentResolver

    var api by mutableStateOf<Api?>(null); private set
    var imageLoader by mutableStateOf<ImageLoader?>(null); private set
    var info by mutableStateOf<ServerInfo?>(null); private set

    var path by mutableStateOf(""); private set
    var items by mutableStateOf<List<Entry>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var sortBy by mutableStateOf(SortBy.NAME)

    var searchResults by mutableStateOf<List<Entry>?>(null); private set
    var preview by mutableStateOf<Entry?>(null)

    /** Files that another app shared to LapDrive. They wait for the user to pick a folder. */
    var pendingShare by mutableStateOf<List<Uri>>(emptyList())

    /** One-shot message for the snackbar. */
    var message by mutableStateOf<String?>(null)

    val transfers = mutableStateListOf<Transfer>()

    /** At most 2 files upload at the same time. Each file already uses parallel pieces. */
    private val uploadSlots = Semaphore(2)

    val previewDir: File get() = File(getApplication<Application>().cacheDir, "preview")

    init {
        clearPreviewCache()
        store.load()?.let { use(it) }
    }

    private fun use(conn: Connection) {
        val a = Api(conn)
        api = a
        imageLoader = ImageLoader.Builder(getApplication())
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { a.client })) }
            // Memory cache only. No thumbnails or images are written to the phone storage.
            .diskCache(null)
            .build()
        connect()
    }

    fun connect() {
        val a = api ?: return
        viewModelScope.launch {
            loading = true
            error = null
            try {
                info = a.connect()
                load(path)
            } catch (e: Exception) {
                handle(e)
            } finally {
                loading = false
            }
        }
    }

    suspend fun pair(linkText: String, deviceName: String) {
        val link = PairLink.parse(linkText)
        val conn = Api.pair(link, deviceName.ifBlank { Build.MODEL })
        store.save(conn)
        path = ""
        use(conn)
    }

    fun unpair() {
        transfers.forEach { it.job?.cancel() }
        transfers.clear()
        store.clear()
        clearPreviewCache()
        api = null; info = null; imageLoader = null
        items = emptyList(); path = ""; searchResults = null; preview = null
    }

    private fun handle(e: Exception) {
        when {
            e is ApiException && e.code == 401 -> {
                unpair()
                message = "The laptop revoked this phone. Pair again."
            }
            e is javax.net.ssl.SSLException || e.cause is java.security.cert.CertificateException ->
                error = "Security check failed: the laptop certificate does not match. " +
                    "If you reset the certificate on the laptop, pair again."
            e is IOException && e !is ApiException -> error = "Cannot reach the laptop. Check that the server runs and that both devices are on the same network or Tailscale."
            else -> message = e.message ?: e.toString()
        }
    }

    fun sorted(list: List<Entry>): List<Entry> {
        val c = when (sortBy) {
            SortBy.NAME -> compareBy<Entry> { it.name.lowercase() }
            SortBy.DATE -> compareByDescending { it.mtime }
            SortBy.SIZE -> compareByDescending { it.size }
        }
        return list.sortedWith(compareBy<Entry> { !it.isDir }.then(c))
    }

    fun load(newPath: String = path) {
        val a = api ?: return
        viewModelScope.launch {
            loading = true
            try {
                items = a.list(newPath)
                path = newPath
                error = null
            } catch (e: Exception) {
                handle(e)
            } finally {
                loading = false
            }
        }
    }

    fun open(e: Entry) {
        if (e.isDir) {
            searchResults = null
            load(e.path)
        } else preview = e
    }

    fun up(): Boolean {
        if (path.isEmpty()) return false
        load(path.substringBeforeLast('/', ""))
        return true
    }

    fun search(q: String) {
        val a = api ?: return
        if (q.trim().length < 2) { searchResults = null; return }
        viewModelScope.launch {
            loading = true
            try { searchResults = a.search(path, q) } catch (e: Exception) { handle(e) } finally { loading = false }
        }
    }

    fun closeSearch() { searchResults = null }

    fun mkdir(name: String) = op("Folder created") { it.mkdir(if (path.isEmpty()) name else "$path/$name") }
    fun rename(e: Entry, newName: String) = op("Renamed") { it.rename(e.path, newName) }
    fun delete(e: Entry) = op("Moved to the laptop trash") { it.delete(e.path) }

    private fun op(done: String, block: suspend (Api) -> Unit) {
        val a = api ?: return
        viewModelScope.launch {
            try {
                block(a)
                message = done
                searchResults = null
                load(path)
            } catch (e: Exception) { handle(e) }
        }
    }

    // ------------------------------------------------------------------ transfers

    fun upload(uris: List<Uri>) {
        val a = api ?: return
        val dir = path
        for (uri in uris) {
            val (name, size) = queryNameSize(uri)
            val t = Transfer(name, upload = true)
            transfers += t
            t.job = viewModelScope.launch {
                try {
                    val saved = uploadSlots.withPermit {
                        t.detail = "Starting"
                        a.upload(resolver, uri, dir, name, size) { done, total -> t.update(getApplication(), done, total) }
                    }
                    message = "Uploaded to $saved"
                    if (path == dir) load(dir)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    message = "Upload canceled"
                } catch (e: Exception) {
                    handle(e)
                } finally {
                    transfers.remove(t)
                }
            }
        }
    }

    private fun queryNameSize(uri: Uri): Pair<String, Long> {
        var name = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        // Keep only the last part. The server checks the name again.
        return name.substringAfterLast('/').ifBlank { "file" } to size
    }

    /** Save a laptop file to Downloads/LapDrive on the phone. */
    fun download(e: Entry) {
        val a = api ?: return
        val t = Transfer(e.name, upload = false)
        transfers += t
        t.job = viewModelScope.launch {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, e.name)
                put(MediaStore.Downloads.MIME_TYPE, e.mime ?: "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LapDrive")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            var uri: Uri? = null
            try {
                uri = withContext(Dispatchers.IO) { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }
                    ?: throw IOException("Cannot create the file in Downloads")
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri)!!.use { out ->
                        a.download(e.path, out) { done, total -> t.update(getApplication(), done, total) }
                    }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                }
                message = "Saved to Downloads/LapDrive"
            } catch (ex: Exception) {
                uri?.let { u -> withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { runCatching { resolver.delete(u, null, null) } } }
                if (ex is kotlinx.coroutines.CancellationException) message = "Download canceled" else handle(ex)
            } finally {
                transfers.remove(t)
            }
        }
    }

    /**
     * Download a file into the app-private preview folder (for PDF and "Open with").
     * Other apps cannot read this folder. The app deletes it when you close the preview.
     */
    suspend fun fetchForPreview(e: Entry, onProgress: (Float) -> Unit): File {
        val a = api ?: throw IOException("Not connected")
        val dir = File(previewDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val f = File(dir, e.name.replace('/', '_'))
        try {
            withContext(Dispatchers.IO) {
                f.outputStream().use { out -> a.download(e.path, out) { d, t -> if (t > 0) onProgress(d.toFloat() / t) } }
            }
        } catch (ex: Exception) {
            f.delete()
            if (ex is ApiException && ex.code == 401) handle(ex)
            throw ex
        }
        return f
    }

    fun clearPreviewCache() {
        previewDir.deleteRecursively()
    }

    override fun onCleared() {
        clearPreviewCache()
    }
}

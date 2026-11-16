package dev.lapdrive

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.io.OutputStream
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.coroutineContext

data class Entry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
    val mime: String?,
) {
    companion object {
        fun from(o: JSONObject) = Entry(
            name = o.getString("name"),
            path = o.getString("path"),
            isDir = o.getBoolean("is_dir"),
            size = o.optLong("size"),
            mtime = o.optLong("mtime"),
            mime = if (o.isNull("mime")) null else o.optString("mime"),
        )
    }
}

data class ServerInfo(
    val serverName: String,
    val rootName: String,
    val readOnly: Boolean,
    val diskFree: Long,
    val diskTotal: Long,
)

private const val UPLOAD_PARALLEL = 3
private const val MAX_RETRIES = 12

class ApiException(val code: Int, message: String) : IOException(message)

/** Parsed `lapdrive://pair?...` link from the QR code. */
data class PairLink(val hosts: List<String>, val port: Int, val fingerprint: String, val code: String) {
    companion object {
        fun parse(text: String): PairLink {
            val uri = Uri.parse(text.trim())
            require(uri.scheme == "lapdrive" && uri.host == "pair") { "This is not a LapDrive pairing link" }
            val hosts = uri.getQueryParameter("h").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val port = uri.getQueryParameter("p")?.toIntOrNull() ?: 0
            val fp = uri.getQueryParameter("fp").orEmpty().lowercase()
            val code = uri.getQueryParameter("c").orEmpty()
            require(hosts.isNotEmpty()) { "The link has no laptop address" }
            require(port in 1..65535) { "The link has a bad port" }
            require(fp.matches(Regex("[0-9a-f]{64}"))) { "The link has a bad certificate fingerprint" }
            require(code.isNotEmpty()) { "The link has no pairing code" }
            return PairLink(hosts, port, fp, code)
        }
    }
}

/**
 * Trusts exactly one certificate: the one with the pinned SHA-256 fingerprint.
 * Certificate authorities do not matter here. A server with any other
 * certificate fails the TLS handshake, so a person on the same Wi-Fi cannot
 * read or change the traffic.
 */
private class PinnedTrustManager(fingerprintHex: String) : X509TrustManager {
    private val expected = fingerprintHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun matches(cert: java.security.cert.Certificate): Boolean =
        MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(cert.encoded), expected)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("No server certificate")
        if (!matches(leaf)) throw CertificateException("Server certificate does not match the paired laptop")
        leaf.checkValidity()
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Not used")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun pinnedClient(fingerprint: String, token: String?): OkHttpClient {
    val tm = PinnedTrustManager(fingerprint)
    val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
    return OkHttpClient.Builder()
        .sslSocketFactory(ssl.socketFactory, tm)
        // The server uses a self-signed certificate for an IP address. The pin
        // already proves its identity, so the host name check uses the pin too.
        .hostnameVerifier { _, session -> session.peerCertificates.firstOrNull()?.let(tm::matches) == true }
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .apply {
            if (token != null) addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
            }
        }
        .build()
}

private fun Response.checked(): Response {
    if (isSuccessful) return this
    val detail = runCatching { JSONObject(body.string()).optString("detail") }.getOrNull()
    close()
    throw ApiException(code, detail?.takeIf { it.isNotBlank() } ?: "HTTP $code")
}

class Api(val conn: Connection) {
    val client: OkHttpClient = pinnedClient(conn.fingerprint, conn.token)

    @Volatile
    private var host: String = conn.hosts.first()

    /** Try each laptop address (for example Wi-Fi first, then Tailscale) and keep the first that answers. */
    suspend fun connect(): ServerInfo = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (h in listOf(host) + conn.hosts.filter { it != host }) {
            try {
                host = h
                return@withContext info()
            } catch (e: ApiException) {
                throw e // the laptop answered, so the address is good
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("Laptop not reachable")
    }

    fun url(path: String, vararg params: Pair<String, String>): HttpUrl {
        val b = HttpUrl.Builder().scheme("https").host(host).port(conn.port).encodedPath(path)
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    fun fileUrl(path: String) = url("/api/file", "path" to path).toString()
    fun thumbUrl(path: String) = url("/api/thumb", "path" to path).toString()

    private fun getJson(url: HttpUrl): JSONObject =
        client.newCall(Request.Builder().url(url).build()).execute().checked().use { JSONObject(it.body.string()) }

    private fun postJson(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url(url(path))
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(req).execute().checked().use { JSONObject(it.body.string()) }
    }

    fun info(): ServerInfo {
        val o = getJson(url("/api/info"))
        return ServerInfo(
            o.getString("server_name"), o.getString("root_name"), o.getBoolean("read_only"),
            o.optLong("disk_free"), o.optLong("disk_total"),
        )
    }

    suspend fun list(path: String): List<Entry> = withContext(Dispatchers.IO) {
        val arr = getJson(url("/api/list", "path" to path)).getJSONArray("items")
        List(arr.length()) { Entry.from(arr.getJSONObject(it)) }
    }

    suspend fun search(path: String, q: String): List<Entry> = withContext(Dispatchers.IO) {
        val arr = getJson(url("/api/search", "path" to path, "q" to q)).getJSONArray("items")
        List(arr.length()) { Entry.from(arr.getJSONObject(it)) }
    }

    suspend fun mkdir(path: String) = withContext(Dispatchers.IO) { postJson("/api/mkdir", JSONObject().put("path", path)) }
    suspend fun rename(path: String, newName: String) = withContext(Dispatchers.IO) {
        postJson("/api/rename", JSONObject().put("path", path).put("new_name", newName))
    }
    suspend fun delete(path: String) = withContext(Dispatchers.IO) { postJson("/api/delete", JSONObject().put("path", path)) }

    /** Read the first [maxBytes] of a file (for a text preview). */
    suspend fun head(path: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(fileUrl(path)).header("Range", "bytes=0-${maxBytes - 1}").build()
        client.newCall(req).execute().checked().use { r ->
            r.body.source().use { src ->
                val buf = okio.Buffer()
                while (buf.size < maxBytes && src.read(buf, maxBytes - buf.size) != -1L) Unit
                buf.readByteArray(minOf(buf.size, maxBytes.toLong()))
            }
        }
    }

    suspend fun download(path: String, out: OutputStream, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/api/file", "path" to path, "download" to "1")).build()
        client.newCall(req).execute().checked().use { r ->
            val total = r.body.contentLength()
            val buf = ByteArray(256 * 1024)
            var done = 0L
            r.body.byteStream().use { input ->
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
        }
    }

    /**
     * Upload a file. When the app can read the file at any position, it sends the
     * file in pieces over [UPLOAD_PARALLEL] connections and sends a failed piece
     * again. Otherwise it falls back to one request.
     */
    suspend fun upload(
        resolver: ContentResolver, uri: Uri, dir: String, name: String, size: Long,
        onProgress: (Long, Long) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val pfd = if (size > 0) runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull() else null
        val channel = pfd?.let { runCatching { FileInputStream(it.fileDescriptor).channel.also { c -> c.size() } }.getOrNull() }
        if (pfd == null || channel == null) {
            pfd?.close()
            return@withContext uploadSingle(resolver, uri, dir, name, size, onProgress)
        }
        pfd.use { uploadPieces(channel, dir, name, size, onProgress) }
    }

    private suspend fun uploadPieces(
        channel: FileChannel, dir: String, name: String, size: Long,
        onProgress: (Long, Long) -> Unit,
    ): String = coroutineScope {
        val start = postJson("/api/uploads", JSONObject().put("dir", dir).put("name", name).put("size", size))
        val id = start.getString("id")
        val chunkSize = start.getInt("chunk_size")
        val chunks = start.getInt("chunks")
        val sent = AtomicLong(0)
        val next = AtomicInteger(0)
        try {
            (0 until UPLOAD_PARALLEL).map {
                launch(Dispatchers.IO) {
                    val buf = ByteArray(chunkSize)
                    while (true) {
                        val i = next.getAndIncrement()
                        if (i >= chunks) break
                        val offset = i.toLong() * chunkSize
                        val len = minOf(chunkSize.toLong(), size - offset).toInt()
                        readFully(channel, buf, len, offset)
                        sendPiece(id, i, buf, len, sent) { onProgress(it, size) }
                    }
                }
            }.joinAll()
            val req = Request.Builder().url(url("/api/uploads/$id/finish")).post(ByteArray(0).toRequestBody()).build()
            retrying { pieceClient.newCall(req).execute().checked().use { JSONObject(it.body.string()).getString("saved_as") } }
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                runCatching { pieceClient.newCall(Request.Builder().url(url("/api/uploads/$id")).delete().build()).execute().close() }
            }
            throw e
        }
    }

    private fun readFully(channel: FileChannel, buf: ByteArray, len: Int, offset: Long) {
        val bb = ByteBuffer.wrap(buf, 0, len)
        var pos = offset
        while (bb.hasRemaining()) {
            val n = channel.read(bb, pos)
            if (n < 0) throw IOException("The file changed while it was uploading")
            pos += n
        }
    }

    private suspend fun sendPiece(id: String, index: Int, buf: ByteArray, len: Int, sent: AtomicLong, report: (Long) -> Unit) {
        val ctx = coroutineContext
        retrying {
            var mine = 0L
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = len.toLong()
                override fun writeTo(sink: BufferedSink) {
                    var off = 0
                    while (off < len) {
                        ctx.ensureActive()
                        val n = minOf(64 * 1024, len - off)
                        sink.write(buf, off, n)
                        off += n
                        mine += n
                        report(sent.addAndGet(n.toLong()))
                    }
                }
            }
            try {
                val req = Request.Builder().url(url("/api/uploads/$id/$index")).put(body).build()
                pieceClient.newCall(req).execute().checked().close()
            } catch (e: Throwable) {
                report(sent.addAndGet(-mine)) // this piece starts again from zero
                throw e
            }
        }
    }

    /** Run [block] again after a network error or a server error, with a growing wait. */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            try {
                return block()
            } catch (e: ApiException) {
                if (e.code < 500 && e.code != 408 && e.code != 429) throw e
                if (++attempt > MAX_RETRIES) throw e
            } catch (e: IOException) {
                if (++attempt > MAX_RETRIES) throw e
            }
            delay(minOf(30_000L, 1_000L shl minOf(attempt, 5)))
        }
    }

    /** Short timeouts, so that a stalled mobile connection fails fast and the piece is sent again. */
    private val pieceClient: OkHttpClient by lazy {
        client.newBuilder().writeTimeout(25, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    }

    private suspend fun uploadSingle(
        resolver: ContentResolver, uri: Uri, dir: String, name: String, size: Long,
        onProgress: (Long, Long) -> Unit,
    ): String {
        val ctx = coroutineContext
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(uri) ?: throw IOException("Cannot read the file")
                input.use {
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        ctx.ensureActive()
                        val n = it.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        done += n
                        onProgress(done, size)
                    }
                }
            }
        }
        val req = Request.Builder().url(url("/api/upload", "dir" to dir, "name" to name)).put(body).build()
        return client.newCall(req).execute().checked().use { JSONObject(it.body.string()).getString("saved_as") }
    }

    companion object {
        /** Pair with a laptop. The QR code gives the pinned fingerprint, so this first call is protected too. */
        suspend fun pair(link: PairLink, deviceName: String): Connection = withContext(Dispatchers.IO) {
            val client = pinnedClient(link.fingerprint, null)
            val body = JSONObject().put("code", link.code).put("device_name", deviceName).toString()
                .toRequestBody("application/json".toMediaType())
            var last: Exception? = null
            for (h in link.hosts) {
                val url = HttpUrl.Builder().scheme("https").host(h).port(link.port).encodedPath("/api/pair").build()
                try {
                    val o = client.newCall(Request.Builder().url(url).post(body).build()).execute().checked()
                        .use { JSONObject(it.body.string()) }
                    val hosts = listOf(h) + link.hosts.filter { it != h }
                    return@withContext Connection(hosts, link.port, link.fingerprint, o.getString("token"), o.getString("server_name"))
                } catch (e: ApiException) {
                    throw e
                } catch (e: IOException) {
                    last = e
                }
            }
            throw last ?: IOException("Laptop not reachable")
        }
    }
}

package com.shiga.shigaflow

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.os.IBinder
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.InboxStyle
import android.content.pm.PackageManager
import android.Manifest
import android.content.pm.ServiceInfo
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.StateUpdateAlert
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

private const val ACTION_EXIT_SHIGAFLOW = "com.shiga.shigaflow.action.EXIT"

private val Ink = Color(0xFF151C24)
private val Mint = Color(0xFF168BFF)
private val Canvas = Color(0xFF080B10)
private val TextPrimary = Color(0xFFEAF1F8)
private val TextSecondary = Color(0xFF9AA8B8)
private val Track = Color(0xFF303B49)

internal data class TorrentUiState(
    val hash: String,
    val title: String,
    val progress: Float,
    val rate: String,
    val peers: Int,
    val state: String,
    val paused: Boolean = false
)

class MainActivity : ComponentActivity() {
    private val session = SessionManager()
    private lateinit var downloadDir: File
    private val mainHandler = Handler(Looper.getMainLooper())
    private val snapshots = ConcurrentHashMap<String, TorrentUiState>()
    private val pausedHashes = ConcurrentHashMap.newKeySet<String>()
    private val deletedHashes = ConcurrentHashMap.newKeySet<String>()
    private val publishedHashes = ConcurrentHashMap.newKeySet<String>()
    private val publishedByHash = ConcurrentHashMap<String, MutableList<Uri>>()
    private var uiTorrents by mutableStateOf<List<TorrentUiState>>(emptyList())
    private var engineStats by mutableStateOf("Starting torrent engine…")
    private var incomingMagnet by mutableStateOf<String?>(null)
    private val prefs by lazy { getSharedPreferences("shigaflow_settings", MODE_PRIVATE) }
    private val torrentRecords = ConcurrentHashMap<String, JSONObject>()
    private val recordsPersistLock = Any()
    private var downloadTreeUri by mutableStateOf<String?>(null)
    private var downloadLimitKbps by mutableIntStateOf(0)
    private var uploadLimitKbps by mutableIntStateOf(0)
    private var isDarkTheme by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == ACTION_EXIT_SHIGAFLOW) {
            stopService(Intent(this, ShigaflowService::class.java))
            finishAndRemoveTask()
            return
        }
        captureIncomingMagnet(intent)
        downloadDir = File(filesDir, "torrent-work").apply { mkdirs() }
        downloadTreeUri = prefs.getString("download_tree_uri", null)
        downloadLimitKbps = prefs.getInt("download_limit_kbps", 0)
        uploadLimitKbps = prefs.getInt("upload_limit_kbps", 0)
        isDarkTheme = prefs.getBoolean("dark_theme", true)
        runCatching { session.start() }
        runCatching { session.downloadRateLimit(if (downloadLimitKbps == 0) 0 else downloadLimitKbps * 1024) }
        runCatching { session.uploadRateLimit(if (uploadLimitKbps == 0) 0 else uploadLimitKbps * 1024) }
        ContextCompat.startForegroundService(this, ShigaflowService.intent(this))
        requestNotificationPermissionIfNeeded()
        loadTorrentRecords()
        session.addListener(object : AlertListener {
            override fun types(): IntArray = intArrayOf(AlertType.STATE_UPDATE.swig())

            override fun alert(alert: Alert<*>) {
                if (alert !is StateUpdateAlert) return
                for (status in alert.status()) {
                    val hash = runCatching { status.getInfoHashes().getBest().toHex().lowercase(Locale.ROOT) }
                        .getOrElse { status.name().orEmpty() }
                    if (deletedHashes.contains(hash)) {
                        continue
                    }
                    val metadataName = runCatching {
                        if (hash.length == 40) session.find(Sha1Hash.parseHex(hash))?.torrentFile()?.name() else null
                    }.getOrNull()?.trim().orEmpty()
                    val rawName = status.name().orEmpty().trim()
                    val bestName = metadataName.ifBlank { rawName }
                    val title = if (bestName.isBlank() || bestName.equals(hash, ignoreCase = true)) {
                        "Fetching torrent metadata…"
                    } else bestName
                    val percent = status.progress().coerceIn(0f, 1f)
                    val state = status.state().name.lowercase(Locale.ROOT).replace('_', ' ')
                    val isPaused = pausedHashes.contains(hash)
                    val priorRecord = torrentRecords[hash]
                    val savedPaused = pausedHashes.contains(hash) || (priorRecord?.optBoolean("paused", false) == true)
                    if (savedPaused) pausedHashes.add(hash)
                    if (priorRecord != null && priorRecord.optBoolean("completed", false) && percent < 1f) { /* libtorrent will verify existing data as metadata becomes available */ }
                    snapshots[hash] = TorrentUiState(
                        hash = hash,
                        title = title,
                        progress = percent,
                        rate = formatRate(status.downloadPayloadRate().toLong()),
                        peers = status.numPeers(),
                        state = if (savedPaused) "paused" else if (priorRecord?.optBoolean("completed", false) == true && percent >= 1f) "finished" else state,
                        paused = savedPaused
                    )
                    if (priorRecord != null) { priorRecord.put("title", title); if (percent >= 1f) priorRecord.put("completed", true); persistTorrentRecords() }
                    if (percent >= 1f && hash.length == 40 && publishedHashes.add(hash)) {
                        runCatching { exportCompletedTorrent(hash, title) }
                    }
                }
                val list = snapshots.values.sortedWith(compareBy<TorrentUiState> { it.title.lowercase(Locale.ROOT) })
                mainHandler.post {
                    uiTorrents = list
                    engineStats = if (list.isEmpty()) "Waiting for torrents…" else "${list.size} torrent(s) in the session"
                    ShigaflowService.updateNotification(this@MainActivity, list)
                }
            }
        })

        setContent {
            val appColorScheme = if (isDarkTheme) {
                darkColorScheme(primary = Mint, onPrimary = Color.White, background = Canvas, surface = Ink, surfaceVariant = Color(0xFF202A36), onSurface = TextPrimary, onBackground = TextPrimary)
            } else {
                lightColorScheme(primary = Color(0xFF1269C7), onPrimary = Color.White, background = Color(0xFFF4F7FB), surface = Color.White, surfaceVariant = Color(0xFFE8EEF5), onSurface = Color(0xFF17212B), onBackground = Color(0xFF17212B))
            }
            MaterialTheme(colorScheme = appColorScheme) {
                TorrentHome(
                    torrents = uiTorrents,
                    engineStats = engineStats,
                    savePath = "Downloads",
                    onAddMagnet = { addMagnet(it) },
                    onAddTorrent = { addTorrentFile(it) },
                    onToggleTorrent = { hash, pause -> toggleTorrent(hash, pause) },
                    onDeleteTorrent = { hash, deleteFiles -> deleteTorrent(hash, deleteFiles) },
                    incomingMagnet = incomingMagnet,
                    onIncomingMagnetHandled = { incomingMagnet = null },
                    downloadLocationLabel = if (downloadTreeUri == null) "Downloads" else "Custom folder selected",
                    downloadLimitKbps = downloadLimitKbps,
                    uploadLimitKbps = uploadLimitKbps,
                    onPickDownloadLocation = { uri ->
                        if (uri != null) {
                            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                            downloadTreeUri = uri.toString()
                            prefs.edit().putString("download_tree_uri", uri.toString()).apply()
                        }
                    },
                    onSaveSpeedLimits = { downloadKbps, uploadKbps ->
                        downloadLimitKbps = downloadKbps.coerceIn(0, 2_000_000)
                        uploadLimitKbps = uploadKbps.coerceIn(0, 2_000_000)
                        prefs.edit().putInt("download_limit_kbps", downloadLimitKbps).putInt("upload_limit_kbps", uploadLimitKbps).apply()
                        runCatching { session.downloadRateLimit(if (downloadLimitKbps == 0) 0 else downloadLimitKbps * 1024) }
                        runCatching { session.uploadRateLimit(if (uploadLimitKbps == 0) 0 else uploadLimitKbps * 1024) }
                    },
                    isDarkTheme = isDarkTheme,
                    onThemeChange = { dark ->
                        isDarkTheme = dark
                        prefs.edit().putBoolean("dark_theme", dark).apply()
                    },
                    onResetDownloadLocation = {
                        downloadTreeUri?.let { runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
                        downloadTreeUri = null
                        prefs.edit().remove("download_tree_uri").apply()
                    },
                    onExit = { exitShigaflow() }
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 501)
        }
    }

    private fun exitShigaflow() {
        Thread {
            runCatching { session.stop() }
            runOnUiThread {
                stopService(Intent(this, ShigaflowService::class.java))
                finishAndRemoveTask()
            }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_EXIT_SHIGAFLOW) {
            exitShigaflow()
            return
        }
        captureIncomingMagnet(intent)
    }

    private fun captureIncomingMagnet(intent: Intent?) {
        if (intent == null) return
        val candidate = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> intent.dataString
        }?.trim().orEmpty()
        val magnet = Regex("magnet:\\?[^\\s]+", RegexOption.IGNORE_CASE)
            .find(candidate)?.value?.trimEnd(')', ']', '}', '>', '"', ',')
        if (!magnet.isNullOrBlank()) incomingMagnet = magnet
    }

    private fun addMagnet(magnet: String): String {
        val cleaned = magnet.trim()
        if (!cleaned.startsWith("magnet:?", ignoreCase = true)) return "Please enter a valid magnet link starting with magnet:?"
        return runCatching {
            val hash = extractMagnetInfoHash(cleaned)
                ?: throw IllegalArgumentException("The magnet link does not contain a valid BitTorrent info hash.")
            deletedHashes.remove(hash)
            val record = torrentRecords[hash] ?: JSONObject().put("hash", hash).put("magnet", cleaned).put("paused", false).put("completed", false).put("title", "Fetching torrent metadata…")
            record.put("magnet", cleaned)
            torrentRecords[hash] = record
            persistTorrentRecords()
            session.download(cleaned, downloadDir, TorrentFlags.UPDATE_SUBSCRIBE)
            "Torrent added. Its title will update when metadata arrives."
        }.getOrElse { "Could not add magnet: ${it.message ?: "unknown error"}" }
    }

    private fun addTorrentFile(uri: Uri): String {
        return runCatching {
            val copied = File(cacheDir, "${UUID.randomUUID()}.torrent")
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open selected file" }
                FileOutputStream(copied).use { output -> input.copyTo(output) }
            }
            val info = TorrentInfo(copied)
            val hash = info.infoHash().toHex().lowercase(Locale.ROOT)
            deletedHashes.remove(hash)
            val savedTorrent = File(filesDir, "shigaflow_${hash}.torrent")
            copied.copyTo(savedTorrent, overwrite = true)
            torrentRecords[hash] = JSONObject().put("hash", hash).put("torrentFile", savedTorrent.absolutePath).put("paused", false).put("completed", false).put("title", info.name())
            persistTorrentRecords()
            session.download(info, downloadDir)
            "Torrent added: ${info.name()}"
        }.getOrElse { "Could not add torrent file: ${it.message ?: "unknown error"}" }
    }

    private fun toggleTorrent(hash: String, pause: Boolean) {
        runCatching {
            val parsedHash = runCatching { Sha1Hash.parseHex(hash) }.getOrNull()
                ?: throw IllegalStateException("This torrent hash format is not supported by the per-torrent control yet.")
            val handle = session.find(parsedHash)
                ?: throw IllegalStateException("Torrent handle not available yet; try again in a moment.")
            // Disable automatic queue management so libtorrent cannot auto-resume a manually paused torrent.
            handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
            if (pause) {
                handle.pause()
                pausedHashes.add(hash)
            } else {
                handle.resume()
                pausedHashes.remove(hash)
            }
            torrentRecords[hash]?.put("paused", pause)
            persistTorrentRecords()
            snapshots[hash]?.let { old ->
                snapshots[hash] = old.copy(paused = pause, state = if (pause) "paused" else "resuming")
            }
            uiTorrents = snapshots.values.sortedBy { it.title.lowercase(Locale.ROOT) }
            ShigaflowService.updateNotification(this, uiTorrents)
            engineStats = if (pause) "Paused selected torrent" else "Resumed selected torrent"
        }.onFailure {
            engineStats = "Could not change torrent state: ${it.message ?: "unknown error"}"
        }
    }

    private fun exportCompletedTorrent(hash: String, title: String) {
        // Keep libtorrent's working files in app storage; publish completed files to the public public Downloads folder.
        val handle = session.find(Sha1Hash.parseHex(hash))
            ?: throw IllegalStateException("Torrent handle unavailable while exporting completed files")
        val info = handle.torrentFile()
            ?: throw IllegalStateException("Torrent metadata unavailable while exporting completed files")
        val storage = info.files()
        val filesToExport = (0 until storage.numFiles()).mapNotNull { index ->
            val relativePath = storage.filePath(index).replace('\\', '/')
            val candidate = File(downloadDir, relativePath)
            candidate.takeIf { it.isFile && it.length() > 0L }
        }.distinctBy { it.absolutePath }
        if (filesToExport.isEmpty()) throw IllegalStateException("No completed files found for $title")
        val uris = mutableListOf<Uri>()
        for (file in filesToExport) {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT)) ?: "application/octet-stream"
            val chosenTree = downloadTreeUri?.let(Uri::parse)
            val uri = if (chosenTree != null) {
                DocumentsContract.createDocument(contentResolver, DocumentsContract.buildDocumentUriUsingTree(chosenTree, DocumentsContract.getTreeDocumentId(chosenTree)), mime, file.name)
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } ?: continue
            try {
                contentResolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { input -> input.copyTo(output) } }
                    ?: throw IllegalStateException("Could not open destination for ${file.name}")
                if (chosenTree == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    contentResolver.update(uri, done, null, null)
                }
                uris.add(uri)
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        }
        publishedByHash[hash] = uris
        torrentRecords[hash]?.put("exportedUris", JSONArray().apply { uris.forEach { put(it.toString()) } })
        torrentRecords[hash]?.put("completed", true)
        torrentRecords[hash]?.put("workingDataCleaned", true)
        persistTorrentRecords()
        // The exported copy is the user's durable file. Remove the private working copy so
        // completed downloads do not occupy storage twice. Completed entries are restored from
        // their saved record on next launch rather than re-downloaded into private storage.
        runCatching { session.remove(handle) }
        filesToExport.forEach { runCatching { it.delete() } }
        runCatching {
            downloadDir.walkBottomUp().filter { it.isDirectory && it != downloadDir && it.listFiles()?.isEmpty() == true }.forEach { it.delete() }
        }
        snapshots[hash] = TorrentUiState(hash, title, 1f, "0 B/s", 0, "finished", false)
        mainHandler.post {
            uiTorrents = snapshots.values.sortedBy { it.title.lowercase(Locale.ROOT) }
            engineStats = "Completed torrent saved to Downloads; temporary data cleaned"
        }
    }

    private fun deleteTorrent(hash: String, deleteFiles: Boolean) {
        runCatching {
            val parsed = runCatching { Sha1Hash.parseHex(hash) }.getOrNull()
            val handle = parsed?.let { session.find(it) }
            val info = handle?.torrentFile()
            val recordBeforeRemoval = torrentRecords[hash]
            val downloadRoot = downloadDir.canonicalFile
            val filesToDelete = if (deleteFiles && info != null) {
                val storage = info.files()
                (0 until storage.numFiles()).mapNotNull { index ->
                    val candidate = File(downloadDir, storage.filePath(index).replace('\\', '/')).canonicalFile
                    candidate.takeIf { it.path.startsWith(downloadRoot.path + File.separator) }
                }
            } else emptyList()
            if (handle != null) {
                runCatching { handle.pause() }
                session.remove(handle)
            }
            deletedHashes.add(hash.lowercase(Locale.ROOT))
            pausedHashes.remove(hash)
            snapshots.remove(hash)
            publishedHashes.remove(hash)
            val record = torrentRecords.remove(hash)
            persistTorrentRecords(forceDiskWrite = true)
            record?.optString("torrentFile")?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
            if (deleteFiles) {
                filesToDelete.forEach { it.delete() }
                downloadDir.walkBottomUp().filter { it.isDirectory && it != downloadDir && it.listFiles()?.isEmpty() == true }.forEach { it.delete() }
                val exportedUris = mutableSetOf<Uri>()
                publishedByHash.remove(hash)?.let { exportedUris.addAll(it) }
                recordBeforeRemoval?.optJSONArray("exportedUris")?.let { array ->
                    for (i in 0 until array.length()) runCatching { Uri.parse(array.getString(i)) }.getOrNull()?.let { exportedUris.add(it) }
                }
                exportedUris.forEach { uri -> runCatching { contentResolver.delete(uri, null, null) } }
            } else {
                publishedByHash.remove(hash)
            }
            uiTorrents = snapshots.values.sortedBy { it.title.lowercase(Locale.ROOT) }
            ShigaflowService.updateNotification(this, uiTorrents)
            engineStats = if (deleteFiles) "Torrent removed and its downloaded files were deleted" else "Torrent removed from the list; downloaded files were kept"
        }.onFailure { engineStats = "Could not delete torrent: ${it.message ?: "unknown error"}" }
    }

    private fun persistTorrentRecords(forceDiskWrite: Boolean = false) {
        synchronized(recordsPersistLock) {
            val array = JSONArray()
            torrentRecords.values.forEach { array.put(it) }

            val editor = prefs.edit().putString("saved_torrents", array.toString())
            if (forceDiskWrite) {
                // A deletion is committed before the user can immediately close the app.
                editor.commit()
            } else {
                editor.apply()
            }
        }
    }

    private fun loadTorrentRecords() {
        val array = runCatching { JSONArray(prefs.getString("saved_torrents", "[]")) }.getOrNull() ?: return
        val restoredRecords = linkedMapOf<String, JSONObject>()

        for (i in 0 until array.length()) {
            val record = runCatching { array.getJSONObject(i) }.getOrNull() ?: continue
            var hash = record.optString("hash").lowercase(Locale.ROOT)
            val magnet = record.optString("magnet")

            // Older builds stored a random UUID for base32 magnet hashes. Normalize
            // those records to the real info hash so Delete can remove the saved entry.
            val magnetHash = extractMagnetInfoHash(magnet)
            if (!magnetHash.isNullOrBlank()) {
                hash = magnetHash
                record.put("hash", hash)
            }

            if (hash.isBlank()) continue
            restoredRecords[hash] = record
        }

        torrentRecords.clear()
        torrentRecords.putAll(restoredRecords)
        persistTorrentRecords(forceDiskWrite = true)

        for ((hash, record) in torrentRecords) {
            if (record.optBoolean("paused", false)) pausedHashes.add(hash)

            val exported = record.optJSONArray("exportedUris")
            if (exported != null) {
                val uris = mutableListOf<Uri>()
                for (j in 0 until exported.length()) {
                    runCatching { Uri.parse(exported.getString(j)) }.getOrNull()?.let { uris.add(it) }
                }
                if (uris.isNotEmpty()) publishedByHash[hash] = uris
            }
        }

        for ((hash, record) in torrentRecords) {
            if (record.optBoolean("completed", false) && record.optBoolean("workingDataCleaned", false)) {
                val title = record.optString("title").ifBlank { "Completed download" }
                snapshots[hash] = TorrentUiState(hash, title, 1f, "0 B/s", 0, "finished", false)
                publishedHashes.add(hash)
                continue
            }

            runCatching {
                val torrentPath = record.optString("torrentFile")
                if (torrentPath.isNotBlank() && File(torrentPath).isFile) {
                    session.download(TorrentInfo(File(torrentPath)), downloadDir)
                } else {
                    val magnet = record.optString("magnet")
                    if (magnet.startsWith("magnet:?", true)) {
                        session.download(magnet, downloadDir, TorrentFlags.UPDATE_SUBSCRIBE)
                    }
                }

                if (record.optBoolean("paused", false)) {
                    val handle = runCatching { session.find(Sha1Hash.parseHex(hash)) }.getOrNull()
                    handle?.unsetFlags(TorrentFlags.AUTO_MANAGED)
                    handle?.pause()
                }
            }
        }
    }

    private fun extractMagnetInfoHash(magnet: String): String? {
        val encodedHash = Regex("(?i)xt=urn:btih:([a-f0-9]{40}|[a-z2-7]{32})")
            .find(magnet)
            ?.groupValues
            ?.get(1)
            ?: return null

        if (encodedHash.length == 40) {
            return encodedHash.lowercase(Locale.ROOT)
        }

        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val output = StringBuilder()
        var buffer = 0
        var bitsInBuffer = 0

        for (character in encodedHash.uppercase(Locale.ROOT)) {
            val value = alphabet.indexOf(character)
            if (value < 0) return null

            buffer = (buffer shl 5) or value
            bitsInBuffer += 5

            if (bitsInBuffer >= 8) {
                bitsInBuffer -= 8
                output.append("%02x".format(Locale.ROOT, (buffer shr bitsInBuffer) and 0xff))
            }
        }

        return output.toString().takeIf { it.length == 40 }
    }

    private fun listSavedFiles(): List<File> = runCatching {
        downloadDir.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".parts", ignoreCase = true) && !it.name.startsWith(".") }
            .sortedByDescending { it.lastModified() }
            .take(200)
            .toList()
    }.getOrDefault(emptyList())

    private fun openSavedFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val extension = file.extension.lowercase(Locale.ROOT)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            engineStats = "No app found to open ${file.name}. The file is saved at ${file.absolutePath}"
        } catch (e: Exception) {
            engineStats = "Could not open file: ${e.message ?: file.absolutePath}"
        }
    }

    private fun formatRate(bytesPerSecond: Long): String {
        if (bytesPerSecond < 1024) return "$bytesPerSecond B/s"
        if (bytesPerSecond < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB/s", bytesPerSecond / 1024.0)
        return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TorrentHome(
    torrents: List<TorrentUiState>,
    engineStats: String,
    savePath: String,
    onAddMagnet: (String) -> String,
    onAddTorrent: (Uri) -> String,
    onToggleTorrent: (String, Boolean) -> Unit,
    onDeleteTorrent: (String, Boolean) -> Unit,
    incomingMagnet: String?,
    onIncomingMagnetHandled: () -> Unit,
    downloadLocationLabel: String,
    downloadLimitKbps: Int,
    uploadLimitKbps: Int,
    onPickDownloadLocation: (Uri?) -> Unit,
    onSaveSpeedLimits: (Int, Int) -> Unit,
    isDarkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
    onResetDownloadLocation: () -> Unit,
    onExit: () -> Unit
) {
    val Ink = if (isDarkTheme) Color(0xFF151C24) else Color.White
    val Canvas = if (isDarkTheme) Color(0xFF080B10) else Color(0xFFF4F7FB)
    val TextPrimary = if (isDarkTheme) Color(0xFFEAF1F8) else Color(0xFF17212B)
    val TextSecondary = if (isDarkTheme) Color(0xFF9AA8B8) else Color(0xFF596A7B)
    val Track = if (isDarkTheme) Color(0xFF303B49) else Color(0xFFD7E0EA)
    val Mint = if (isDarkTheme) Color(0xFF168BFF) else Color(0xFF1269C7)

    var magnet by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Ready for a magnet link or .torrent file.") }
    var showAddDownload by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var selectedHashes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<List<TorrentUiState>>(emptyList()) }
    var deleteDownloadedFiles by remember { mutableStateOf(false) }
    var speedLimitText by remember(downloadLimitKbps) { mutableStateOf(if (downloadLimitKbps == 0) "0" else downloadLimitKbps.toString()) }
    var uploadLimitText by remember(uploadLimitKbps) { mutableStateOf(if (uploadLimitKbps == 0) "0" else uploadLimitKbps.toString()) }
    var settingsSaved by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> onPickDownloadLocation(uri) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = withContext(Dispatchers.IO) { onAddTorrent(uri) }
        }
    }

    val filteredTorrents = remember(torrents, searchText) {
        if (searchText.isBlank()) torrents else torrents.filter { it.title.contains(searchText.trim(), ignoreCase = true) }
    }
    val inSelectionMode = selectedHashes.isNotEmpty()

    LaunchedEffect(incomingMagnet) {
        val link = incomingMagnet
        if (!link.isNullOrBlank()) {
            showAddDownload = true
            showSettings = false
            showAbout = false
            magnet = link
            message = onAddMagnet(link)
            if (message.startsWith("Torrent added")) {
                magnet = ""
                showAddDownload = false
            }
            onIncomingMagnetHandled()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxHeight()) {
                    Text("Shigaflow", modifier = Modifier.padding(20.dp), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    NavigationDrawerItem(
                        label = { Text("Your downloads") },
                        selected = !showAddDownload && !showSettings && !showAbout,
                        onClick = { showAddDownload = false; showSettings = false; showAbout = false; scope.launch { drawerState.close() } },
                        icon = { Text("⇩") },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    NavigationDrawerItem(
                        label = { Text("Add a download") },
                        selected = showAddDownload,
                        onClick = { showAddDownload = true; showSettings = false; showAbout = false; scope.launch { drawerState.close() } },
                        icon = { Text("＋") },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    NavigationDrawerItem(
                        label = { Text("Settings") },
                        selected = showSettings,
                        onClick = { showSettings = true; showAddDownload = false; showAbout = false; scope.launch { drawerState.close() } },
                        icon = { Text("⚙") },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    NavigationDrawerItem(
                        label = { Text("About") },
                        selected = showAbout,
                        onClick = { showAbout = true; showSettings = false; showAddDownload = false; scope.launch { drawerState.close() } },
                        icon = { Text("ⓘ") },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    NavigationDrawerItem(
                        label = { Text("Exit") },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; showExitConfirm = true },
                        icon = { Text("⇥") },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Let justice be done, though the heavens fall - Aldnoah.Zero",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)
                    )
                }
            }
        }
    ) {
        Scaffold(
            containerColor = Canvas,
            bottomBar = {
                if (inSelectionMode && !showSettings && !showAddDownload && !showAbout) {
                    Surface(color = Ink, shadowElevation = 8.dp) {
                        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text("${selectedHashes.size} selected", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { selectedHashes.forEach { onToggleTorrent(it, true) } },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Track,
                                            contentColor = TextPrimary
                                        ),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            if (selectedHashes.size == 1) "Pause" else "Pause All",
                                            maxLines = 1,
                                            fontSize = 13.sp
                                        )
                                    }

                                    Button(
                                        onClick = { selectedHashes.forEach { onToggleTorrent(it, false) } },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Track,
                                            contentColor = TextPrimary
                                        ),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            if (selectedHashes.size == 1) "Resume" else "Resume All",
                                            maxLines = 1,
                                            fontSize = 13.sp
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            pendingDelete = torrents.filter { selectedHashes.contains(it.hash) }
                                            deleteDownloadedFiles = false
                                        },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF8B3038),
                                            contentColor = Color.White
                                        ),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            if (selectedHashes.size == 1) "Delete" else "Delete All",
                                            maxLines = 1,
                                            fontSize = 13.sp
                                        )
                                    }

                                    Button(
                                        onClick = { selectedHashes = emptySet() },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Track,
                                            contentColor = TextPrimary
                                        ),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text("Cancel", maxLines = 1, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Text("☰", color = TextPrimary, fontSize = 26.sp) }
                        Text("Shigaflow", color = TextPrimary, fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                        if (!showSettings && !showAddDownload && !showAbout) {
                            IconButton(onClick = {
                                searchVisible = !searchVisible
                                if (!searchVisible) searchText = ""
                            }) { Text(if (searchVisible) "×" else "⌕", color = TextPrimary, fontSize = 30.sp) }
                        }
                    }
                }

                if (showSettings) item {
                    Card(colors = CardDefaults.cardColors(containerColor = Ink), shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("Settings", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("Appearance", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(if (isDarkTheme) "Dark theme" else "Light theme", color = TextSecondary)
                                Switch(checked = isDarkTheme, onCheckedChange = onThemeChange)
                            }
                            HorizontalDivider(color = Track)
                            Text("Download location", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text(downloadLocationLabel, color = TextSecondary, fontSize = 13.sp)
                            OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Choose download folder") }
                            OutlinedButton(onClick = { onResetDownloadLocation(); settingsSaved = true }, modifier = Modifier.fillMaxWidth()) { Text("Use default Downloads folder") }
                            Text("This folder is used for completed-file exports. Active torrent data remains in app storage while downloading.", color = TextSecondary, fontSize = 12.sp)
                            HorizontalDivider(color = Track)
                            Text("Download speed limit", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text("Limit in KB/s. Use 0 for unlimited.", color = TextSecondary, fontSize = 12.sp)
                            OutlinedTextField(value = speedLimitText, onValueChange = { speedLimitText = it.filter(Char::isDigit).take(7) }, label = { Text("Download KB/s") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = uploadLimitText, onValueChange = { uploadLimitText = it.filter(Char::isDigit).take(7) }, label = { Text("Upload KB/s") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Button(onClick = { onSaveSpeedLimits(speedLimitText.toIntOrNull()?.coerceAtMost(2_000_000) ?: 0, uploadLimitText.toIntOrNull()?.coerceAtMost(2_000_000) ?: 0); settingsSaved = true }, modifier = Modifier.fillMaxWidth()) { Text("Save settings") }
                            if (settingsSaved) Text("Settings saved", color = Mint, fontSize = 13.sp)
                        }
                    }
                }

                if (showAbout) item {
                    Card(colors = CardDefaults.cardColors(containerColor = Ink), shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("About Shigaflow", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Surface(
                                color = Track,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(190.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.klhw),
                                    contentDescription = "KLHW",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Text("Click ME > KLHW", color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                                val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.app.goo.gl/rs8WMZ9YrTwRiU4UA"))
                                runCatching { context.startActivity(mapIntent) }
                            })
                        }
                    }
                }

                if (!showSettings && showAddDownload) item {
                    Card(colors = CardDefaults.cardColors(containerColor = Ink), shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Add a download", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("Paste a magnet link or choose a .torrent file.", color = TextSecondary, fontSize = 14.sp)
                            OutlinedTextField(value = magnet, onValueChange = { magnet = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("magnet:?xt=urn:btih:…") }, singleLine = true)
                            Button(onClick = {
                                val submitted = magnet
                                scope.launch {
                                    message = withContext(Dispatchers.IO) { onAddMagnet(submitted) }
                                    if (message.startsWith("Torrent added")) magnet = ""
                                }
                            }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), enabled = magnet.isNotBlank()) { Text("Add magnet link", fontWeight = FontWeight.Bold) }
                            OutlinedButton(onClick = { picker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Choose .torrent file") }
                            if (message != "Ready for a magnet link or .torrent file.") Text(message, color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }

                if (!showSettings && !showAddDownload && !showAbout) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Your Downloads", color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        if (searchVisible) {
                            OutlinedTextField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("Search downloads") },
                                trailingIcon = {
                                    if (searchText.isNotEmpty()) IconButton(onClick = { searchText = ""; searchVisible = false }) { Text("×") }
                                }
                            )
                        }
                        Text("${filteredTorrents.size} torrent(s)", color = TextSecondary, fontSize = 13.sp)
                    }
                }

                if (!showSettings && !showAddDownload && !showAbout && filteredTorrents.isEmpty()) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Ink), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("⇩", fontSize = 34.sp, color = Mint)
                                Text(if (searchText.isNotBlank()) "No matching downloads" else "Nothing downloading yet", color = TextPrimary, fontWeight = FontWeight.Bold)
                                Text(if (searchText.isNotBlank()) "Try another torrent name." else "Add a magnet link or a .torrent file to get started.", color = TextSecondary, fontSize = 13.sp)
                            }
                        }
                    }
                } else if (!showSettings && !showAddDownload && !showAbout) {
                    items(filteredTorrents, key = { it.hash }) { torrent ->
                        val isSelected = selectedHashes.contains(torrent.hash)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Ink),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (inSelectionMode) {
                                        selectedHashes = if (isSelected) selectedHashes - torrent.hash else selectedHashes + torrent.hash
                                    }
                                },
                                onLongClick = {
                                    selectedHashes = selectedHashes + torrent.hash
                                }
                            )
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(torrent.title, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (inSelectionMode) Checkbox(checked = isSelected, onCheckedChange = { checked ->
                                        selectedHashes = if (checked) selectedHashes + torrent.hash else selectedHashes - torrent.hash
                                    })
                                }
                                Text("${(torrent.progress * 100).toInt()}% · ${torrent.rate} · ${torrent.peers} peers", color = TextSecondary, fontSize = 12.sp)
                                LinearProgressIndicator(progress = { torrent.progress }, modifier = Modifier.fillMaxWidth(), color = Mint, trackColor = Track)
                                if (!inSelectionMode) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(torrent.state.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                        OutlinedButton(onClick = { onToggleTorrent(torrent.hash, !torrent.paused) }) {
                                            Text(if (torrent.paused) "Resume" else "Pause")
                                        }
                                        TextButton(onClick = {
                                            pendingDelete = listOf(torrent)
                                            deleteDownloadedFiles = false
                                        }) { Text("Delete") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (pendingDelete.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { pendingDelete = emptyList(); deleteDownloadedFiles = false },
                title = { Text(if (pendingDelete.size == 1) "Delete torrent?" else "Delete ${pendingDelete.size} torrents?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Remove the selected torrent ${if (pendingDelete.size == 1) "entry" else "entries"} from Shigaflow?")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = deleteDownloadedFiles, onCheckedChange = { deleteDownloadedFiles = it })
                            Text("Also delete downloaded files")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val deleteFiles = deleteDownloadedFiles
                        val toDelete = pendingDelete.toList()
                        pendingDelete = emptyList()
                        deleteDownloadedFiles = false
                        toDelete.forEach { onDeleteTorrent(it.hash, deleteFiles) }
                        selectedHashes = emptySet()
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { pendingDelete = emptyList(); deleteDownloadedFiles = false }) { Text("Cancel") } },
                containerColor = Ink,
                titleContentColor = TextPrimary,
                textContentColor = TextSecondary
            )
        }

        if (showExitConfirm) {
            AlertDialog(
                onDismissRequest = { showExitConfirm = false },
                title = { Text("Exit Shigaflow?") },
                text = { Text("This stops the torrent engine and all active downloads, and removes the ongoing notification.") },
                confirmButton = { TextButton(onClick = { showExitConfirm = false; onExit() }) { Text("Exit") } },
                dismissButton = { TextButton(onClick = { showExitConfirm = false }) { Text("Cancel") } },
                containerColor = Ink,
                titleContentColor = TextPrimary,
                textContentColor = TextSecondary
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}


/** Keeps an ongoing notification visible while Shigaflow is running in the background. */
class ShigaflowService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification(this, emptyList())

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Shigaflow background",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Ongoing Shigaflow service notification"
                }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "torrentmate_running"
        private const val NOTIFICATION_ID = 4101

        fun intent(context: Context) = Intent(context, ShigaflowService::class.java)

        internal fun updateNotification(context: Context, torrents: List<TorrentUiState>) {
            val activeDownloads = torrents
                .filter { !it.paused && it.progress < 1f }
                .sortedBy { it.title.lowercase(Locale.ROOT) }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(context, activeDownloads))
        }

        private fun buildNotification(context: Context, torrents: List<TorrentUiState>): android.app.Notification {
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPendingIntent = android.app.PendingIntent.getActivity(
                context,
                1,
                openAppIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0
            )

            val exitIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_EXIT_SHIGAFLOW
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val exitPendingIntent = android.app.PendingIntent.getActivity(
                context,
                2,
                exitIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0
            )

            val title = if (torrents.isEmpty()) {
                "No active downloads"
            } else {
                "${torrents.size} active download(s)"
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_torrentmate)
                .setContentTitle("Shigaflow")
                .setContentText(title)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setNumber(torrents.size)
                .setContentIntent(contentPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit", exitPendingIntent)

            val inboxStyle = InboxStyle()
                .setBigContentTitle("Shigaflow • $title")
                .setSummaryText("Exit stops all downloads without deleting files.")

            torrents.forEach { torrent ->
                val percent = (torrent.progress * 100f).toInt().coerceIn(0, 100)
                inboxStyle.addLine("${torrent.title} — $percent%")
            }

            if (torrents.isEmpty()) {
                inboxStyle.addLine("No torrents are currently downloading.")
            }

            return builder
                .setStyle(inboxStyle)
                .build()
        }
    }
}

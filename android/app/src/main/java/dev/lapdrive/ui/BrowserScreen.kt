package dev.lapdrive.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.lapdrive.AppLock
import dev.lapdrive.AppViewModel
import dev.lapdrive.Entry
import dev.lapdrive.SortBy

fun iconFor(e: Entry): ImageVector {
    if (e.isDir) return Icons.Filled.Folder
    val m = e.mime.orEmpty()
    val ext = e.name.substringAfterLast('.', "").lowercase()
    return when {
        m.startsWith("image/") -> Icons.Filled.Image
        m.startsWith("video/") -> Icons.Filled.Movie
        m.startsWith("audio/") -> Icons.Filled.MusicNote
        m == "application/pdf" -> Icons.Filled.PictureAsPdf
        m.startsWith("text/") || ext in TEXT_EXT -> Icons.Filled.Description
        ext in setOf("zip", "tar", "gz", "7z", "rar", "xz", "bz2", "zst") -> Icons.Filled.Archive
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

val TEXT_EXT = setOf(
    "txt", "md", "json", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg", "log", "csv", "tsv",
    "py", "kt", "kts", "java", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "rs", "go", "sh",
    "bash", "zsh", "fish", "html", "css", "scss", "sql", "gradle", "properties", "env", "rb", "php",
    "swift", "dart", "lua", "vim", "gitignore", "dockerfile", "makefile", "lock",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Entry?>(null) }
    var deleteTarget by remember { mutableStateOf<Entry?>(null) }
    var unpairAsk by remember { mutableStateOf(false) }
    val readOnly = vm.info?.readOnly == true

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.upload(uris)
    }

    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }

    BackHandler(enabled = searching || vm.path.isNotEmpty()) {
        if (searching) { searching = false; query = ""; vm.closeSearch() } else vm.up()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (vm.path.isNotEmpty() || searching) IconButton(onClick = {
                        if (searching) { searching = false; query = ""; vm.closeSearch() } else vm.up()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it; vm.search(it) },
                            placeholder = { Text("Search in this folder") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            vm.path.substringAfterLast('/').ifEmpty { vm.info?.serverName ?: vm.api?.conn?.serverName ?: "LapDrive" },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (!searching) IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search") }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.Filled.Sort, "Sort") }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            SortBy.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text((if (vm.sortBy == s) "• " else "  ") + s.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                    onClick = { vm.sortBy = s; sortMenu = false },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            if (!readOnly) DropdownMenuItem(
                                text = { Text("New folder") }, leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                                onClick = { menu = false; newFolder = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Refresh") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                onClick = { menu = false; vm.load() },
                            )
                            DropdownMenuItem(
                                text = { Text("Unpair this phone") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                                onClick = { menu = false; unpairAsk = true },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!readOnly && vm.error == null) ExtendedFloatingActionButton(
                onClick = { AppLock.expectExternal = true; picker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Filled.Upload, null) },
                text = { Text("Upload") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { TransferBar(vm) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!searching) Breadcrumbs(vm)
            if (vm.pendingShare.isNotEmpty()) ShareBanner(vm, readOnly)
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()

            val err = vm.error
            if (err != null) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.WifiOff, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
                    Text(err, Modifier.padding(16.dp))
                    Button(onClick = { vm.connect() }) { Text("Try again") }
                }
                return@Column
            }

            val list = vm.sorted(vm.searchResults ?: vm.items)
            PullToRefreshBox(isRefreshing = false, onRefresh = { vm.load() }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (list.isEmpty() && !vm.loading) item {
                        Text(
                            if (vm.searchResults != null) "No results" else "This folder is empty",
                            Modifier.fillMaxWidth().padding(32.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    items(list, key = { it.path }) { e ->
                        EntryRow(
                            vm, e, readOnly, showPath = vm.searchResults != null,
                            onRename = { renameTarget = e }, onDelete = { deleteTarget = e },
                            onOpenWith = { openWith(ctx, vm, e) },
                        )
                    }
                }
            }
        }
    }

    if (newFolder) NameDialog("New folder", "", "Create", { newFolder = false }) { vm.mkdir(it); newFolder = false }
    renameTarget?.let { t -> NameDialog("Rename", t.name, "Rename", { renameTarget = null }) { vm.rename(t, it); renameTarget = null } }
    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${t.name}?") },
            text = { Text("The laptop moves it to its trash. You can restore it there.") },
            confirmButton = { TextButton(onClick = { vm.delete(t); deleteTarget = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
    if (unpairAsk) AlertDialog(
        onDismissRequest = { unpairAsk = false },
        title = { Text("Unpair this phone?") },
        text = { Text("The app deletes its key. To connect again, pair with a new QR code. To block the old key on the laptop too, run: lapdrive revoke <id>") },
        confirmButton = { TextButton(onClick = { unpairAsk = false; vm.unpair() }) { Text("Unpair") } },
        dismissButton = { TextButton(onClick = { unpairAsk = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Breadcrumbs(vm: AppViewModel) {
    val parts = if (vm.path.isEmpty()) emptyList() else vm.path.split('/')
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { vm.load("") }) {
            Icon(Icons.Filled.Home, null, Modifier.size(18.dp))
            Text(" " + (vm.info?.rootName ?: ""))
        }
        parts.forEachIndexed { i, p ->
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp))
            TextButton(onClick = { vm.load(parts.take(i + 1).joinToString("/")) }) { Text(p, maxLines = 1) }
        }
    }
}

@Composable
private fun ShareBanner(vm: AppViewModel, readOnly: Boolean) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (readOnly) "The laptop is read-only. You cannot upload the shared files."
                else "Upload ${vm.pendingShare.size} shared file(s) to this folder?",
                Modifier.weight(1f),
            )
            if (!readOnly) TextButton(onClick = { vm.upload(vm.pendingShare); vm.pendingShare = emptyList() }) { Text("Upload") }
            IconButton(onClick = { vm.pendingShare = emptyList() }) { Icon(Icons.Filled.Close, "Cancel") }
        }
    }
}

@Composable
private fun TransferBar(vm: AppViewModel) {
    if (vm.transfers.isEmpty()) return
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            vm.transfers.take(4).forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (t.upload) Icons.Filled.Upload else Icons.Filled.Download, null, Modifier.size(18.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Text(t.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (t.progress < 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    IconButton(onClick = { t.job?.cancel() }) { Icon(Icons.Filled.Close, "Cancel") }
                }
            }
            if (vm.transfers.size > 4) Text("+${vm.transfers.size - 4} more", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EntryRow(
    vm: AppViewModel, e: Entry, readOnly: Boolean, showPath: Boolean,
    onRename: () -> Unit, onDelete: () -> Unit, onOpenWith: () -> Unit,
) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val sub = buildString {
        if (showPath) append(e.path.substringBeforeLast('/', "/")).append(" • ")
        if (!e.isDir) append(Formatter.formatShortFileSize(ctx, e.size)).append(" • ")
        append(DateUtils.getRelativeTimeSpanString(e.mtime * 1000))
    }
    ListItem(
        modifier = Modifier.clickable { vm.open(e) },
        leadingContent = {
            val loader = vm.imageLoader
            if (!e.isDir && e.mime?.startsWith("image/") == true && loader != null && vm.api != null) {
                AsyncImage(
                    model = vm.api!!.thumbUrl(e.path), contentDescription = null, imageLoader = loader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                )
            } else Icon(iconFor(e), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Actions") }
                DropdownMenu(menu, { menu = false }) {
                    if (!e.isDir) {
                        DropdownMenuItem(text = { Text("Download to phone") }, leadingIcon = { Icon(Icons.Filled.Download, null) },
                            onClick = { menu = false; vm.download(e) })
                        DropdownMenuItem(text = { Text("Open with…") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                            onClick = { menu = false; onOpenWith() })
                    }
                    if (!readOnly) {
                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; onDelete() })
                    }
                }
            }
        },
    )
}

@Composable
private fun NameDialog(title: String, initial: String, action: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val valid = name.isNotBlank() && '/' !in name && name != "." && name != ".."
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onOk(name.trim()) }, enabled = valid) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

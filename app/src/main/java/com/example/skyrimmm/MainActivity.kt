package com.example.skyrimmm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SkyrimTheme { Surface(Modifier.fillMaxSize()) { App(this) } } }
    }
}

/** Material You colors on Android 12+, an icy Nordic palette on older versions. */
@Composable
fun SkyrimTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF9CCBEA), onPrimary = Color(0xFF00334B), primaryContainer = Color(0xFF1F4A66),
            onPrimaryContainer = Color(0xFFCDE6F7), secondaryContainer = Color(0xFF354A57), onSecondaryContainer = Color(0xFFD0E5F4),
            background = Color(0xFF10161B), surface = Color(0xFF10161B), onSurface = Color(0xFFE0E6EB))
        else -> lightColorScheme(primary = Color(0xFF2C6A8D), primaryContainer = Color(0xFFCDE6F7), onPrimaryContainer = Color(0xFF001E2E),
            secondaryContainer = Color(0xFFD0E5F4), onSecondaryContainer = Color(0xFF0B1E2A))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(ctx: Context) {
    val mgr = remember { ModManager(ctx) }
    val prefs = remember { ctx.getSharedPreferences("p", 0) }
    var dataUri by remember { mutableStateOf(prefs.getString("data", null)?.let(Uri::parse)) }
    var mods by remember { mutableStateOf(mgr.list()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var prog by remember { mutableStateOf<Prog?>(null) }
    DisposableEffect(Unit) {
        Progress.sink = { t, d, f -> prog = Prog(t, d, f) }
        onDispose { Progress.sink = null }
    }
    var wiz by remember { mutableStateOf<Triple<FomodWizard, FomodPack, Uri>?>(null) }
    var tab by remember { mutableStateOf(0) }
    var port by remember { mutableStateOf<Boolean?>(null) }
    var pluginsUri by remember { mutableStateOf(prefs.getString("plugins", null)?.let(Uri::parse)) }
    var skseZip by remember { mutableStateOf<Pair<Uri, List<String>>?>(null) }
    var confirm by remember { mutableStateOf<Mod?>(null) }
    var filesOf by remember { mutableStateOf<Mod?>(null) }
    var fast by remember { mutableStateOf(mgr.fastAccess()) }
    DisposableEffect(Unit) {
        val act = ctx as ComponentActivity
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) fast = mgr.fastAccess() }
        act.lifecycle.addObserver(obs)
        onDispose { act.lifecycle.removeObserver(obs) }
    }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }

    LaunchedEffect(status) { if (status.isNotBlank()) snack.showSnackbar(status) }
    LaunchedEffect(dataUri, mods.size) { // measure mods installed before sizes were recorded
        val d = dataUri ?: return@LaunchedEffect
        if (mods.any { it.size < 0 }) {
            withContext(Dispatchers.IO) { runCatching { mods.filter { it.size < 0 }.forEach { mgr.measure(d, it) } } }
            mods = mgr.list()
        }
    }
    LaunchedEffect(dataUri) {
        port = dataUri?.let { u -> withContext(Dispatchers.IO) { runCatching { mgr.findPortDir(u) != null }.getOrDefault(false) } }
    }

    fun io(label: String = "Installed", block: () -> Mod) {
        busy = true
        prog = Prog(when (label) { "Installed" -> "Installing"; "Uninstalled" -> "Uninstalling"; "Enabled" -> "Enabling"; "Disabled" -> "Disabling"; else -> "Working" }, "Starting…", null)
        scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching { block().let { "$label: ${it.name} (${it.files.size} files, ${it.plugins.size} plugin(s))" } }
                    .getOrElse { "Error: ${it.message}" }
            }
            mods = mgr.list(); busy = false; prog = null
        }
    }

    val pickDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) {
            ctx.contentResolver.takePersistableUriPermission(u,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { mgr.describe(u) } }
                r.onSuccess { prefs.edit().putString("data", u.toString()).apply(); dataUri = u; status = it }
                    .onFailure { status = "Error: ${it.message}" }
            }
        }
    }
    val pickPlugins = rememberLauncherForActivityResult(OpenRW()) { u ->
        if (u != null) {
            ctx.contentResolver.takePersistableUriPermission(u,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.edit().putString("plugins", u.toString()).apply(); pluginsUri = u
            status = "plugins.txt linked."
        }
    }

    fun begin(u: Uri) {
        val d = dataUri ?: return
        busy = true
        prog = Prog("Reading archive", "Preparing…", null)
        scope.launch {
            val pack = withContext(Dispatchers.IO) { runCatching { mgr.readFomod(u) }.getOrNull() }
            if (pack == null) { busy = false; io { mgr.install(d, u) } }
            else {
                val (ex, imgs) = withContext(Dispatchers.IO) {
                    mgr.existing(d, pack.config.fileDeps()) to runCatching { mgr.loadImages(u, pack) }.getOrDefault(emptyMap())
                }
                val w = FomodWizard(pack.config, { f -> normPath(f) in ex }, imgs)
                busy = false; prog = null
                if (w.done) io { mgr.installFomod(d, u, pack, w.result()) } else wiz = Triple(w, pack, u)
            }
        }
    }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null && dataUri != null) {
            busy = true
            prog = Prog("Reading archive", "Preparing…", null)
            scope.launch {
                val why = withContext(Dispatchers.IO) { runCatching { mgr.skseReasons(u) }.getOrDefault(emptyList()) }
                busy = false
                if (why.isNotEmpty()) { prog = null; skseZip = u to why } else begin(u)
            }
        }
    }

    skseZip?.let { (u, why) ->
        AlertDialog(
            onDismissRequest = { skseZip = null },
            title = { Text("SKSE mod detected") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This mod appears to need SKSE (Skyrim Script Extender). SKSE is not supported by this app, so this mod should not work and may cause crashes or broken scripts. You can still install it and uninstall it completely later.")
                    Text("Why it was flagged:", style = MaterialTheme.typography.titleSmall)
                    why.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    Text("Detection is a best guess, so it can be wrong in both directions.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            },
            confirmButton = { TextButton(onClick = { skseZip = null; begin(u) }) { Text("Install anyway") } },
            dismissButton = { TextButton(onClick = { skseZip = null; mgr.clearStage(); status = "Install cancelled." }) { Text("Cancel") } })
    }
    wiz?.let { (w, pack, zip) ->
        FomodDialog(w, onCancel = { wiz = null; mgr.clearStage(); status = "Install cancelled." }) {
            wiz = null; io { mgr.installFomod(dataUri!!, zip, pack, w.result()) }
        }
    }
    confirm?.let { m ->
        var details by remember(m.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Uninstall ${m.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("This removes ${m.files.size} files" + (if (m.size >= 0) " (${fmtSize(m.size)})" else "") + " the mod added" +
                            if (m.backups.isNotEmpty()) " and restores ${m.backups.size} file(s) it overwrote." else ".")
                    TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Show details") }
                    if (details) LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        item { Text("Removed (${m.files.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                        items(m.files) { Text(it, style = MaterialTheme.typography.bodySmall) }
                        item { Text("Restored to the original (${m.backups.size})", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) }
                        if (m.backups.isEmpty()) item { Text("Nothing to restore.", style = MaterialTheme.typography.bodySmall) }
                        else items(m.backups.keys.toList()) { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (m.dirs.isNotEmpty()) {
                            item { Text("Folders removed if left empty (${m.dirs.size})", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) }
                            items(m.dirs) { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { confirm = null; io("Uninstalled") { mgr.uninstall(dataUri!!, m); m } }) { Text("Uninstall") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }

    prog?.let { ProgressDialog(it) }

    filesOf?.let { m ->
        AlertDialog(
            onDismissRequest = { filesOf = null },
            title = { Text(m.name) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item { Text("${m.files.size} files" + (if (m.size >= 0) " · ${fmtSize(m.size)}" else ""), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                    items(m.files) { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
                    if (m.backups.isNotEmpty()) {
                        item { Text("Overwrote ${m.backups.size} existing file(s), restored on uninstall", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) }
                        items(m.backups.keys.toList()) { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { filesOf = null }) { Text("Close") } })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Skyrim Mod Manager", fontWeight = FontWeight.Bold)
                        Text("Legendary Edition" + if (version.isNotEmpty()) " · v$version" else "", style = MaterialTheme.typography.labelSmall)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Mods") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.List, null) }, label = { Text("Load order") })
            }
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Dashboard(dataUri, port, mods, busy, onFolder = { pickDir.launch(null) }, onInstall = {
                pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/x-7z-compressed",
                    "application/vnd.rar", "application/x-rar-compressed", "application/x-rar", "application/octet-stream"))
            })
            if (!fast && dataUri != null && Build.VERSION.SDK_INT >= 30) Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Fast mode is off. Allow All files access so installing and uninstalling work directly on your files.",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    }) { Text("Enable") }
                }
            }
            if (tab == 1) LoadOrderScreen(mgr, mods, dataUri, pluginsUri, { pickPlugins.launch(arrayOf("text/plain", "*/*")) }) { status = it }
            else ModList(mods, !busy && dataUri != null,
                onToggle = { m, on -> io(if (on) "Enabled" else "Disabled") { mgr.setEnabled(dataUri!!, m, on) } },
                onUninstall = { confirm = it }, onShowFiles = { filesOf = it })
        }
    }
}

data class Prog(val title: String, val detail: String, val fraction: Float?)

/** Blocks the screen while a long operation runs, showing what it is doing and how far along it is. */
@Composable
fun ProgressDialog(p: Prog) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp).widthIn(min = 280.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val f = p.fraction
                if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.Top) {
                    Text(p.detail, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (f != null) Text("  ${(f * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun Dashboard(dataUri: Uri?, port: Boolean?, mods: List<Mod>, busy: Boolean, onFolder: () -> Unit, onInstall: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (dataUri == null) {
                Text("Choose your Skyrim game folder (the one that contains Data) to get started.")
            } else {
                Text(dataUri.lastPathSegment?.substringAfterLast(':') ?: "Game folder",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatChip("${mods.size} mods")
                    StatChip("${mods.sumOf { it.plugins.size }} plugins")
                    StatChip(if (port == true) "Port synced" else "No port folder")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onFolder) {
                    Icon(Icons.Default.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (dataUri == null) "Choose folder" else "Change")
                }
                Button(onClick = onInstall, enabled = dataUri != null && !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("Install mod")
                }
            }
        }
    }
}

@Composable
fun StatChip(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun ModList(mods: List<Mod>, actionsEnabled: Boolean, onToggle: (Mod, Boolean) -> Unit, onUninstall: (Mod) -> Unit, onShowFiles: (Mod) -> Unit) {
    if (mods.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                Text("No mods yet", style = MaterialTheme.typography.titleMedium)
                Text("Install a zip, 7z or rar and it will appear here with an on/off switch and an uninstall button.",
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.outline)
            }
        }
        return
    }
    Text("Installed mods", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(mods, key = { it.id }) { m ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = if (m.enabled) MaterialTheme.colorScheme.surfaceContainer
                else MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Row(Modifier.padding(12.dp).alpha(if (m.enabled) 1f else 0.6f), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).clickable { onShowFiles(m) }, contentAlignment = Alignment.Center) {
                        Text(m.name.firstOrNull()?.uppercase() ?: "?", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${m.files.size} files" + (if (m.size >= 0) " · ${fmtSize(m.size)}" else "") + (if (m.plugins.isNotEmpty()) " · " + m.plugins.joinToString { it.substringAfterLast('/') } else "") +
                                (if (m.enabled) "" else " · disabled"), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Switch(checked = m.enabled, enabled = actionsEnabled, onCheckedChange = { onToggle(m, it) })
                    IconButton(onClick = { onUninstall(m) }, enabled = actionsEnabled) {
                        Icon(Icons.Default.Delete, "Uninstall", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
fun FomodDialog(w: FomodWizard, onCancel: () -> Unit, onFinish: () -> Unit) {
    val i = w.cur; val step = w.cfg.steps[i]; val flags = w.flagsAt(i)
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Text(step.name, style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.weight(1f)) {
                    step.groups.forEachIndexed { gi, g ->
                        item { Text("${g.name} (${g.type.removePrefix("Select")})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp)) }
                        itemsIndexed(g.plugins) { pi, p ->
                            val t = p.type(flags, w.has); val sel = w.picked[Triple(i, gi, pi)] == true
                            val locked = t == "Required" || t == "NotUsable" || g.type == "SelectAll"
                            val single = g.type == "SelectExactlyOne" || g.type == "SelectAtMostOne"
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                if (single) RadioButton(sel, { w.toggle(i, gi, pi) }, enabled = !locked)
                                else Checkbox(sel, { w.toggle(i, gi, pi) }, enabled = !locked)
                                w.images[p.image]?.let { Image(it.asImageBitmap(), null, Modifier.size(64.dp).padding(end = 8.dp)) }
                                Column {
                                    Text(p.name + if (t == "Recommended") " ★" else if (t == "NotUsable") " (unavailable)" else "")
                                    if (p.desc.isNotBlank()) Text(p.desc.take(300), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                w.problem()?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(Modifier.weight(1f))
                    if (w.hasPrev()) TextButton(onClick = { w.back() }) { Text("Back") }
                    Button(enabled = w.problem() == null, onClick = { if (w.hasNext()) w.next() else onFinish() }) { Text(if (w.hasNext()) "Next" else "Install") }
                }
            }
        }
    }
}

/** Opens a document with read+write access that survives restarts. */
class OpenRW : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}


@Composable
fun LoadOrderScreen(mgr: ModManager, mods: List<Mod>, dataUri: Uri?, manual: Uri?, onPick: () -> Unit, onStatus: (String) -> Unit) {
    val list = remember { mutableStateListOf<PluginEntry>() }
    var files by remember { mutableStateOf(OrderFiles(null, null, null)) }
    var star by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ready = files.dir != null || files.plugins != null

    LaunchedEffect(manual, dataUri, mods) {
        val f = withContext(Dispatchers.IO) { runCatching { mgr.orderFiles(dataUri, manual) }.getOrDefault(OrderFiles(null, null, null)) }
        files = f; list.clear(); dirty = false
        if (f.dir == null && f.plugins == null) return@LaunchedEffect
        val r = withContext(Dispatchers.IO) { runCatching { mgr.readOrder(f) } }
        val (st, entries) = r.onFailure { onStatus("Error: ${it.message}") }.getOrNull() ?: return@LaunchedEffect
        val known = entries.map { it.name.lowercase() }.toSet()
        val extra = mods.filter { it.enabled }.flatMap { it.plugins }.map { it.substringAfterLast('/') }
            .distinctBy { it.lowercase() }.filter { it.lowercase() !in known }.map { PluginEntry(it, false) }
        star = st; list.addAll(entries + extra)
    }

    fun move(i: Int, j: Int) { val e = list[i]; list[i] = list[j]; list[j] = e; dirty = true }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (files.dir != null) "Port folder synced" else "Plugin load order", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (files.dir == null) OutlinedButton(onClick = onPick) { Text(if (manual == null) "Select plugins.txt" else "Change") }
        Button(enabled = ready && dirty, onClick = {
            val ordered = list.sortedBy { if (it.name.endsWith(".esm", true)) 0 else 1 } // masters always load first
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { mgr.writeOrder(files, star, ordered, dataUri) } }
                if (r.isSuccess) {
                    list.clear(); list.addAll(ordered); dirty = false
                    onStatus("Saved (${ordered.count { it.active }} active of ${ordered.size}). ${r.getOrNull().orEmpty()}")
                } else onStatus("Error: ${r.exceptionOrNull()?.message}")
            }
        }) { Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save") }
    }
    if (!ready) {
        Text("Choose the game folder that contains the port folder, or select your plugins.txt manually, to manage plugins and their load order.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
    } else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(list, key = { _, e -> e.name.lowercase() }) { i, e ->
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(
                containerColor = if (e.active) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest)) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Checkbox(e.active, { list[i] = e.copy(active = it); dirty = true }) },
                    headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("#${i + 1}" + if (e.name.endsWith(".esm", true)) " · master" else "") },
                    trailingContent = {
                        Row {
                            IconButton(enabled = i > 0, onClick = { move(i, i - 1) }) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                            IconButton(enabled = i < list.lastIndex, onClick = { move(i, i + 1) }) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                        }
                    })
            }
        }
    }
}

fun fmtSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < (1L shl 20) -> "%.0f KB".format(b / 1024.0)
    b < (1L shl 30) -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

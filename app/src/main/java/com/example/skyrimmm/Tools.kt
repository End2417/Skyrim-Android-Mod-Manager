package com.example.skyrimmm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs slow work with the progress window and reports the result in the message bar. */
typealias TaskRunner = (String, () -> String) -> Unit

@Composable
fun ToolsDialog(onPick: (String) -> Unit, onClose: () -> Unit, autoCheck: Boolean = true, onAutoCheck: (Boolean) -> Unit = {}) {
    val items = listOf(
        Triple("verify", "Verify installed mods", "Check that every file of every mod is still there"),
        Triple("profiles", "Profiles and setups", "Save, switch, share or import mod and load order setups"),
        Triple("ini", "Skyrim.ini helper", "Turn on loose file loading"),
        Triple("trouble", "Troubleshoot crashes", "Find the mod causing a problem by switching mods off in steps"),
        Triple("update", "Check for updates", "See whether a newer version of the app is available"))
    AlertDialog(
        onDismissRequest = onClose, title = { Text("Tools") },
        text = {
            Column {
                items.forEach { (key, title, hint) ->
                    ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), modifier = Modifier.clickable { onPick(key) },
                        headlineContent = { Text(title) }, supportingContent = { Text(hint) })
                }
                ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("Check for updates when the app opens") },
                    supportingContent = { Text("Looks up the latest release on GitHub") },
                    trailingContent = { Switch(checked = autoCheck, onCheckedChange = onAutoCheck) })
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}

@Composable
fun VerifyDialog(mgr: ModManager, dataUri: Uri, mods: List<Mod>, onClose: () -> Unit) {
    var results by remember { mutableStateOf<List<Triple<Mod, List<String>, List<String>>>?>(null) }
    LaunchedEffect(Unit) {
        results = withContext(Dispatchers.IO) {
            mods.map { m ->
                val r = runCatching { mgr.verify(dataUri, m) }.getOrDefault(emptyList<String>() to emptyList())
                Triple(m, r.first, r.second)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onClose, title = { Text("Verify mods") },
        text = {
            val r = results
            if (r == null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Checking ${mods.size} mod(s)…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val bad = r.filter { it.second.isNotEmpty() || it.third.isNotEmpty() }
                if (bad.isEmpty()) Text("All ${mods.size} mod(s) look intact: every file is present and has its original size.")
                else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    bad.forEach { (m, miss, chg) ->
                        item { Text("${m.name}: ${miss.size} missing, ${chg.size} changed", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                        items(miss.take(10)) { Text("missing: $it", style = MaterialTheme.typography.bodySmall) }
                        items(chg.take(10)) { Text("changed: $it", style = MaterialTheme.typography.bodySmall) }
                        if (miss.size + chg.size > 20) item { Text("…and more", style = MaterialTheme.typography.bodySmall) }
                    }
                    item { Text("To repair a mod, use its Update button with the original archive.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 12.dp)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}

@Composable
fun ProfilesDialog(mgr: ModManager, dataUri: Uri, ctx: Context, tick: Int, task: TaskRunner, onStatus: (String) -> Unit, onClose: () -> Unit) {
    var profiles by remember(tick) { mutableStateOf(mgr.profiles()) }
    val active = remember(tick) { mgr.activeProfile() }
    var name by remember { mutableStateOf("") }
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    fun create(empty: Boolean) {
        val n = name.trim(); name = ""
        task("Creating profile") {
            val p = mgr.captureProfile(n, dataUri, empty)
            mgr.saveProfile(p)
            mgr.applyProfile(dataUri, p)
        }
    }
    AlertDialog(
        onDismissRequest = onClose, title = { Text("Profiles") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (active != null) "Active profile: ${active.name}" else "No profile is active",
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text("The active profile saves itself: turning mods on or off, installing, uninstalling and load order changes all go into it. Switching turns the right mods on and off and restores that profile's plugin order.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("New profile name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = name.isNotBlank(), onClick = { create(true) }) { Text("New: all mods off") }
                    OutlinedButton(enabled = name.isNotBlank(), onClick = { create(false) }) { Text("Copy current") }
                }
                Text("Creating a profile switches to it. “All mods off” starts clean so you can choose a different set of mods; “Copy current” starts from what is on now.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(profiles, key = { it.id }) { p ->
                        val isActive = p.id == active?.id
                        ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(p.name + if (isActive) "  ✓" else "", fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal) },
                            supportingContent = { Text("${p.enabled.size} mods on · ${p.order.count { it.active }} plugins" + if (isActive) " · active" else "") },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(enabled = !isActive, onClick = { onClose(); task("Switching profile") { mgr.applyProfile(dataUri, p) } }) { Text("Switch") }
                                    IconButton(onClick = {
                                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, mgr.profileJson(p)) }, "Share setup"))
                                    }) { Icon(Icons.Default.Share, "Share") }
                                    IconButton(enabled = !isActive, onClick = { mgr.deleteProfile(p); profiles = mgr.profiles() }) {
                                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                                }
                            })
                    }
                }
                TextButton(onClick = {
                    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                    val p = runCatching { mgr.parseProfile(text) }.getOrNull()
                    if (p == null) onStatus("The clipboard doesn't hold a Skyrim Mod Manager setup.")
                    else {
                        mgr.saveProfile(p); profiles = mgr.profiles()
                        val installed = mgr.list()
                        val missing = p.enabled.filter { n -> installed.none { it.name.equals(n, true) } }
                        onStatus("Imported profile ${p.name}." + if (missing.isNotEmpty()) " Mods you still need: " + missing.joinToString { n ->
                            p.mods.firstOrNull { it.name == n }?.link?.takeIf { it.isNotBlank() }?.let { "$n ($it)" } ?: n } else " All its mods are installed.")
                    }
                }) { Text("Import a shared setup from the clipboard") }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}

@Composable
fun IniDialog(mgr: ModManager, dataUri: Uri?, ini: Uri?, onPick: () -> Unit, task: TaskRunner, onClose: () -> Unit) {
    var state by remember(ini) { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    LaunchedEffect(ini) { state = if (ini == null) null else withContext(Dispatchers.IO) { runCatching { mgr.iniState(ini) }.getOrNull() } }
    var loose by remember(state) { mutableStateOf(state?.let { it.first && it.second } ?: false) }
    AlertDialog(
        onDismissRequest = onClose, title = { Text("Skyrim.ini helper") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPick) { Text(if (ini == null) "Select Skyrim.ini" else "Change Skyrim.ini") }
                when {
                    ini == null -> Text("Pick the Skyrim.ini your game uses.", style = MaterialTheme.typography.bodySmall)
                    state == null -> Text("This ini file could not be read.", style = MaterialTheme.typography.bodySmall)
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = loose, onCheckedChange = { loose = it }); Spacer(Modifier.width(12.dp))
                            Text("Load loose files (needed by most mods)")
                        }
                        Text("Sets bInvalidateOlderFiles=1 and clears sResourceDataDirsFinal in [Archive]. A backup of the ini is saved first.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = ini != null && state != null, onClick = { onClose(); task("Updating Skyrim.ini") { mgr.applyIni(ini!!, loose, dataUri) } }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } })
}

@Composable
fun TroubleshootDialog(mgr: ModManager, dataUri: Uri, tick: Int, task: TaskRunner, onClose: () -> Unit) {
    val t = remember(tick) { mgr.troubleState() }
    val alloff = t?.phase == "alloff"
    AlertDialog(
        onDismissRequest = onClose, title = { Text("Troubleshoot crashes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    t == null -> Text("This switches your mods off in steps to find the one causing a crash. Each step turns some mods on and others off, you start the game, and you tell the app what happened. Your plugin list is not edited while this runs.")
                    alloff -> Text("All mods are off. Start the game. Does the problem still happen?")
                    else -> Text("${t.cands.size} suspect mod(s) left. ${t.testing.size} of them are on for this test, plus ${t.good.size} mod(s) already cleared. Start the game and test.")
                }
                if (t != null) TextButton(onClick = { task("Restoring mods") { mgr.troubleRestore(dataUri) } }) { Text("Stop and restore my mods") }
            }
        },
        confirmButton = {
            if (t == null) TextButton(onClick = { task("Troubleshooting") { mgr.troubleStart(dataUri) } }) { Text("Switch all mods off") }
            else TextButton(onClick = { task("Troubleshooting") { mgr.troubleAnswer(dataUri, true) } }) { Text(if (alloff) "Yes, still happens" else "Problem still happens") }
        },
        dismissButton = {
            if (t == null) TextButton(onClick = onClose) { Text("Close") }
            else TextButton(onClick = { task("Troubleshooting") { mgr.troubleAnswer(dataUri, false) } }) { Text(if (alloff) "No, it's gone" else "Problem is gone") }
        })
}

@Composable
fun EditDetailsDialog(m: Mod, onSave: (String, String, String, String) -> Unit, onOpenLink: (String) -> Unit, onClose: () -> Unit) {
    var name by remember(m.id) { mutableStateOf(m.name) }
    var version by remember(m.id) { mutableStateOf(m.version) }
    var note by remember(m.id) { mutableStateOf(m.note) }
    var link by remember(m.id) { mutableStateOf(m.link) }
    AlertDialog(
        onDismissRequest = onClose, title = { Text("Mod details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = version, onValueChange = { version = it }, label = { Text("Version") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = link, onValueChange = { link = it }, label = { Text("Source link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                if (link.isNotBlank()) TextButton(onClick = { onOpenLink(link) }) { Text("Open source page") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, version, note, link) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

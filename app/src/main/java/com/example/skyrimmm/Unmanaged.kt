package com.example.skyrimmm

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lists files in Data that no installed mod owns and lets the person move the ones they pick into a backup folder. */
@Composable
fun UnmanagedDialog(mgr: ModManager, dataUri: Uri, task: TaskRunner, onClose: () -> Unit) {
    var groups by remember { mutableStateOf<List<Unmanaged>?>(null) }
    val picked = remember { mutableStateListOf<Int>() }
    var open by remember { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        groups = withContext(Dispatchers.IO) { runCatching { mgr.scanUnmanaged(dataUri) }.getOrDefault(emptyList()) }
    }
    AlertDialog(
        onDismissRequest = onClose, title = { Text("Unmanaged files") },
        text = {
            val g = groups
            when {
                g == null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Scanning the Data folder…")
                }
                g.isEmpty() -> Text("Every file in Data belongs to a mod this app installed, or is a known game file.")
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Files in Data that no mod installed with this app owns. This can include game files the app does not know about, so only pick what you recognise. " +
                            "Picked files are moved to SkyrimMM Backups/Removed in your game folder, not deleted.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        itemsIndexed(g) { i, u ->
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = i in picked, onCheckedChange = { on -> if (on) picked.add(i) else picked.remove(i) })
                                    Column(Modifier.weight(1f).clickable { open = if (open == i) null else i }) {
                                        Text(u.label, fontWeight = FontWeight.Medium)
                                        Text("${u.files.size} file(s) · ${fmtSize(u.size)} · tap to " + (if (open == i) "hide" else "see") + " the files", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (open == i) {
                                    u.files.take(60).forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 48.dp)) }
                                    if (u.files.size > 60) Text("… and ${u.files.size - 60} more", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 48.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = picked.isNotEmpty(), onClick = { confirm = true }) { Text("Move selected (${picked.size})") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } })
    if (confirm) {
        val g = groups.orEmpty()
        val files = picked.sorted().flatMap { g[it].files }
        AlertDialog(
            onDismissRequest = { confirm = false }, title = { Text("Move ${files.size} files?") },
            text = { Text("They are moved out of Data into SkyrimMM Backups/Removed in your game folder, keeping their folder layout, so you can put them back by hand. Plugins among them are also taken out of the load order.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false; onClose()
                    task("Moving files") { val n = mgr.removeUnmanaged(dataUri, files); "Moved $n file(s) to SkyrimMM Backups/Removed." }
                }) { Text("Move") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    }
}

/** Shows what an archive matched in Data and lets the person record it as an installed mod. */
@Composable
fun AdoptDialog(plan: AdoptPlan, onAdopt: (String) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(plan.name) }
    AlertDialog(
        onDismissRequest = onClose, title = { Text("Adopt existing mod") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (plan.files.isEmpty()) Text("None of this archive's files were found in your Data folder with the same size, or the app already tracks them.")
                else {
                    Text("Found ${plan.files.size} of ${plan.total} files from this archive in your Data folder, with matching sizes. Files that were never installed, such as readmes or unused FOMOD options, are not counted.")
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Mod name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Adopting only records these files, so the app can disable and uninstall them later. Nothing in Data changes now. " +
                            "Files the mod replaced in the past cannot be restored, because their originals were never saved.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        },
        confirmButton = { TextButton(enabled = plan.files.isNotEmpty(), onClick = { onAdopt(name) }) { Text("Adopt") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

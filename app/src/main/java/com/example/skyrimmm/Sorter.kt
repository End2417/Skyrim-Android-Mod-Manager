package com.example.skyrimmm

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The LOOT masterlists the sorter reads: the copies bundled with the app, or newer ones saved by "Update rules". */
object LootRules {
    private const val LE_URL = "https://raw.githubusercontent.com/loot/skyrim/v0.29/masterlist.yaml"
    private const val SE_URL = "https://raw.githubusercontent.com/loot/skyrimse/v0.29/masterlist.yaml"

    private fun dir(ctx: Context) = File(ctx.filesDir, "loot").apply { mkdirs() }

    private fun text(ctx: Context, saved: String, asset: String): String {
        val f = File(dir(ctx), saved)
        if (f.exists()) {
            try { return f.readText() } catch (_: Exception) { /* fall back to the bundled copy */ }
        }
        return ctx.assets.open("loot/$asset").bufferedReader().use { it.readText() }
    }

    /** Legendary Edition rules first, Special Edition rules as the fallback. */
    fun load(ctx: Context): Pair<LootSort.Master, LootSort.Master> =
        LootSort.parse(text(ctx, "le.yaml", "skyrim_le.yaml")) to LootSort.parse(text(ctx, "se.yaml", "skyrim_se.yaml"))

    /** When the rules were last downloaded, or null while the bundled ones are used. */
    fun updatedAt(ctx: Context): Long? = File(dir(ctx), "le.yaml").takeIf { it.exists() }?.lastModified()

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "SkyrimModManager")
        try {
            if (c.responseCode != 200) error("download failed (HTTP ${c.responseCode})")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Downloads both masterlists, checks that they read properly and only then replaces the saved copies. */
    fun refresh(ctx: Context): String {
        val le = fetch(LE_URL); val se = fetch(SE_URL)
        val a = LootSort.parse(le); val b = LootSort.parse(se)
        if (a.entries.size < 1000 || a.groups.isEmpty() || b.entries.size < 1000 || b.groups.isEmpty())
            error("the downloaded files did not look like LOOT masterlists, so the current rules were kept")
        val d = dir(ctx)
        File(d, "le.tmp").writeText(le); File(d, "se.tmp").writeText(se)
        File(d, "le.tmp").renameTo(File(d, "le.yaml")); File(d, "se.tmp").renameTo(File(d, "se.yaml"))
        return "LOOT rules updated: ${a.entries.size} Legendary Edition and ${b.entries.size} Special Edition entries."
    }
}

/** Works out what sorting the current order with the LOOT rules would change. Nothing is written. */
fun lootSortPlan(ctx: Context, order: List<PluginEntry>, scan: PluginScan): LootSort.Result {
    val (le, se) = LootRules.load(ctx)
    val master = HashMap<String, Boolean>(); val masters = HashMap<String, List<String>>()
    for ((k, v) in scan.info) { master[k] = v.isMaster; masters[k] = v.masters }
    val env = LootSort.Env(scan.present, order.filter { it.active }.map { it.name.lowercase() }.toSet(), master, masters)
    return LootSort.sort(order.map { it.name }, le, se, env)
}

private const val SHIFT = "moved to make room"

/** Shows what the sort would change; nothing happens until Apply, and the new order still has to be saved (which makes a backup). */
@Composable
fun SortDialog(res: LootSort.Result, total: Int, onApply: () -> Unit, onClose: () -> Unit) {
    val main = res.moved.filter { !it.why.startsWith(SHIFT) }
    val shifted = res.moved.size - main.size
    AlertDialog(
        onDismissRequest = onClose, title = { Text("Sort with LOOT rules") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (res.moved.isEmpty()) "Your load order already follows the rules. Nothing would move."
                     else "${main.size} plugin(s) have to move" + (if (shifted > 0) " and $shifted others shift to make room" else "") + ".",
                    fontWeight = FontWeight.Medium)
                Text("${res.known} of $total plugins have group or load-after rules in the masterlists. ${res.rulesUsed} load-after rule(s) applied" +
                        (if (res.rulesSkipped > 0) ", ${res.rulesSkipped} skipped (their condition is false or can't be checked here)" else "") + ".",
                    style = MaterialTheme.typography.bodySmall)
                if (res.conflicts > 0)
                    Text("${res.conflicts} group rule(s) contradicted each other and were left out.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                if (main.isNotEmpty()) LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(main.take(100)) { m ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(m.name, style = MaterialTheme.typography.bodyMedium)
                            Text("#${m.from + 1} → #${m.to + 1} · ${m.why}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (main.size > 100) item { Text("…and ${main.size - 100} more", style = MaterialTheme.typography.bodySmall) }
                }
                Text("Plugins the lists don't know keep their order. This uses LOOT's groups and rules only; it doesn't compare plugin records like the full LOOT does.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        },
        confirmButton = { if (res.moved.isNotEmpty()) TextButton(onClick = onApply) { Text("Apply") } else TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = { if (res.moved.isNotEmpty()) TextButton(onClick = onClose) { Text("Cancel") } })
}

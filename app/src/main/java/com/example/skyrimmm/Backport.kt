package com.example.skyrimmm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File

/** The outcome of a Special Edition to Legendary Edition conversion: the converted zip and the checks that were run on it. */
class ConvResult(val file: Uri, val report: String, val passed: Int, val warns: Int, val fails: Int, val missing: List<String>, val src: Uri? = null)

/** Runs the bundled SE2LE converter (package com.se2le) on an archive and hands the result to the normal install. */
object Backport {
    val CAPS = intArrayOf(0, 4096, 2048, 1024, 512)
    val CAP_LABELS = listOf("No limit (keep original size)", "4096 px", "2048 px", "1024 px", "512 px")

    fun clear(ctx: Context) { File(ctx.cacheDir, "se2le").deleteRecursively() }

    /** Converts `src` into "<name> (LE).zip" in the app's cache and returns it as a content link the install queue can read. */
    fun convert(ctx: Context, src: Uri, cap: Int): ConvResult {
        clear(ctx)
        val dir = File(ctx.cacheDir, "se2le").apply { mkdirs() }
        val base = (DocumentFile.fromSingleUri(ctx, src)?.name ?: "mod")
            .replace(Regex("(?i)\\.(zip|7z|rar|tar\\.gz|tgz|tar\\.bz2|tar\\.xz|tar|gz|bz2|xz)$"), "")
        val safe = base.replace(Regex("[^A-Za-z0-9 ._()\\-]"), "_").trim().take(80).ifEmpty { "mod" }
        val input = File(dir, "input.bin")
        val out = File(dir, "$safe (LE).zip")
        val rep = com.se2le.Report()
        rep.maxTex = cap
        rep.modName = base
        rep.embedReport = false // the report is shown in the app and kept with the mod, not installed into Data
        rep.onProgress = { n -> Progress.report("Converting to Legendary Edition", n, null) }
        Progress.report("Converting to Legendary Edition", "Copying the archive…", null, force = true)
        try {
            ctx.contentResolver.openInputStream(src)!!.use { i -> input.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            out.outputStream().use { o -> com.se2le.Converter.run(input, o, rep) }
        } catch (t: Throwable) {
            out.delete(); throw t
        } finally { input.delete() }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
        return ConvResult(uri, rep.text(),
            rep.checks.count { it.first == "PASS" }, rep.checks.count { it.first == "WARN" }, rep.checks.count { it.first == "FAIL" }, rep.missing.toList(), src)
    }
}

private fun copyText(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Conversion report", text))
}

/** Asks for the options before converting (only the texture size limit for now). */
@Composable
fun ConvertOptionsDialog(initial: Int, onStart: (Int) -> Unit, onClose: () -> Unit) {
    var cap by remember { mutableStateOf(initial.coerceIn(0, Backport.CAPS.size - 1)) }
    AlertDialog(
        onDismissRequest = onClose, title = { Text("Convert to Legendary Edition") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("The mod is converted on your phone and then installed like any other mod. Large mods can take several minutes, so keep the app open.")
                Text("Largest texture size", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Backport.CAP_LABELS.forEachIndexed { i, label ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { cap = i }) {
                        RadioButton(selected = cap == i, onClick = { cap = i })
                        Text(label)
                    }
                }
                Text("Smaller textures use less memory and storage in the game. Textures already below the limit are left alone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Text("Converting is for your own use. Respect the mod author's permissions, and don't share converted files without them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onStart(cap) }) { Text("Convert") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

/** Shows what the conversion did and found, then lets the person install the converted mod or drop it. */
@Composable
fun ConvertDoneDialog(res: ConvResult, onInstall: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onCancel, title = { Text("Conversion finished") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${res.passed} check(s) passed, ${res.warns} warning(s), ${res.fails} failed.", fontWeight = FontWeight.Medium)
                if (res.missing.isNotEmpty())
                    Text("These masters do not exist in Skyrim LE: ${res.missing.joinToString()}. Records that use them will not work.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text(res.report, style = MaterialTheme.typography.bodySmall, modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()))
                TextButton(onClick = { copyText(ctx, res.report) }) { Text("Copy report") }
            }
        },
        confirmButton = { TextButton(onClick = onInstall) { Text("Install") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}

/** A saved conversion report, opened from the mod's menu. */
@Composable
fun ReportDialog(title: String, text: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose, title = { Text("Conversion report: $title") },
        text = { Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = { TextButton(onClick = { copyText(ctx, text) }) { Text("Copy") } })
}

package com.example.skyrimmm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

data class OrderFiles(val plugins: Uri?, val loadOrder: Uri?, val dir: DocumentFile?)

data class PluginEntry(val name: String, val active: Boolean)

data class Mod(
    val id: String, val name: String,
    val files: List<String>,            // every file the mod wrote (relative to Data/)
    val dirs: List<String>,             // directories the mod created
    val backups: Map<String, String>,   // files it overwrote -> backup file name
    val enabled: Boolean = true,
    val size: Long = -1                 // bytes the mod wrote, -1 = not measured yet
) {
    val plugins get() = files.filter { it.substringAfterLast('.').lowercase() in setOf("esp", "esm", "esl") }
}

class ModManager(private val ctx: Context) {
    private val store = File(ctx.filesDir, "mods").apply { mkdirs() }
    private val backupRoot = File(ctx.filesDir, "backups").apply { mkdirs() }
    private val cr = ctx.contentResolver
    private val known = setOf("meshes", "textures", "scripts", "sound", "interface", "skse", "strings",
        "seq", "music", "video", "grass", "lodsettings", "shadersfx", "facegen", "source")

    fun list(): List<Mod> = store.listFiles { f -> f.extension == "json" }.orEmpty()
        .map { load(it) }.sortedBy { it.name.lowercase() }

    private fun segs(p: String) = p.replace('\\', '/').split('/').filter { it.isNotEmpty() }

    private fun mapPath(raw: String, hasData: Boolean, wrap: Boolean): List<String>? {
        val s = segs(raw)
        if (s.any { it == ".." }) return null // zip-slip protection
        val rel = when {
            hasData -> {
                val i = s.indexOfFirst { it.equals("data", true) }
                if (i in 0..1 && i < s.size - 1) s.drop(i + 1) else return null
            }
            wrap -> s.drop(1)
            else -> s
        }
        return rel.takeIf { it.isNotEmpty() && !it.first().equals("fomod", true) }
    }

    private fun newFile(dir: DocumentFile, name: String): DocumentFile? =
        if (dir.uri.scheme == "file") DocumentFile.fromFile(File(dir.uri.path!!, name).also { it.createNewFile() })
        else dir.createFile("application/octet-stream", name)

    private fun norm(p: String) = segs(p).joinToString("/").lowercase()
    private fun zipName(u: Uri) = (DocumentFile.fromSingleUri(ctx, u)?.name ?: "mod").let { n -> if (n.substringAfterLast('.', "").lowercase() in setOf("zip", "7z", "rar")) n.substringBeforeLast('.') else n }

    private fun entryNames(zipUri: Uri): List<String> = openArchive(ctx, zipUri).use { it.names() }

    /** Best-effort reasons a mod needs SKSE (empty list = no sign of it). Checks files, compiled scripts, the FOMOD config and readmes. */
    fun skseReasons(zipUri: Uri): List<String> {
        Progress.report("Reading archive", "Checking for SKSE…", null, force = true)
        val reasons = mutableListOf<String>()
        openArchive(ctx, zipUri).use { arch ->
            arch.names().firstOrNull { n ->
                val s = segs(n).map { it.lowercase() }
                "skse" in s.dropLast(1) || s.last().let { it.startsWith("skse") && (it.endsWith(".dll") || it.endsWith(".exe")) }
            }?.let { reasons += "It ships SKSE plugin files (${segs(it).takeLast(2).joinToString("/")})" }
            var pex = 0; var scripts = false; var installer = false; var readme = false
            arch.forEach { n, s ->
                val low = n.lowercase()
                val file = segs(n).lastOrNull().orEmpty()
                when {
                    !scripts && low.endsWith(".pex") && pex++ < 300 -> {
                        val txt = String(s.readUpTo(400_000), Charsets.ISO_8859_1)
                        SKSE_TERMS.firstOrNull { it in txt }?.let { reasons += "Its scripts ($file) use SKSE-only features (found: $it)"; scripts = true }
                    }
                    !installer && low.endsWith("moduleconfig.xml") -> {
                        if ("skse" in decodeText(s.readUpTo(500_000)).lowercase()) { reasons += "Its installer (ModuleConfig.xml) mentions SKSE"; installer = true }
                    }
                    !readme && (low.endsWith(".txt") || low.endsWith(".md") || low.endsWith(".rtf")) && !low.endsWith("plugins.txt") -> {
                        if (readmeNeedsSkse(decodeText(s.readUpTo(200_000)))) { reasons += "A readme ($file) says it requires SKSE"; readme = true }
                    }
                }
                !(scripts && installer && readme)
            }
        }
        return reasons
    }

    /** Which of the given paths (relative to Data) exist, normalised with normPath. */
    fun existing(dataUri: Uri, paths: Set<String>): Set<String> {
        val idx = runCatching { Idx(dataRoot(dataUri)) }.getOrNull() ?: return emptySet()
        return paths.filter { p ->
            val s = segs(p)
            s.isNotEmpty() && idx.children(s.dropLast(1))?.containsKey(s.last().lowercase()) == true
        }.map { normPath(it) }.toSet()
    }

    /** Thumbnails for the wizard's option images, keyed by the image path written in the config. */
    fun loadImages(zipUri: Uri, pack: FomodPack): Map<String, Bitmap> {
        val want = pack.config.steps.flatMap { it.groups }.flatMap { it.plugins }.map { it.image }
            .filter { it.isNotBlank() }.associateBy { normPath(pack.prefix + "/" + it) }
        if (want.isEmpty()) return emptyMap()
        Progress.report("Reading archive", "Loading option images…", null, force = true)
        val out = mutableMapOf<String, Bitmap>()
        openArchive(ctx, zipUri).use { a -> a.forEach { n, s ->
            val key = want[normPath(n)]
            if (key != null) {
                val b = s.readBytes()
                if (b.size <= 3_000_000)
                    BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = 2 })?.let { out[key] = it }
            }
            true
        } }
        return out
    }

    /** Returns the parsed FOMOD installer if the zip has one, otherwise null. */
    fun readFomod(zipUri: Uri): FomodPack? {
        val names = entryNames(zipUri)
        val cfg = names.firstOrNull { norm(it).endsWith("fomod/moduleconfig.xml") } ?: return null
        var xml: ByteArray? = null
        openArchive(ctx, zipUri).use { a -> a.forEach { n, s -> if (n == cfg) { xml = s.readBytes(); false } else true } }
        return FomodPack(parseFomod(xml!!), segs(cfg).dropLast(2).joinToString("/"), names)
    }

    fun install(dataUri: Uri, zipUri: Uri): Mod {
        val names = entryNames(zipUri)
        if (names.isEmpty()) error("Empty zip")
        val hasData = names.any { n -> segs(n).let { s -> s.indexOfFirst { it.equals("data", true) }.let { it in 0..1 && it < s.size - 1 } } }
        val firsts = names.map { segs(it).firstOrNull()?.lowercase() }.toSet()
        val wrap = !hasData && firsts.size == 1 && names.all { segs(it).size > 1 } && firsts.first() !in known
        return extract(dataUri, zipUri, zipName(zipUri)) { n -> mapPath(n, hasData, wrap)?.let { listOf(it) } ?: emptyList() }
    }

    /** Installs the files a FOMOD wizard selected, honouring source -> destination mappings and priorities. */
    fun installFomod(dataUri: Uri, zipUri: Uri, pack: FomodPack, chosen: List<FFile>): Mod {
        val pre = segs(pack.prefix)
        class Pick(val dst: List<String>, val src: String, val pri: Int)
        val best = mutableMapOf<String, Pick>()
        fun offer(dst: List<String>, src: String, pri: Int) {
            if (dst.isEmpty() || dst.any { it == ".." }) return
            val k = dst.joinToString("/").lowercase()
            if (best[k] == null || pri >= best[k]!!.pri) best[k] = Pick(dst, src, pri)
        }
        for (f in chosen) {
            val src = pre + segs(f.src); val sn = src.joinToString("/").lowercase()
            if (f.folder) {
                for (n in pack.names) {
                    val s = segs(n)
                    if (s.size > src.size && s.take(src.size).joinToString("/").lowercase() == sn)
                        offer(segs(f.dst) + s.drop(src.size), n, f.priority)
                }
            } else pack.names.firstOrNull { norm(it) == sn }?.let { n ->
                val d = when {
                    f.dst.isEmpty() -> segs(f.src)
                    f.dst.endsWith("/") || f.dst.endsWith("\\") -> segs(f.dst) + segs(f.src).last()
                    else -> segs(f.dst)
                }
                offer(d, n, f.priority)
            }
        }
        val plan = best.values.groupBy({ it.src }, { it.dst })
        return extract(dataUri, zipUri, zipName(zipUri)) { n -> plan[n].orEmpty() }
    }

    private fun extract(dataUri: Uri, zipUri: Uri, name: String, plan: (String) -> List<List<String>>): Mod {
        val idx = Idx(dataRoot(dataUri))
        val id = UUID.randomUUID().toString().take(8)
        val bdir = File(backupRoot, id).apply { mkdirs() }
        val files = mutableListOf<String>(); val dirs = mutableListOf<String>()
        val backups = mutableMapOf<String, String>()
        var bytes = 0L
        try {
            Progress.report("Installing $name", "Preparing…", null, force = true)
            openArchive(ctx, zipUri).use { arch ->
                var n = 0; var done = 0
                val total = arch.names().count { plan(it).isNotEmpty() }.coerceAtLeast(1)
                arch.forEach { entryName, z ->
                    var first: DocumentFile? = null
                    for (rel in plan(entryName)) {
                        val parent = rel.dropLast(1)
                        val dir = idx.dir(parent, dirs) ?: error("Cannot create folder ${parent.joinToString("/")}")
                        val kids = idx.children(parent)!!
                        val fname = rel.last(); val relStr = rel.joinToString("/")
                        kids.remove(fname.lowercase())?.let { old ->
                            val b = "${n++}.bak"
                            cr.openInputStream(old.uri)!!.use { i -> File(bdir, b).outputStream().use { i.copyTo(it, BUF) } }
                            backups[relStr] = b
                            old.delete()
                        }
                        val out = newFile(dir, fname) ?: error("Cannot create $fname")
                        kids[fname.lowercase()] = out
                        files += relStr
                        if (first == null) { bytes += cr.openOutputStream(out.uri)!!.use { z.copyTo(it, BUF) }; first = out }
                        else bytes += cr.openInputStream(first.uri)!!.use { i -> cr.openOutputStream(out.uri)!!.use { i.copyTo(it, BUF) } }
                    }
                    if (first != null) { done++; Progress.report("Installing $name", files.lastOrNull().orEmpty(), done.toFloat() / total) }
                    true
                }
            }
            if (files.isEmpty()) error("No installable files found in archive")
        } catch (t: Throwable) {
            uninstall(dataUri, Mod(id, name, files, dirs, backups)); throw t
        } finally { Stage.clear(ctx) }
        return Mod(id, name, files, dirs, backups, true, bytes).also { save(it) }
    }

    /** Removes every file the mod wrote, restores anything it overwrote, and prunes folders it created. */
    fun uninstall(dataUri: Uri, mod: Mod) {
        var done = 0
        Progress.report("Uninstalling ${mod.name}", "Preparing…", null, force = true)
        val idx = Idx(dataRoot(dataUri))
        val bdir = File(backupRoot, mod.id)
        for ((parent, group) in mod.files.groupBy { segs(it).dropLast(1) }) {
            val dir = idx.dir(parent, if (group.any { it in mod.backups }) mutableListOf() else null) ?: continue
            val kids = idx.children(parent) ?: continue
            for (f in group) {
                val fname = segs(f).last(); val key = fname.lowercase()
                Progress.report("Uninstalling ${mod.name}", f, (++done).toFloat() / mod.files.size)
                kids.remove(key)?.delete()
                kids.remove("$key.disabled")?.delete()
                mod.backups[f]?.let { b ->
                    val out = newFile(dir, fname) ?: return@let
                    kids[key] = out
                    File(bdir, b).inputStream().use { i -> cr.openOutputStream(out.uri)!!.use { i.copyTo(it, BUF) } }
                }
            }
        }
        mod.dirs.sortedByDescending { segs(it).size }.forEach { d ->
            val p = segs(d)
            if (idx.children(p)?.isEmpty() == true) idx.removeDir(p)
        }
        bdir.deleteRecursively(); File(store, "${mod.id}.json").delete()
    }

    /** True when the app has All files access, so shared-storage folders can be used as plain files (far faster than SAF). */
    fun fastAccess() = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    private fun rootOf(uri: Uri): DocumentFile? {
        if (fastAccess() && uri.authority == "com.android.externalstorage.documents") {
            val id = DocumentsContract.getTreeDocumentId(uri)
            val vol = id.substringBefore(':')
            val base = if (vol == "primary") Environment.getExternalStorageDirectory() else File("/storage/$vol")
            val f = File(base, id.substringAfter(':', ""))
            if (f.isDirectory) return DocumentFile.fromFile(f)
        }
        return DocumentFile.fromTreeUri(ctx, uri)
    }

    /** The selected folder may be the game folder (containing Data) or the Data folder itself. */
    private fun dataRoot(uri: Uri): DocumentFile {
        val r = rootOf(uri) ?: error("Invalid folder")
        if (r.name.equals("data", true)) return r
        return r.listFiles().firstOrNull { it.isDirectory && it.name.equals("data", true) }
            ?: error("No Data folder found in the selected folder")
    }

    /** <game root>/port/save/AppData/Local/Skyrim, where the Skyrim Android port keeps Plugins.txt and loadorder.txt. */
    fun findPortDir(dataUri: Uri): DocumentFile? {
        val root = rootOf(dataUri) ?: return null
        return Idx(root).dir(listOf("port", "save", "AppData", "Local", "Skyrim"))
    }

    private fun childFile(dir: DocumentFile, name: String) = dir.listFiles().firstOrNull { it.isFile && it.name.equals(name, true) }

    fun describe(dataUri: Uri): String {
        dataRoot(dataUri)
        return if (findPortDir(dataUri) != null) "Folder set. Data folder found, and the port folder was detected, so plugins sync automatically."
        else "Folder set. Data folder found. No port folder detected; pick plugins.txt on the Load order tab."
    }

    fun clearStage() = Stage.clear(ctx)

    /** Port folder files win when the port folder exists; otherwise the manually selected plugins.txt is used. */
    fun orderFiles(dataUri: Uri?, manual: Uri?): OrderFiles {
        val dir = dataUri?.let { findPortDir(it) }
        return when {
            dir != null -> OrderFiles(childFile(dir, "plugins.txt")?.uri, childFile(dir, "loadorder.txt")?.uri, dir)
            manual != null -> OrderFiles(manual, null, null)
            else -> OrderFiles(null, null, null)
        }
    }

    /** loadorder.txt gives the full order (inactive plugins too); plugins.txt says which are active. */
    fun readOrder(f: OrderFiles): Pair<Boolean, List<PluginEntry>> {
        val (star, active) = f.plugins?.let { readPlugins(it) } ?: (false to emptyList<PluginEntry>())
        val order = f.loadOrder?.let { u ->
            cr.openInputStream(u)!!.use { it.readBytes() }.toString(Charsets.UTF_8).removePrefix("\uFEFF").lines()
                .map { it.trim().removePrefix("*") }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinctBy { it.lowercase() }
        }.orEmpty()
        if (order.isEmpty()) return star to active
        val on = active.filter { it.active }.map { it.name.lowercase() }.toSet()
        val have = order.map { it.lowercase() }.toSet()
        return star to (order.map { PluginEntry(it, it.lowercase() in on) } + active.filter { it.name.lowercase() !in have })
    }

    /** Copies the current plugins.txt / loadorder.txt into "SkyrimMM Backups/Load order" in the game folder before they are overwritten. */
    private fun backupOrder(dataUri: Uri?, f: OrderFiles): String {
        val srcs = listOfNotNull(f.plugins?.let { "Plugins" to it }, f.loadOrder?.let { "loadorder" to it })
        if (srcs.isEmpty()) return "No previous files to back up."
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = dataUri?.let { rootOf(it) }?.let { Idx(it).dir(listOf("SkyrimMM Backups", "Load order"), mutableListOf()) }
        for ((label, uri) in srcs) {
            val bytes = cr.openInputStream(uri)!!.use { it.readBytes() }
            val name = "$label-$stamp.txt"
            if (dir != null) {
                val out = newFile(dir, name) ?: error("Cannot create the backup file")
                cr.openOutputStream(out.uri, "wt")!!.use { it.write(bytes) }
            } else File(File(ctx.filesDir, "order_backups").apply { mkdirs() }, name).writeBytes(bytes)
        }
        dir?.let { d -> // keep the newest 10 backups of each file
            for (label in listOf("Plugins-", "loadorder-"))
                d.listFiles().filter { it.name.orEmpty().startsWith(label) }.sortedBy { it.name }.dropLast(10).forEach { it.delete() }
        }
        return if (dir != null) "Backup saved in SkyrimMM Backups/Load order." else "Backup saved inside the app only."
    }

    fun writeOrder(f: OrderFiles, star: Boolean, list: List<PluginEntry>, dataUri: Uri? = null): String {
        val note = backupOrder(dataUri, f) // if the backup fails this throws and nothing is overwritten
        val p = f.plugins ?: f.dir?.let { newFile(it, "Plugins.txt") }?.uri ?: error("No plugins.txt selected")
        writePlugins(p, star, list)
        f.dir?.let { d ->
            val lo = f.loadOrder ?: newFile(d, "loadorder.txt")?.uri ?: error("Cannot create loadorder.txt")
            cr.openOutputStream(lo, "wt")!!.use { it.write((list.joinToString("\r\n") { e -> e.name } + "\r\n").toByteArray(Charsets.UTF_8)) }
        }
        return note
    }

    /** Works out the size of a mod installed before sizes were recorded (reads fresh data, so it can't undo a toggle). */
    fun measure(dataUri: Uri, mod: Mod): Mod {
        val idx = Idx(dataRoot(dataUri))
        var total = 0L
        for ((parent, group) in mod.files.groupBy { segs(it).dropLast(1) }) {
            val kids = idx.children(parent) ?: continue
            for (f in group) {
                val n = segs(f).last().lowercase()
                total += (kids[n] ?: kids["$n.disabled"])?.length() ?: 0L
            }
        }
        val cur = list().firstOrNull { it.id == mod.id } ?: return mod
        return cur.copy(size = total).also { save(it) }
    }

    /** Disables a mod by renaming its files to *.disabled (restoring anything it overwrote), or re-enables it. */
    fun setEnabled(dataUri: Uri, mod: Mod, enable: Boolean): Mod {
        if (mod.enabled == enable) return mod
        var done = 0
        val title = (if (enable) "Enabling " else "Disabling ") + mod.name
        Progress.report(title, "Preparing…", null, force = true)
        val idx = Idx(dataRoot(dataUri))
        val bdir = File(backupRoot, mod.id)
        for ((parent, group) in mod.files.groupBy { segs(it).dropLast(1) }) {
            val dir = idx.dir(parent) ?: continue
            val kids = idx.children(parent) ?: continue
            for (f in group) {
                val name = segs(f).last(); val key = name.lowercase()
                Progress.report(title, f, (++done).toFloat() / mod.files.size)
                if (!enable) {
                    val file = kids.remove(key) ?: continue
                    if (!file.renameTo("$name.disabled")) error("Cannot rename $name")
                    kids["$key.disabled"] = file
                    mod.backups[f]?.let { b ->
                        newFile(dir, name)?.let { out ->
                            kids[key] = out
                            File(bdir, b).inputStream().use { i -> cr.openOutputStream(out.uri)!!.use { i.copyTo(it, BUF) } }
                        }
                    }
                } else {
                    val off = kids.remove("$key.disabled") ?: continue
                    mod.backups[f]?.let { b ->
                        kids.remove(key)?.let { cur ->
                            cr.openInputStream(cur.uri)!!.use { i -> File(bdir, b).outputStream().use { i.copyTo(it, BUF) } }
                            cur.delete()
                        }
                    }
                    if (!off.renameTo(name)) error("Cannot rename $name")
                    kids[key] = off
                }
            }
        }
        return mod.copy(enabled = enable).also { save(it) }
    }

    /** Reads plugins.txt. Returns (usesAsterisks, entries): LE lists only active plugins in load order; SE-style marks active ones with *. */
    fun readPlugins(uri: Uri): Pair<Boolean, List<PluginEntry>> {
        val text = cr.openInputStream(uri)!!.use { it.readBytes() }.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val star = lines.any { it.startsWith("*") }
        return star to lines.map { PluginEntry(it.removePrefix("*"), !star || it.startsWith("*")) }.distinctBy { it.name.lowercase() }
    }

    /** Writes plugins.txt in the same style it was read in, keeping a backup copy of the previous file. */
    fun writePlugins(uri: Uri, star: Boolean, list: List<PluginEntry>) {
        cr.openInputStream(uri)?.use { File(ctx.filesDir, "plugins.txt.bak").writeBytes(it.readBytes()) }
        val out = if (star) list.map { (if (it.active) "*" else "") + it.name } else list.filter { it.active }.map { it.name }
        cr.openOutputStream(uri, "wt")!!.use { it.write((out.joinToString("\r\n") + "\r\n").toByteArray(Charsets.UTF_8)) }
    }

    private fun save(m: Mod) = File(store, "${m.id}.json").writeText(
        JSONObject().put("id", m.id).put("name", m.name).put("files", JSONArray(m.files))
            .put("dirs", JSONArray(m.dirs)).put("backups", JSONObject(m.backups)).put("enabled", m.enabled).put("size", m.size).toString())

    private fun load(f: File): Mod {
        val o = JSONObject(f.readText())
        fun JSONArray.strs() = List(length()) { getString(it) }
        val b = o.getJSONObject("backups")
        return Mod(o.getString("id"), o.getString("name"), o.getJSONArray("files").strs(),
            o.getJSONArray("dirs").strs(), b.keys().asSequence().associateWith { b.getString(it) }, o.optBoolean("enabled", true), o.optLong("size", -1))
    }
}

private const val BUF = 1 shl 16

/** Cached view of the Data tree: every folder is listed at most once, which matters a lot over SAF. */
private class Idx(val root: DocumentFile) {
    private val dirs = HashMap<String, DocumentFile>()
    private val kids = HashMap<String, MutableMap<String, DocumentFile>>()
    private fun key(p: List<String>) = p.joinToString("/").lowercase()
    private fun kidsOf(p: List<String>, d: DocumentFile) =
        kids.getOrPut(key(p)) { d.listFiles().associateByTo(HashMap()) { it.name.orEmpty().lowercase() } }

    /** Finds a folder; when `created` is given, missing folders are created and their paths recorded in it. */
    fun dir(p: List<String>, created: MutableList<String>? = null): DocumentFile? {
        if (p.isEmpty()) return root
        dirs[key(p)]?.let { return it }
        val parentPath = p.dropLast(1)
        val parent = dir(parentPath, created) ?: return null
        val siblings = kidsOf(parentPath, parent)
        var d = siblings[p.last().lowercase()]?.takeIf { it.isDirectory }
        if (d == null && created != null) {
            d = parent.createDirectory(p.last()) ?: error("Cannot create folder ${p.last()}")
            siblings[p.last().lowercase()] = d
            kids[key(p)] = HashMap()
            created += p.joinToString("/")
        }
        return d?.also { dirs[key(p)] = it }
    }

    /** The (editable) name -> file listing of a folder, or null if the folder doesn't exist. */
    fun children(p: List<String>): MutableMap<String, DocumentFile>? = dir(p)?.let { kidsOf(p, it) }

    fun removeDir(p: List<String>) {
        val d = dir(p) ?: return
        d.delete()
        dirs.remove(key(p)); kids.remove(key(p))
        kids[key(p.dropLast(1))]?.remove(p.last().lowercase())
    }
}

private val SKSE_TERMS = listOf("SKSE", "ModEvent", "StringUtil", "NetImmerse", "UICallback", "SKI_ConfigBase", "StorageUtil",
    "JsonUtil", "MiscUtil", "ActorUtil", "ConsoleUtil", "JContainers", "JValue", "PO3_SKSEFunctions", "PapyrusUtil")
private val SKSE_MENTION = Regex("skse|script extender")
private val NEEDS_WORDS = Regex("requir|need|depend|must|mandatory")
private val NOT_NEEDED = Regex("optional|not required|n't require|no need|without|not need|n't need")

/** True if a readme mentions SKSE close to words like "requires" (and not "optional" or "not required"). */
private fun readmeNeedsSkse(text: String): Boolean {
    val t = text.lowercase()
    return SKSE_MENTION.findAll(t).any { m ->
        val w = t.substring(maxOf(0, m.range.first - 80), minOf(t.length, m.range.last + 81))
        NEEDS_WORDS.containsMatchIn(w) && !NOT_NEEDED.containsMatchIn(w)
    }
}

private fun InputStream.readUpTo(max: Int): ByteArray {
    val out = ByteArrayOutputStream(); val buf = ByteArray(8192); var left = max
    while (left > 0) { val n = read(buf, 0, minOf(buf.size, left)); if (n < 0) break; out.write(buf, 0, n); left -= n }
    return out.toByteArray()
}

private fun decodeText(b: ByteArray): String = when {
    b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, Charsets.UTF_16LE)
    b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, Charsets.UTF_16BE)
    else -> String(b, Charsets.UTF_8)
}

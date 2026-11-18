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

enum class Sync { ACTIVATE, RESTORE, DEACTIVATE, REMOVE }

data class PluginEntry(val name: String, val active: Boolean)

data class Mod(
    val id: String, val name: String,
    val files: List<String>,            // every file the mod wrote (relative to Data/)
    val dirs: List<String>,             // directories the mod created
    val backups: Map<String, String>,   // files it overwrote -> backup file name
    val enabled: Boolean = true,
    val size: Long = -1,                // bytes the mod wrote, -1 = not measured yet
    val time: Long = 0,                 // when it was installed (decides who wins a file conflict)
    val wasActive: List<String> = emptyList(), // plugins that were active when the mod was disabled
    val version: String = "", val note: String = "", val link: String = "",
    val fsizes: Map<String, Long> = emptyMap() // size of every file as installed, for verification
) {
    val plugins get() = files.filter { it.substringAfterLast('.').lowercase() in setOf("esp", "esm", "esl") }
}

class ProfMod(val name: String, val version: String, val link: String)
class Profile(val id: String, val name: String, val enabled: List<String>, val order: List<PluginEntry>, val mods: List<ProfMod>)
class Trouble(val phase: String, val original: List<String>, val cands: List<String>, val good: List<String>, val testing: List<String>)

/** A set of files in Data that no installed mod owns (a plugin with its archives, or the loose files of one folder). */
class Unmanaged(val label: String, val files: List<String>, val size: Long, val plugin: Boolean)

/** The files of an archive that were found in Data with the same size, ready to be recorded as an installed mod. */
class AdoptPlan(val name: String, val files: List<String>, val sizes: Map<String, Long>, val total: Int)

class ModManager(private val ctx: Context) {
    private val store = File(ctx.filesDir, "mods").apply { mkdirs() }
    private val backupRoot = File(ctx.filesDir, "backups").apply { mkdirs() }
    private val reportDir = File(ctx.filesDir, "reports").apply { mkdirs() }

    /** Reports of archives the SE to LE converter made, keyed by the converted file's uri; the install that reads that file keeps its report. */
    val conversions = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun hasConversionReport(id: String) = File(reportDir, "$id.md").exists()
    fun conversionReport(id: String): String? = File(reportDir, "$id.md").takeIf { it.exists() }?.readText()
    private val cr = ctx.contentResolver
    private val known = setOf("meshes", "textures", "scripts", "sound", "interface", "skse", "strings",
        "seq", "music", "video", "grass", "lodsettings", "shadersfx", "facegen", "source")

    fun list(): List<Mod> = store.listFiles { f -> f.extension == "json" }.orEmpty()
        .map { f -> load(f).let { m -> if (m.time == 0L) m.copy(time = f.lastModified()).also { save(it) } else m } } // older mods: estimate from the record
        .sortedBy { it.name.lowercase() }

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
    private fun zipName(u: Uri) = (DocumentFile.fromSingleUri(ctx, u)?.name ?: "mod").let { n -> if (n.substringAfterLast('.', "").lowercase() in setOf("zip", "7z", "rar", "esp", "esm", "esl")) n.substringBeforeLast('.') else n }

    private fun entryNames(zipUri: Uri): List<String> = openArchive(ctx, zipUri).use { it.names() }

    /**
     * One pass over an archive looking for two kinds of trouble. Returns (SKSE reasons, Special Edition reasons); empty lists mean no sign of it.
     * SKSE: plugin files, compiled scripts, the FOMOD config, readmes. SE: plugin headers and the ESL flag, BSA version, 64-bit SKSE DLLs,
     * SE mesh format and BC7 textures. All best-effort guesses.
     */
    fun scanCompat(zipUri: Uri): Pair<List<String>, List<String>> {
        Progress.report("Reading archive", "Checking compatibility…", null, force = true)
        val skse = mutableListOf<String>(); val se = mutableListOf<String>()
        openArchive(ctx, zipUri).use { arch ->
            arch.names().firstOrNull { n ->
                val s = segs(n).map { it.lowercase() }
                "skse" in s.dropLast(1) || s.last().let { it.startsWith("skse") && (it.endsWith(".dll") || it.endsWith(".exe")) }
            }?.let { skse += "It ships SKSE plugin files (${segs(it).takeLast(2).joinToString("/")})" }
            var pex = 0; var scripts = false; var installer = false; var readme = false
            var plugins = 0; var bsas = 0; var nifs = 0; var ddss = 0
            var sePlugin = false; var seBsa = false; var seDll = false; var seNif = false; var seDds = false
            arch.forEach { n, s ->
                val low = n.lowercase()
                val file = segs(n).lastOrNull().orEmpty()
                val dirs = segs(n).dropLast(1).map { it.lowercase() }
                when {
                    !scripts && low.endsWith(".pex") && pex++ < 300 -> {
                        val txt = String(s.readUpTo(400_000), Charsets.ISO_8859_1)
                        SKSE_TERMS.firstOrNull { it in txt }?.let { skse += "Its scripts ($file) use SKSE-only features (found: $it)"; scripts = true }
                    }
                    !installer && low.endsWith("moduleconfig.xml") -> {
                        if ("skse" in decodeText(s.readUpTo(500_000)).lowercase()) { skse += "Its installer (ModuleConfig.xml) mentions SKSE"; installer = true }
                    }
                    !readme && (low.endsWith(".txt") || low.endsWith(".md") || low.endsWith(".rtf")) && !low.endsWith("plugins.txt") -> {
                        if (readmeNeedsSkse(decodeText(s.readUpTo(200_000)))) { skse += "A readme ($file) says it requires SKSE"; readme = true }
                    }
                    !sePlugin && (low.endsWith(".esp") || low.endsWith(".esm") || low.endsWith(".esl")) && plugins++ < 20 -> {
                        // The ESL flag and a form version of 44 or higher count. The header version (such as 1.70) does not:
                        // it is common in plugins that work fine in LE.
                        parsePluginHeader(file, s)?.let { info ->
                            when {
                                info.isLight || low.endsWith(".esl") -> {
                                    se += "Plugin $file is a light (ESL) plugin, which only Special Edition supports"; sePlugin = true
                                }
                                info.formVersion >= 44 -> {
                                    se += "Plugin $file was saved for Special Edition (form version ${info.formVersion}; Legendary Edition uses 43)"; sePlugin = true
                                }
                                else -> {}
                            }
                        }
                    }
                    !seBsa && low.endsWith(".bsa") && bsas++ < 5 -> {
                        val h = s.readUpTo(8)
                        if (h.size >= 8 && h[0] == 'B'.code.toByte() && h[1] == 'S'.code.toByte() && h[2] == 'A'.code.toByte() && u32le(h, 4) == 105) {
                            se += "Archive $file uses the Special Edition BSA format"; seBsa = true
                        }
                    }
                    !seDll && low.endsWith(".dll") && "skse" in dirs -> {
                        if (peMachine(s.readUpTo(1024)) == 0x8664) { se += "SKSE plugin $file is a 64-bit DLL (SKSE64, Special Edition)"; seDll = true }
                    }
                    !seNif && low.endsWith(".nif") && nifs++ < 6 -> {
                        if (nifBsVersion(s.readUpTo(128)) == 100) { se += "Mesh $file uses the Special Edition format"; seNif = true }
                    }
                    !seDds && low.endsWith(".dds") && ddss++ < 12 -> {
                        val dxgi = ddsDxgi(s.readUpTo(148))
                        if (dxgi == 98 || dxgi == 99) { se += "Texture $file uses BC7 compression, which Legendary Edition cannot read"; seDds = true }
                    }
                }
                true
            }
        }
        return skse to se
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
        val names = try { entryNames(zipUri) } catch (t: Throwable) { throw friendly(t) }
        if (names.isEmpty()) error("Empty zip")
        detectRoot(names)?.let { base -> // the mod sits inside one or more wrapper folders: that folder is the real Data folder
            val skipped = names.count { stripBase(it, base).isEmpty() }
            addNote("Used the archive folder \"${base.joinToString("/")}\" as the Data folder" + (if (skipped > 0) "; skipped $skipped other file(s) (readmes or other options)." else "."))
            return extract(dataUri, zipUri, zipName(zipUri)) { n -> stripBase(n, base) }
        }
        val hasData = names.any { n -> segs(n).let { s -> s.indexOfFirst { it.equals("data", true) }.let { it in 0..1 && it < s.size - 1 } } }
        val firsts = names.map { segs(it).firstOrNull()?.lowercase() }.toSet()
        val wrap = !hasData && firsts.size == 1 && names.all { segs(it).size > 1 } && firsts.first() !in known
        return extract(dataUri, zipUri, zipName(zipUri)) { n -> mapPath(n, hasData, wrap)?.let { listOf(it) } ?: emptyList() }
    }

    /**
     * How many leading folders of an archive path to drop so that what is left starts at the Data folder: just past a folder named Data, at the
     * first game folder (meshes, textures, scripts…), or at a plugin or BSA file. Null when the path shows no sign of where the content starts.
     */
    private fun contentDepth(s: List<String>): Int? {
        for (i in s.indices) {
            val last = i == s.size - 1
            val low = s[i].lowercase()
            if (!last && low == "data") return i + 1
            if (!last && low in known) return i
            if (last && low.substringAfterLast('.', "") in setOf("esp", "esm", "esl", "bsa")) return i
        }
        return null
    }

    /** The wrapper folders to strip (the most common place where content starts), or null when the content is already at the top of the archive. */
    private fun detectRoot(names: List<String>): List<String>? {
        val counts = HashMap<String, Int>(); val first = HashMap<String, List<String>>()
        for (n in names) {
            val s = segs(n)
            val d = contentDepth(s) ?: continue
            val key = s.take(d).joinToString("/").lowercase()
            counts[key] = (counts[key] ?: 0) + 1
            if (key !in first) first[key] = s.take(d)
        }
        val best = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.length }).firstOrNull()?.key ?: return null
        return if (best.isEmpty()) null else first[best]
    }

    private fun stripBase(raw: String, base: List<String>): List<List<String>> {
        val s = segs(raw)
        if (s.any { it == ".." } || s.size <= base.size) return emptyList()
        for (i in base.indices) if (!s[i].equals(base[i], true)) return emptyList()
        val rel = s.drop(base.size)
        return if (rel.first().equals("fomod", true)) emptyList() else listOf(rel)
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

    /** Turns the low-level errors a truncated or corrupt archive produces into something a person can act on. */
    private fun friendly(t: Throwable): Throwable {
        val m = t.message.orEmpty().lowercase()
        val damaged = t is java.io.EOFException || listOf("zlib", "unexpected end", "crc", "invalid stored", "invalid distance", "invalid code",
            "header check", "truncated", "end-of-central-directory", "central directory", "not a zip").any { it in m }
        return if (damaged) IllegalStateException("The archive looks damaged or incomplete (${t.message}). Download it again, or check that it opens in another app.", t) else t
    }

    private fun extract(dataUri: Uri, zipUri: Uri, name: String, plan: (String) -> List<List<String>>): Mod {
        val idx = Idx(dataRoot(dataUri))
        val id = UUID.randomUUID().toString().take(8)
        val bdir = File(backupRoot, id).apply { mkdirs() }
        val files = mutableListOf<String>(); val dirs = mutableListOf<String>()
        val backups = mutableMapOf<String, String>()
        val sizes = mutableMapOf<String, Long>()
        var bytes = 0L
        val tracked = HashSet<String>() // every file some installed mod already owns
        for (o in list()) for (f in o.files) tracked += normPath(f)
        try {
            Progress.report("Installing $name", "Preparing…", null, force = true)
            openArchive(ctx, zipUri).use { arch ->
                var n = 0; var done = 0
                val total = arch.names().count { plan(it).isNotEmpty() }.coerceAtLeast(1)
                val need = arch.sizes().entries.sumOf { (n, sz) -> plan(n).size * sz }
                freeSpace(dataUri)?.let { free ->
                    if (free < need + (20L shl 20)) error("Not enough free space in the game folder's storage: ${fmtSize(need)} needed, ${fmtSize(free)} free.")
                }
                arch.forEach { entryName, z ->
                    var first: DocumentFile? = null
                    for (rel in plan(entryName)) {
                        val parent = rel.dropLast(1)
                        val dir = idx.dir(parent, dirs) ?: error("Cannot create folder ${parent.joinToString("/")}")
                        val kids = idx.children(parent)!!
                        val fname = rel.last(); val relStr = rel.joinToString("/")
                        var oldBak: String? = null; var oldLen = -1L
                        kids.remove(fname.lowercase())?.let { old ->
                            val b = "${n++}.bak"
                            oldLen = old.length()
                            cr.openInputStream(old.uri)!!.use { i -> File(bdir, b).outputStream().use { i.copyTo(it, BUF) } }
                            backups[relStr] = b; oldBak = b
                            old.delete()
                        }
                        val out = newFile(dir, fname) ?: error("Cannot create $fname")
                        kids[fname.lowercase()] = out
                        files += relStr
                        val prev = first
                        val wrote = if (prev == null) { val c = cr.openOutputStream(out.uri)!!.use { z.copyTo(it, BUF) }; first = out; c }
                        else cr.openInputStream(prev.uri)!!.use { i -> cr.openOutputStream(out.uri)!!.use { i.copyTo(it, BUF) } }
                        bytes += wrote; sizes[relStr] = wrote
                        val ob = oldBak
                        if (ob != null && oldLen == wrote && normPath(relStr) !in tracked && !isVanilla(relStr) && sameContent(File(bdir, ob), out)) {
                            // an identical file that no mod owns is a leftover of this very mod, so there is nothing to restore later
                            File(bdir, ob).delete(); backups.remove(relStr)
                        }
                    }
                    if (first != null) { done++; Progress.report("Installing $name", files.lastOrNull().orEmpty(), done.toFloat() / total) }
                    true
                }
            }
            if (files.isEmpty()) error("No installable files found in archive")
        } catch (t: Throwable) {
            removeFiles(dataUri, Mod(id, name, files, dirs, backups, time = System.currentTimeMillis())); throw friendly(t)
        } finally { Stage.clear(ctx) }
        val conv = conversions.remove(zipUri.toString())
        val made = Mod(id, name, files, dirs, backups, true, bytes, System.currentTimeMillis(),
            note = if (conv != null) "Converted from Special Edition" else "", fsizes = sizes)
        if (conv != null) runCatching { File(reportDir, "$id.md").writeText(conv) }
        return made.also { save(it); syncActiveProfile(dataUri) }
    }

    /** Removes a mod: its files (except any a newer mod now owns), restores originals it overwrote, and takes its plugins out of the load order. */
    fun uninstall(dataUri: Uri, mod: Mod, restore: Boolean = true) {
        removeFiles(dataUri, mod, restore)
        syncPlugins(dataUri, mod, Sync.REMOVE)
        if (list().none { it.name.equals(mod.name, true) }) removeFromProfiles(mod.name)
        syncActiveProfile(dataUri)
    }

    private fun removeFiles(dataUri: Uri, mod: Mod, restore: Boolean = true) {
        var done = 0
        Progress.report("Uninstalling ${mod.name}", "Preparing…", null, force = true)
        val idx = Idx(dataRoot(dataUri))
        val bdir = File(backupRoot, mod.id)
        // Newer enabled mods that wrote the same files own the live copy, so this mod must not touch it.
        val newer = HashMap<String, Mod>()
        for (o in list().filter { it.id != mod.id && it.enabled && it.time > mod.time }.sortedByDescending { it.time })
            for (f in o.files) newer[normPath(f)] = o // iterating newest first, so the oldest newer writer ends up as the owner
        val changed = HashMap<String, Mod>()
        for ((parent, group) in mod.files.groupBy { segs(it).dropLast(1) }) {
            val dir = idx.dir(parent, if (mod.enabled && group.any { it in mod.backups }) mutableListOf<String>() else null) ?: continue
            val kids = idx.children(parent) ?: continue
            for (f in group) {
                val fname = segs(f).last(); val key = fname.lowercase()
                Progress.report("Uninstalling ${mod.name}", f, (++done).toFloat() / mod.files.size)
                if (!mod.enabled) { kids.remove("$key.disabled")?.delete(); continue }
                val owner = newer[normPath(f)]
                if (owner != null) { // hand this mod's backup to the owner instead of touching the live file
                    val ob = changed[owner.id] ?: owner
                    val theirs = ob.backups[f]?.let { File(File(backupRoot, ob.id), it) }
                    val mine = mod.backups[f]?.let { File(bdir, it) }
                    if (theirs != null) {
                        if (mine != null && mine.exists()) mine.copyTo(theirs, overwrite = true)
                        else { theirs.delete(); changed[owner.id] = ob.copy(backups = ob.backups - f) }
                    }
                    continue
                }
                kids.remove(key)?.delete()
                kids.remove("$key.disabled")?.delete()
                if (restore) mod.backups[f]?.let { b ->
                    val out = newFile(dir, fname) ?: return@let
                    kids[key] = out
                    File(bdir, b).inputStream().use { i -> cr.openOutputStream(out.uri)!!.use { i.copyTo(it, BUF) } }
                }
            }
        }
        changed.values.forEach { save(it) }
        mod.dirs.sortedByDescending { segs(it).size }.forEach { d ->
            val p = segs(d)
            if (idx.children(p)?.isEmpty() == true) idx.removeDir(p)
        }
        bdir.deleteRecursively(); File(store, "${mod.id}.json").delete(); File(reportDir, "${mod.id}.md").delete()
    }

    // ---------------- files the app did not install ----------------

    /** Files this mod overwrote that no other mod here owns: probably leftovers of an earlier install, but possibly original game files. */
    fun leftoverBackups(m: Mod): List<String> {
        val others = HashSet<String>()
        for (o in list()) if (o.id != m.id) for (f in o.files) others += normPath(f)
        return m.backups.keys.filter { normPath(it) !in others && !isVanilla(it) }.sorted()
    }

    /** Treats these overwritten files as part of the mod: their saved copies are dropped, so uninstalling deletes them instead of restoring them. */
    fun claimLeftovers(m: Mod, paths: Collection<String>): Mod {
        val cur = list().firstOrNull { it.id == m.id } ?: return m
        val bdir = File(backupRoot, cur.id)
        val keep = cur.backups.toMutableMap()
        for (p in paths) keep.remove(p)?.let { File(bdir, it).delete() }
        return cur.copy(backups = keep).also { save(it) }
    }

    private fun isVanilla(rel: String): Boolean {
        val s = segs(rel)
        if (s.size != 1) return false
        val n = s[0].lowercase()
        return n in VANILLA_ROOT || (n.startsWith("skyrim - ") && n.endsWith(".bsa"))
    }

    private fun sameContent(a: File, b: DocumentFile): Boolean = try {
        a.inputStream().use { x -> cr.openInputStream(b.uri)!!.use { y -> streamsEqual(x, y) } }
    } catch (t: Throwable) { false }

    private fun streamsEqual(x: InputStream, y: InputStream): Boolean {
        val ba = ByteArray(BUF); val bb = ByteArray(BUF)
        while (true) {
            val n = x.fillBuf(ba); val m = y.fillBuf(bb)
            if (n != m) return false
            if (n == 0) return true
            for (i in 0 until n) if (ba[i] != bb[i]) return false
        }
    }

    /** Lists every file in Data that no installed mod owns and that is not a known game file, grouped by plugin (with its archives) or folder. */
    fun scanUnmanaged(dataUri: Uri): List<Unmanaged> {
        val idx = Idx(dataRoot(dataUri))
        val owned = HashSet<String>()
        for (m in list()) for (f in m.files) owned += normPath(f)
        val found = mutableListOf<Pair<String, Long>>()
        fun walk(p: List<String>) {
            val kids = idx.children(p) ?: return
            for (f in kids.values.toList()) {
                val n = f.name ?: continue
                val rel = p + n
                if (f.isDirectory) {
                    if (p.isEmpty() && (n.equals("video", true) || n.equals("SkyrimMM Backups", true))) continue
                    walk(rel)
                } else {
                    val path = rel.joinToString("/")
                    if (normPath(path).removeSuffix(".disabled") in owned || isVanilla(path)) continue
                    found.add(path to f.length())
                }
            }
        }
        walk(emptyList())
        fun ext(p: String) = p.substringAfterLast('.', "").lowercase()
        val root = found.filter { '/' !in it.first }
        val plugins = root.filter { ext(it.first) in setOf("esp", "esm", "esl") }.sortedBy { it.first.lowercase() }
        val bsas = root.filter { ext(it.first) == "bsa" }
        val used = HashSet<String>()
        val out = mutableListOf<Unmanaged>()
        for (pl in plugins) {
            val base = pl.first.substringBeforeLast('.').lowercase()
            val mine = bsas.filter { b -> b.first !in used && b.first.lowercase().let { it == "$base.bsa" || (it.startsWith("$base - ") && it.endsWith(".bsa")) } }
            used.add(pl.first); mine.forEach { used.add(it.first) }
            val all = listOf(pl) + mine
            out.add(Unmanaged("Plugin: ${pl.first}" + (if (mine.isNotEmpty()) " + ${mine.size} archive(s)" else ""), all.map { it.first }, all.sumOf { it.second }, true))
        }
        val loose = found.filter { it.first !in used }.groupBy { e ->
            val d = e.first.split('/').dropLast(1)
            if (d.isEmpty()) "Data folder (top level)" else d.take(3).joinToString("/")
        }
        for ((k, v) in loose.entries.sortedByDescending { e -> e.value.sumOf { it.second } })
            out.add(Unmanaged(k, v.map { it.first }.sorted(), v.sumOf { it.second }, false))
        return out
    }

    private fun moveTo(src: DocumentFile, srcDir: DocumentFile, dstDir: DocumentFile, name: String): Boolean {
        if (src.uri.scheme == "file" && dstDir.uri.scheme == "file") {
            if (File(src.uri.path!!).renameTo(File(dstDir.uri.path!!, name))) return true
        } else {
            val ok = try { DocumentsContract.moveDocument(cr, src.uri, srcDir.uri, dstDir.uri) != null } catch (t: Throwable) { false }
            if (ok) return true
        }
        val out = newFile(dstDir, name) ?: return false
        return try {
            cr.openInputStream(src.uri)!!.use { i -> cr.openOutputStream(out.uri)!!.use { o -> i.copyTo(o, BUF) } }
            src.delete()
        } catch (t: Throwable) { out.delete(); false }
    }

    /** Moves files out of Data into SkyrimMM Backups/Removed/<time> (same folder layout) instead of deleting them. Returns how many moved. */
    fun removeUnmanaged(dataUri: Uri, files: List<String>): Int {
        val idx = Idx(dataRoot(dataUri))
        val trash = Idx(rootOf(dataUri) ?: error("Invalid folder"))
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var moved = 0; var done = 0
        val parents = HashSet<List<String>>()
        Progress.report("Moving files out of Data", "Preparing…", null, force = true)
        for (f in files) {
            val s = segs(f)
            Progress.report("Moving files out of Data", f, (++done).toFloat() / files.size)
            if (s.isEmpty()) continue
            val kids = idx.children(s.dropLast(1)) ?: continue
            val srcDir = idx.dir(s.dropLast(1)) ?: continue
            val src = kids[s.last().lowercase()] ?: continue
            val dst = trash.dir(listOf("SkyrimMM Backups", "Removed", stamp) + s.dropLast(1), mutableListOf<String>()) ?: continue
            if (moveTo(src, srcDir, dst, s.last())) { kids.remove(s.last().lowercase()); moved++; parents.add(s.dropLast(1)) }
        }
        for (p in parents.sortedByDescending { it.size }) {
            var q = p
            while (q.isNotEmpty() && idx.children(q)?.isEmpty() == true) { idx.removeDir(q); q = q.dropLast(1) }
        }
        val gone = files.filter { f -> segs(f).size == 1 && segs(f)[0].substringAfterLast('.', "").lowercase() in setOf("esp", "esm", "esl") }
        if (gone.isNotEmpty()) syncPlugins(dataUri, Mod("unmanaged", "Unmanaged files", gone, emptyList(), emptyMap()), Sync.REMOVE)
        syncActiveProfile(dataUri)
        return moved
    }

    /**
     * Looks through an archive for files that already sit in Data with the same path and size and that no mod owns. The archive may be laid out
     * differently from how the mod was installed (a Data folder, a wrapper folder, FOMOD option folders), so leading folders are tried one by one.
     */
    fun adoptPreview(dataUri: Uri, zipUri: Uri): AdoptPlan {
        val idx = Idx(dataRoot(dataUri))
        val owned = HashSet<String>()
        for (m in list()) for (f in m.files) owned += normPath(f)
        Progress.report("Reading archive", "Matching files…", null, force = true)
        val matched = LinkedHashMap<String, Pair<String, Long>>()
        var total = 0
        try {
            openArchive(ctx, zipUri).use { arch ->
                for ((entry, size) in arch.sizes()) {
                    val s = segs(entry)
                    if (s.isEmpty() || s.any { it == ".." } || s.any { it.equals("fomod", true) }) continue
                    total++
                    for (i in 0..minOf(4, s.size - 1)) {
                        val rel = s.drop(i)
                        val f = idx.children(rel.dropLast(1))?.get(rel.last().lowercase()) ?: continue
                        if (!f.isFile || f.length() != size) continue
                        val path = rel.joinToString("/")
                        if (normPath(path) !in owned && normPath(path) !in matched) matched[normPath(path)] = path to size
                        break
                    }
                }
            }
        } catch (t: Throwable) { throw friendly(t) } finally { Stage.clear(ctx) }
        val files = matched.values.map { it.first }
        return AdoptPlan(zipName(zipUri), files, matched.values.associate { it.first to it.second }, total)
    }

    /** Records the matched files as an installed mod. Nothing in Data changes; the app can now disable and uninstall it. */
    fun adopt(dataUri: Uri, plan: AdoptPlan, name: String): Mod {
        if (plan.files.isEmpty()) error("Nothing to adopt")
        val dirs = LinkedHashSet<String>()
        for (f in plan.files) { val s = segs(f); for (k in 1 until s.size) dirs.add(s.take(k).joinToString("/")) }
        val id = UUID.randomUUID().toString().take(8)
        val m = Mod(id, name.trim().ifBlank { plan.name }, plan.files, dirs.toList(), emptyMap(), true, plan.sizes.values.sum(), System.currentTimeMillis(), fsizes = plan.sizes)
        save(m); syncActiveProfile(dataUri)
        return m
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
        syncActiveProfile(dataUri)
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

    /** Disables a mod by renaming its files to *.disabled (restoring anything it overwrote), or re-enables it. Plugins follow. */
    fun setEnabled(dataUri: Uri, mod: Mod, enable: Boolean, sync: Boolean = true): Mod {
        if (mod.enabled == enable) return mod
        var done = 0
        val title = (if (enable) "Enabling " else "Disabling ") + mod.name
        Progress.report(title, "Preparing…", null, force = true)
        val idx = Idx(dataRoot(dataUri))
        val bdir = File(backupRoot, mod.id)
        val newer = HashSet<String>() // files a newer enabled mod owns
        for (o in list().filter { it.id != mod.id && it.enabled && it.time > mod.time }) for (f in o.files) newer += normPath(f)
        if (enable) { // refuse before changing anything if another mod took over a file while this one was off
            val taken = mod.files.filter { f ->
                val p = segs(f); val kids = idx.children(p.dropLast(1)); val key = p.last().lowercase()
                kids != null && kids.containsKey("$key.disabled") && ((f !in mod.backups && kids.containsKey(key)) || normPath(f) in newer)
            }
            if (taken.isNotEmpty()) error("Can't enable ${mod.name}: ${taken.size} of its files are now provided by another mod (for example ${taken.first()}). Disable or uninstall that mod first.")
        }
        val backups = mod.backups.toMutableMap()
        for ((parent, group) in mod.files.groupBy { segs(it).dropLast(1) }) {
            val dir = idx.dir(parent) ?: continue
            val kids = idx.children(parent) ?: continue
            for (f in group) {
                val name = segs(f).last(); val key = name.lowercase()
                Progress.report(title, f, (++done).toFloat() / mod.files.size)
                if (!enable) {
                    if (normPath(f) in newer) continue // a newer mod owns the live file; leave it alone
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
                    if (f in backups) {
                        val cur = kids.remove(key)
                        if (cur != null) {
                            cr.openInputStream(cur.uri)!!.use { i -> File(bdir, backups[f]!!).outputStream().use { i.copyTo(it, BUF) } }
                            cur.delete()
                        } else backups.remove(f) // nothing underneath any more, so there is nothing to restore later
                    }
                    if (!off.renameTo(name)) error("Cannot rename $name")
                    kids[key] = off
                }
            }
        }
        val changed = if (sync) syncPlugins(dataUri, mod, if (enable) Sync.RESTORE else Sync.DEACTIVATE) else emptyList()
        return mod.copy(enabled = enable, backups = backups, wasActive = if (enable) emptyList() else changed).also { save(it); syncActiveProfile(dataUri) }
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

    /** Free bytes on the volume holding the Data folder, or null if that can't be told. */
    private fun freeSpace(dataUri: Uri): Long? {
        val r = rootOf(dataUri) ?: return null
        if (r.uri.scheme == "file") return File(r.uri.path!!).usableSpace
        val id = runCatching { DocumentsContract.getTreeDocumentId(dataUri) }.getOrNull() ?: return null
        return if (id.substringBefore(':') == "primary") Environment.getExternalStorageDirectory().usableSpace else null
    }

    /** Returns a message if there isn't room to stage this archive in the app's temporary storage. */
    fun checkRoom(archive: Uri): String? {
        val size = DocumentFile.fromSingleUri(ctx, archive)?.length() ?: return null
        val need = size * 3 / 2 + (50L shl 20) // staged copy, plus extra room when a RAR has to be unpacked
        val free = ctx.cacheDir.usableSpace
        return if (size > 0 && free < need) "Not enough free storage for the temporary copy of this archive: ${fmtSize(need)} needed, ${fmtSize(free)} free." else null
    }

    /** Checks a mod's files against the disk. Returns (missing, changed). Files a newer mod now owns are not judged. */
    fun verify(dataUri: Uri, mod: Mod): Pair<List<String>, List<String>> {
        val idx = Idx(dataRoot(dataUri))
        val newer = HashSet<String>()
        for (o in list().filter { it.id != mod.id && it.enabled && it.time > mod.time }) for (f in o.files) newer += normPath(f)
        val missing = mutableListOf<String>(); val changed = mutableListOf<String>()
        for ((parent, group) in mod.files.groupBy { segs(it).dropLast(1) }) {
            val kids = idx.children(parent)
            for (f in group) {
                val key = segs(f).last().lowercase()
                val file = kids?.get(if (mod.enabled) key else "$key.disabled")
                if (file == null) { if (mod.enabled || normPath(f) !in newer) missing += f; continue }
                val expected = mod.fsizes[f]
                if (expected != null && normPath(f) !in newer && file.length() != expected) changed += f
            }
        }
        return missing to changed
    }

    fun updateDetails(id: String, name: String, version: String, note: String, link: String): Mod? {
        val old = list().firstOrNull { it.id == id } ?: return null
        val n = old.copy(name = name.trim().ifBlank { "Unnamed mod" }, version = version.trim(), note = note.trim(), link = link.trim())
        if (!n.name.equals(old.name, true)) renameInProfiles(old.name, n.name) // profiles follow a rename
        save(n); syncActiveProfile(null)
        return n
    }

    private fun orderBackupDir(dataUri: Uri?): DocumentFile? = dataUri?.let { rootOf(it) }?.let { Idx(it).dir(listOf("SkyrimMM Backups", "Load order")) }

    /** Timestamps of the saved load order backups, newest first. */
    fun listOrderBackups(dataUri: Uri?): List<String> {
        val names = orderBackupDir(dataUri)?.listFiles()?.mapNotNull { it.name } ?: File(ctx.filesDir, "order_backups").list()?.toList().orEmpty()
        return names.filter { it.startsWith("Plugins-") && it.endsWith(".txt") }.map { it.removePrefix("Plugins-").removeSuffix(".txt") }.sortedDescending()
    }

    /** Puts a saved Plugins.txt / loadorder.txt back (the current ones are backed up first). */
    fun restoreOrderBackup(dataUri: Uri?, f: OrderFiles, stamp: String): String {
        val note = backupOrder(dataUri, f)
        val d = orderBackupDir(dataUri)
        fun read(label: String): ByteArray? =
            if (d != null) d.listFiles().firstOrNull { it.name == "$label-$stamp.txt" }?.let { x -> cr.openInputStream(x.uri)!!.use { it.readBytes() } }
            else File(File(ctx.filesDir, "order_backups"), "$label-$stamp.txt").takeIf { it.exists() }?.readBytes()
        val plug = read("Plugins") ?: error("That backup has no Plugins file.")
        val p = f.plugins ?: f.dir?.let { newFile(it, "Plugins.txt") }?.uri ?: error("No plugins.txt selected")
        cr.openOutputStream(p, "wt")!!.use { it.write(plug) }
        read("loadorder")?.let { lo ->
            val u = f.loadOrder ?: f.dir?.let { newFile(it, "loadorder.txt") }?.uri
            if (u != null) cr.openOutputStream(u, "wt")!!.use { it.write(lo) }
        }
        syncActiveProfile(dataUri)
        return "Restored the load order from $stamp. $note"
    }

    // ---------------- profiles and shareable setups ----------------
    private val profDir = File(ctx.filesDir, "profiles").apply { mkdirs() }

    fun profiles(): List<Profile> = profDir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { f -> runCatching { parseProfile(f.readText(), f.nameWithoutExtension) }.getOrNull() }.sortedBy { it.name.lowercase() }

    fun saveProfile(p: Profile) = File(profDir, "${p.id}.json").writeText(profileJson(p))
    fun deleteProfile(p: Profile) {
        File(profDir, "${p.id}.json").delete()
        if (activeFile.exists() && activeFile.readText().trim() == p.id) activeFile.delete()
    }

    fun profileJson(p: Profile): String = JSONObject().put("app", "SkyrimMM").put("name", p.name)
        .put("enabled", JSONArray(p.enabled))
        .put("order", JSONArray(p.order.map { JSONObject().put("name", it.name).put("active", it.active) }))
        .put("mods", JSONArray(p.mods.map { JSONObject().put("name", it.name).put("version", it.version).put("link", it.link) })).toString(2)

    fun parseProfile(json: String, id: String = UUID.randomUUID().toString().take(8)): Profile {
        val o = JSONObject(json.trim())
        val en = o.optJSONArray("enabled")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
        val ord = o.optJSONArray("order")?.let { a -> List(a.length()) { i -> a.getJSONObject(i).let { PluginEntry(it.getString("name"), it.optBoolean("active", true)) } } }.orEmpty()
        val ms = o.optJSONArray("mods")?.let { a -> List(a.length()) { i -> a.getJSONObject(i).let { ProfMod(it.getString("name"), it.optString("version"), it.optString("link")) } } }.orEmpty()
        return Profile(id, o.optString("name", "Imported setup"), en, ord, ms)
    }

    /**
     * A profile's state: which mods are on and the plugin order. With `empty`, every mod starts off (and their plugins inactive),
     * so a new profile can be a genuinely different set instead of a copy.
     */
    fun captureProfile(name: String, dataUri: Uri?, empty: Boolean = false): Profile {
        val ms = list()
        val ord = runCatching { readOrder(orderFiles(dataUri, manualPlugins)).second }.getOrDefault(emptyList())
        val infos = ms.map { ProfMod(it.name, it.version, it.link) }
        val id = UUID.randomUUID().toString().take(8)
        if (!empty) return Profile(id, name, ms.filter { it.enabled }.map { it.name }, ord, infos)
        val modPlugins = ms.flatMap { it.plugins }.map { it.substringAfterLast('/').lowercase() }.toSet()
        return Profile(id, name, emptyList(), ord.map { if (it.name.lowercase() in modPlugins) it.copy(active = false) else it }, infos)
    }

    private val activeFile = File(ctx.filesDir, "active_profile.txt")
    @Volatile private var suspendSync = false

    fun activeProfile(): Profile? {
        val id = activeFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        return profiles().firstOrNull { it.id == id }
    }
    fun activeProfileName(): String? = activeProfile()?.name
    private fun setActive(p: Profile?) { if (p == null) activeFile.delete() else activeFile.writeText(p.id) }

    /** There is always an active profile: the first run creates "Default" from the current state. */
    fun ensureDefaultProfile(dataUri: Uri?) {
        val all = profiles()
        if (all.isEmpty()) captureProfile("Default", dataUri).also { saveProfile(it); setActive(it) }
        else if (activeProfile() == null) setActive(all.first())
    }

    /** The active profile follows every change: mods turned on or off, installs, uninstalls and load order edits are saved into it. */
    fun syncActiveProfile(dataUri: Uri?) {
        if (suspendSync) return
        runCatching {
            val a = activeProfile() ?: return
            val cur = captureProfile(a.name, dataUri)
            saveProfile(Profile(a.id, a.name, cur.enabled, if (cur.order.isEmpty()) a.order else cur.order, cur.mods))
        }
    }

    private fun editProfiles(f: (Profile) -> Profile?) {
        for (p in profiles()) { val n = f(p); if (n != null) saveProfile(n) }
    }
    fun renameInProfiles(old: String, new: String) = editProfiles { p ->
        if (p.enabled.none { it.equals(old, true) }) null
        else Profile(p.id, p.name, p.enabled.map { if (it.equals(old, true)) new else it }, p.order,
            p.mods.map { if (it.name.equals(old, true)) ProfMod(new, it.version, it.link) else it })
    }
    fun removeFromProfiles(name: String) = editProfiles { p ->
        if (p.enabled.none { it.equals(name, true) }) null else Profile(p.id, p.name, p.enabled.filterNot { it.equals(name, true) }, p.order, p.mods)
    }

    /** Switches to a profile: turns exactly its mods on (matched by name), writes its plugin order once, and makes it the active profile. */
    fun applyProfile(dataUri: Uri, p: Profile): String {
        suspendSync = true
        try {
            val want = p.enabled.map { it.lowercase() }.toSet()
            val installed = list()
            var changed = 0
            for (m in installed) {
                val on = m.name.lowercase() in want
                if (on != m.enabled) { setEnabled(dataUri, m, on, sync = false); changed++ }
            }
            val missing = p.enabled.filter { n -> installed.none { it.name.equals(n, true) } }
            var note = ""
            if (p.order.isNotEmpty()) {
                val f = orderFiles(dataUri, manualPlugins)
                note = if (f.plugins != null || f.dir != null) writeOrder(f, readOrder(f).first, p.order, dataUri)
                else "No plugins.txt is linked, so the load order was not changed."
            }
            setActive(p)
            return "Switched to profile ${p.name}: $changed mod(s) changed." + (if (missing.isNotEmpty()) " Not installed: ${missing.joinToString()}." else "") + " $note"
        } finally { suspendSync = false }
    }

    // ---------------- Skyrim.ini helper ----------------
    private fun iniBytes(uri: Uri): ByteArray {
        val b = cr.openInputStream(uri)!!.use { it.readBytes() }
        if (b.size >= 2 && (b[0] == 0xFF.toByte() || b[0] == 0xFE.toByte())) error("This ini file uses UTF-16, which is not supported.")
        return b
    }

    /** (loose files enabled, data dirs cleared): bInvalidateOlderFiles=1 and an empty sResourceDataDirsFinal in [Archive]. */
    fun iniState(uri: Uri): Pair<Boolean, Boolean> {
        var sec = ""; var inv = false; var dirsEmpty = false
        for (l in String(iniBytes(uri), Charsets.ISO_8859_1).lines()) {
            val t = l.trim()
            if (t.startsWith("[")) { sec = t.lowercase(); continue }
            if (sec != "[archive]") continue
            val k = t.substringBefore('=').trim().lowercase(); val v = t.substringAfter('=', "").trim()
            if (k == "binvalidateolderfiles") inv = v == "1"
            if (k == "sresourcedatadirsfinal") dirsEmpty = v.isEmpty()
        }
        return inv to dirsEmpty
    }

    fun applyIni(uri: Uri, looseFiles: Boolean, dataUri: Uri?): String {
        val bytes = iniBytes(uri)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = dataUri?.let { rootOf(it) }?.let { Idx(it).dir(listOf("SkyrimMM Backups", "Ini"), mutableListOf()) }
        if (dir != null) newFile(dir, "Skyrim-$stamp.ini")?.let { out -> cr.openOutputStream(out.uri, "wt")!!.use { it.write(bytes) } }
        else File(File(ctx.filesDir, "order_backups").apply { mkdirs() }, "Skyrim-$stamp.ini").writeBytes(bytes)
        val text = String(bytes, Charsets.ISO_8859_1)
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split(Regex("\r?\n")).toMutableList()
        var start = lines.indexOfFirst { it.trim().equals("[Archive]", true) }
        if (start < 0) { lines += "[Archive]"; start = lines.size - 1 }
        var end = (start + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("[") } ?: lines.size
        fun setKey(key: String, value: String) {
            val i = (start + 1 until end).firstOrNull { lines[it].substringBefore('=').trim().equals(key, true) }
            if (i != null) lines[i] = "$key=$value" else { lines.add(end, "$key=$value"); end++ }
        }
        if (looseFiles) { setKey("bInvalidateOlderFiles", "1"); setKey("sResourceDataDirsFinal", "") }
        else { setKey("bInvalidateOlderFiles", "0"); setKey("sResourceDataDirsFinal", "STRINGS\\") }
        cr.openOutputStream(uri, "wt")!!.use { it.write(lines.joinToString(nl).toByteArray(Charsets.ISO_8859_1)) }
        return "Skyrim.ini updated (loose files ${if (looseFiles) "on" else "off"}). A backup was saved first."
    }

    // ---------------- troubleshooting (bisecting mods) ----------------
    fun troubleState(): Trouble? = File(ctx.filesDir, "trouble.json").takeIf { it.exists() }?.let { f ->
        runCatching {
            val o = JSONObject(f.readText())
            fun ids(k: String) = o.optJSONArray(k)?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            Trouble(o.getString("phase"), ids("original"), ids("cands"), ids("good"), ids("testing"))
        }.getOrNull()
    }

    private fun saveTrouble(t: Trouble?) {
        val f = File(ctx.filesDir, "trouble.json")
        if (t == null) f.delete()
        else f.writeText(JSONObject().put("phase", t.phase).put("original", JSONArray(t.original)).put("cands", JSONArray(t.cands))
            .put("good", JSONArray(t.good)).put("testing", JSONArray(t.testing)).toString())
    }

    /** Turns exactly the mods with these ids on and all others off. The plugin list is left alone. */
    private fun applyEnabled(dataUri: Uri, ids: Set<String>): Int {
        suspendSync = true
        try { return applyEnabledRaw(dataUri, ids) } finally { suspendSync = false }
    }

    private fun applyEnabledRaw(dataUri: Uri, ids: Set<String>): Int {
        var n = 0
        for (m in list()) {
            val on = m.id in ids
            if (on == m.enabled) continue
            runCatching { setEnabled(dataUri, m, on, sync = false) }.onSuccess { n++ }.onFailure { addNote("${m.name}: ${it.message}") }
        }
        return n
    }

    fun troubleStart(dataUri: Uri): String {
        val orig = list().filter { it.enabled }.map { it.id }
        saveTrouble(Trouble("alloff", orig, orig, emptyList(), emptyList()))
        applyEnabled(dataUri, emptySet())
        return "All mods are off. Start the game: does the problem still happen?"
    }

    fun troubleRestore(dataUri: Uri): String {
        val t = troubleState()
        if (t != null) applyEnabled(dataUri, t.original.toSet())
        saveTrouble(null)
        return "Your mods are back to how they were."
    }

    fun troubleAnswer(dataUri: Uri, problemStillThere: Boolean): String {
        val t = troubleState() ?: return "There is no troubleshooting session."
        if (t.phase == "alloff") {
            if (problemStillThere) { troubleRestore(dataUri); return "The problem happens with every mod off, so these mods are not the cause. Everything was switched back on." }
            return troubleNext(dataUri, Trouble("bisect", t.original, t.original, emptyList(), emptyList()))
        }
        val next = if (problemStillThere) Trouble("bisect", t.original, t.testing, t.good, emptyList())
        else Trouble("bisect", t.original, t.cands - t.testing.toSet(), t.good + t.testing, emptyList())
        return troubleNext(dataUri, next)
    }

    private fun troubleNext(dataUri: Uri, t: Trouble): String {
        if (t.cands.size <= 1) {
            val culprit = list().firstOrNull { it.id == t.cands.firstOrNull() }?.name
            applyEnabled(dataUri, t.original.toSet() - t.cands.toSet())
            saveTrouble(null)
            syncActiveProfile(dataUri) // the culprit stays off, so the profile now says so
            return if (culprit != null) "The likely cause is $culprit. It is switched off and every other mod is back on. If the problem needs two mods together, this can be wrong."
            else "No single mod could be blamed. Your mods are back to how they were."
        }
        val half = t.cands.take((t.cands.size + 1) / 2)
        applyEnabled(dataUri, (t.good + half).toSet())
        saveTrouble(Trouble("bisect", t.original, t.cands, t.good, half))
        return "Testing: ${half.size} of ${t.cands.size} suspect mods are on. Start the game and tell me whether the problem happens."
    }

    /** Set by the UI: the plugins.txt picked by hand (used when there is no port folder). */
    @Volatile var manualPlugins: Uri? = null
    @Volatile private var note: String? = null
    fun takeNote(): String? = note.also { note = null }
    private fun addNote(s: String) { note = listOfNotNull(note, s).joinToString(" ") }

    fun hasOrderFiles(dataUri: Uri?): Boolean = runCatching { orderFiles(dataUri, manualPlugins) }.getOrNull()?.let { it.dir != null || it.plugins != null } == true

    /** Keeps plugins.txt / loadorder.txt in step with a mod (with a backup first). Returns the plugin names it changed; never throws. */
    fun syncPlugins(dataUri: Uri?, mod: Mod, mode: Sync): List<String> {
        val mine = mod.plugins.map { it.substringAfterLast('/') }.distinctBy { it.lowercase() }
        if (mine.isEmpty()) return emptyList()
        return try {
            val f = orderFiles(dataUri, manualPlugins)
            if (f.plugins == null && f.dir == null) return emptyList()
            val (star, cur) = readOrder(f)
            val entries = cur.toMutableList()
            val changed = mutableListOf<String>()
            fun at(n: String) = entries.indexOfFirst { it.name.equals(n, true) }
            fun activate(n: String) {
                val i = at(n)
                if (i < 0) {
                    val pos = if (n.endsWith(".esm", true)) entries.indexOfLast { it.name.endsWith(".esm", true) } + 1 else entries.size
                    entries.add(pos, PluginEntry(n, true)); changed += n
                } else if (!entries[i].active) { entries[i] = entries[i].copy(active = true); changed += n }
            }
            when (mode) {
                Sync.ACTIVATE -> mine.forEach { activate(it) }
                Sync.RESTORE -> mod.wasActive.forEach { activate(it) }
                Sync.DEACTIVATE -> mine.forEach { n ->
                    val i = at(n)
                    if (i >= 0 && entries[i].active) { entries[i] = entries[i].copy(active = false); changed += n }
                }
                Sync.REMOVE -> {
                    val keep = list().filter { it.id != mod.id && it.enabled }.flatMap { it.plugins }.map { it.substringAfterLast('/').lowercase() }.toSet()
                    mine.filter { it.lowercase() !in keep }.forEach { n -> val i = at(n); if (i >= 0) { entries.removeAt(i); changed += n } }
                }
            }
            if (changed.isNotEmpty()) {
                writeOrder(f, star, entries, dataUri)
                addNote("Load order updated (${changed.size} plugin(s)); backup saved.")
            }
            changed
        } catch (t: Throwable) {
            addNote("Could not update the load order: ${t.message}"); emptyList()
        }
    }

    /** Reads each listed plugin's header (master flag and master list) from Data, and which files exist there. */
    fun scanPlugins(dataUri: Uri, names: Collection<String>): PluginScan {
        val kids = runCatching { Idx(dataRoot(dataUri)).children(emptyList()) }.getOrNull() ?: return PluginScan(emptyMap(), emptySet())
        val info = HashMap<String, PluginInfo>()
        for (n in names) {
            val f = kids[n.lowercase()] ?: continue
            runCatching { cr.openInputStream(f.uri)!!.use { parsePluginHeader(n, it) } }.getOrNull()?.let { info[n.lowercase()] = it }
        }
        return PluginScan(info, kids.keys.toSet())
    }

    private fun save(m: Mod) = File(store, "${m.id}.json").writeText(
        JSONObject().put("id", m.id).put("name", m.name).put("files", JSONArray(m.files))
            .put("dirs", JSONArray(m.dirs)).put("backups", JSONObject(m.backups)).put("enabled", m.enabled).put("size", m.size).put("time", m.time).put("wasActive", JSONArray(m.wasActive)).put("version", m.version).put("note", m.note).put("link", m.link)
            .put("fsizes", JSONObject(m.fsizes)).toString())

    private fun load(f: File): Mod {
        val o = JSONObject(f.readText())
        fun JSONArray.strs() = List(length()) { getString(it) }
        val b = o.getJSONObject("backups")
        return Mod(o.getString("id"), o.getString("name"), o.getJSONArray("files").strs(),
            o.getJSONArray("dirs").strs(), b.keys().asSequence().associateWith { b.getString(it) }, o.optBoolean("enabled", true), o.optLong("size", -1), o.optLong("time", 0), o.optJSONArray("wasActive")?.strs().orEmpty(),
            o.optString("version"), o.optString("note"), o.optString("link"),
            o.optJSONObject("fsizes")?.let { j -> j.keys().asSequence().associateWith { j.getLong(it) } }.orEmpty())
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

/** Game files in the top of Data, which are never offered as "unmanaged" and never treated as leftovers. */
private val VANILLA_ROOT = setOf("skyrim.esm", "update.esm", "dawnguard.esm", "hearthfires.esm", "dragonborn.esm",
    "dawnguard.bsa", "hearthfires.bsa", "dragonborn.bsa", "update.bsa")

private fun InputStream.fillBuf(b: ByteArray): Int {
    var t = 0
    while (t < b.size) { val r = read(b, t, b.size - t); if (r < 0) break; t += r }
    return t
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

/** Parses a Skyrim LE plugin's TES4 header record: the master flag and the MAST subrecords. */
private fun parsePluginHeader(name: String, s: InputStream): PluginInfo? {
    fun u32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    val h = s.readUpTo(24)
    if (h.size < 24 || String(h, 0, 4, Charsets.ISO_8859_1) != "TES4") return null
    val size = u32(h, 4); val flags = u32(h, 8)
    val formVersion = (h[20].toInt() and 0xFF) or ((h[21].toInt() and 0xFF) shl 8)
    var hedr = 0f
    if (size < 0 || size > 4_000_000) return null
    val d = s.readUpTo(size)
    val masters = mutableListOf<String>()
    var p = 0
    while (p + 6 <= d.size) {
        val type = String(d, p, 4, Charsets.ISO_8859_1)
        val len = (d[p + 4].toInt() and 0xFF) or ((d[p + 5].toInt() and 0xFF) shl 8)
        p += 6
        if (p + len > d.size) break
        if (type == "HEDR" && len >= 4) hedr = java.lang.Float.intBitsToFloat(u32(d, p))
        if (type == "MAST") { var e = p; while (e < p + len && d[e] != 0.toByte()) e++; masters += String(d, p, e - p, Charsets.ISO_8859_1) }
        p += len
    }
    return PluginInfo(name, (flags and 1) != 0, masters, hedr, formVersion, (flags and 0x200) != 0)
}

private fun u32le(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

/** The CPU type in a Windows DLL's PE header: 0x8664 = 64-bit, 0x014C = 32-bit. */
private fun peMachine(b: ByteArray): Int? {
    if (b.size < 0x40 || b[0] != 'M'.code.toByte() || b[1] != 'Z'.code.toByte()) return null
    val pe = u32le(b, 0x3C)
    if (pe < 0 || pe + 6 > b.size || b[pe] != 'P'.code.toByte() || b[pe + 1] != 'E'.code.toByte()) return null
    return (b[pe + 4].toInt() and 0xFF) or ((b[pe + 5].toInt() and 0xFF) shl 8)
}

/** The "BS version" in a .nif header: 83 for Skyrim LE, 100 for Special Edition. */
private fun nifBsVersion(b: ByteArray): Int? {
    if (String(b, 0, minOf(b.size, 20), Charsets.ISO_8859_1) != "Gamebryo File Format") return null
    val nl = b.indexOf(0x0A.toByte())
    if (nl < 0 || nl + 18 > b.size) return null
    return u32le(b, nl + 1 + 13) // after version (4), endian (1), user version (4), block count (4)
}

/** The DXGI format of a DDS texture with a DX10 header (98 and 99 are BC7), or null for older-style DDS files. */
private fun ddsDxgi(b: ByteArray): Int? {
    if (b.size < 132 || String(b, 0, 4, Charsets.ISO_8859_1) != "DDS " || String(b, 84, 4, Charsets.ISO_8859_1) != "DX10") return null
    return u32le(b, 128)
}

package com.se2le

import com.github.junrar.Archive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.*
import java.util.zip.*

class Report {
    val plugins = mutableListOf<String>()
    val notes = mutableListOf<String>()
    val changes = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val missing = sortedSetOf<String>()
    val nif = mutableListOf<String>()
    val dds = mutableListOf<String>()
    var kept = 0
    var pex = 0
    var skse = 0
    var bsaFailed = 0
    var scriptIssues = 0
    var texConverted = 0
    var modName = "mod"
    var maxTex = 0
    var texScaled = 0
    var scaledSaved = 0L
    var meshConverted = 0
    var onProgress: ((String) -> Unit)? = null
    var embedReport = true   // false when the host app shows or keeps the report itself
    val hkx = mutableListOf<String>()
    val checks = mutableListOf<Pair<String, String>>()
    fun check(status: String, msg: String) { checks.add(status to msg) }

    private fun StringBuilder.section(title: String, items: List<String>, max: Int = 300) {
        if (items.isEmpty()) return
        appendLine("\n## $title")
        items.take(max).forEach { appendLine("- $it") }
        if (items.size > max) appendLine("- ... and ${items.size - max} more")
    }

    fun text(): String = buildString {
        appendLine("# BACKPORT REPORT (SE2LE build ${Converter.BUILD})")
        section("ACTION REQUIRED - masters not in Skyrim LE (records using them will not resolve)", missing.toList())
        val rank = mapOf("FAIL" to 0, "WARN" to 1, "PASS" to 2)
        section("VERIFICATION: ${checks.count { it.first == "PASS" }} passed, ${checks.count { it.first == "WARN" }} warning(s), ${checks.count { it.first == "FAIL" }} failed",
            checks.sortedBy { rank[it.first] }.map { "${it.first}: ${it.second}" }, 500)
        section("WARNINGS / REVIEW", warnings)
        section("CHANGES MADE", changes + listOf("$kept other file(s) copied unchanged ($pex compiled script(s) among them)"))
        section("Plugins converted", plugins)
        section("${nif.size} meshes could not be converted (still SE format): use Cathedral Assets Optimizer", nif, 200)
        section("${dds.size} textures could not be converted (LE can't read them): use Cathedral Assets Optimizer", dds, 200)
        section("${hkx.size} SE (64-bit) animations: convert with a Havok converter on a PC", hkx, 200)
        section("Notes", notes)
        appendLine("\nNot handled: SKSE plugins, SPID/KID/SkyPatcher, plugin merging, removing/re-indexing missing masters, SE-only fields in records other than STAT and WEAP CRDT, skinned or complex NIF meshes, BC6H / signed BC4-BC5 / uncompressed DX10 textures, HKX animations, BSA repacking.")
    }
}

class PStat(val name: String) {
    val ver = sortedMapOf<Int, Int>()
    val stat = mutableListOf<String>()
    val dropped = mutableListOf<String>()
    val masterRenames = mutableListOf<String>()
    val types = sortedMapOf<String, Int>()
    var hedr = ""
    var esl = false
    var ownIdx = 0
    var map: Map<Int, Int> = emptyMap()
    var nextFree = 0
    var refs = 0
    var skipped = 0
    val weap = mutableListOf<String>()
}

class Plug(val path: String, val origBase: String, val outPath: String, val data: ByteArray, val map: Map<Int, Int>)

object Converter {
    const val BUILD = 14
    private val LE_MASTERS = setOf("skyrim.esm", "update.esm", "dawnguard.esm", "hearthfires.esm", "dragonborn.esm")
    private val MASTER_RENAME = mapOf("unofficial skyrim special edition patch.esp" to "Unofficial Skyrim Legendary Edition Patch.esp")
    private val SE_ONLY_GROUPS = setOf("LENS", "VOLI")   // lens flare / volumetric lighting do not exist in LE
    private val REVIEW_TYPES = setOf("WTHR", "CELL", "AMMO")
    private val BAD_DDS = mapOf(80 to "BC4", 81 to "BC4", 94 to "BC6H", 95 to "BC6H", 98 to "BC7", 99 to "BC7")
    private val PLUGIN_EXT = setOf("esp", "esm", "esl")
    private val ID_RE = Regex("(?i)(?<![0-9a-f])00([0-9a-f]{6})(?![0-9a-f])")
    private const val COMPRESSED = 0x00040000
    private const val ESL = 0x200
    private val ISO = Charsets.ISO_8859_1

    private fun r16(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    private fun r32(b: ByteArray, o: Int) = r16(b, o) or (r16(b, o + 2) shl 16)
    private fun w16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }
    private fun w32(b: ByteArray, o: Int, v: Int) { for (i in 0..3) b[o + i] = (v ushr (8 * i)).toByte() }
    private fun sig(b: ByteArray, o: Int) = String(b, o, 4, ISO)
    private fun inflate(p: ByteArray) = InflaterInputStream(ByteArrayInputStream(p, 4, p.size - 4)).readBytes()
    private fun deflate(p: ByteArray): ByteArray {
        val bo = ByteArrayOutputStream()
        val len = ByteArray(4); w32(len, 0, p.size); bo.write(len)
        DeflaterOutputStream(bo).use { it.write(p) }
        return bo.toByteArray()
    }

    private fun parseSubs(p: ByteArray): MutableList<Pair<String, ByteArray>>? {
        val out = mutableListOf<Pair<String, ByteArray>>()
        var i = 0
        while (i < p.size) {
            if (i + 6 > p.size) return null
            val s = sig(p, i)
            val n = r16(p, i + 4)
            if (s == "XXXX" || i + 6 + n > p.size) return null
            out.add(s to p.copyOfRange(i + 6, i + 6 + n))
            i += 6 + n
        }
        return out
    }

    private fun build(subs: List<Pair<String, ByteArray>>): ByteArray {
        val o = ByteArrayOutputStream()
        for ((s, d) in subs) { o.write(s.toByteArray(ISO)); o.write(d.size and 255); o.write((d.size shr 8) and 255); o.write(d) }
        return o.toByteArray()
    }

    private fun isText(d: ByteArray): Boolean {
        if (d.size < 4) return false
        for (x in d) { val c = x.toInt() and 255; if (!(c in 0x20..0x7E || c == 0 || c == 9 || c == 10 || c == 13)) return false }
        return true
    }

    private fun remapId(v: Int, ps: PStat): Int {
        if ((v ushr 24) != ps.ownIdx) return v
        val n = ps.map[v and 0xFFFFFF] ?: return v
        ps.refs++
        return (ps.ownIdx shl 24) or n
    }

    /** Rewrites every FormID that points at a renumbered own record. Copy-on-write. */
    private fun remapBytes(d: ByteArray, ps: PStat): ByteArray {
        var out: ByteArray? = null
        var i = 0
        while (i + 4 <= d.size) {
            if ((d[i + 3].toInt() and 255) == ps.ownIdx) {
                val n = ps.map[r32(d, i) and 0xFFFFFF]
                if (n != null) {
                    val o = out ?: d.copyOf().also { out = it }
                    w32(o, i, (ps.ownIdx shl 24) or n); ps.refs++
                    i += 4; continue
                }
            }
            i++
        }
        return out ?: d
    }

    private fun remapRaw(raw: ByteArray, ps: PStat): ByteArray {
        val subs = parseSubs(raw) ?: return remapBytes(raw, ps)
        return build(subs.map { (s, d) -> s to (if (isText(d)) d else remapBytes(d, ps)) })
    }

    private fun patch(sg: String, p: ByteArray, fid: Int, rep: Report, ps: PStat): ByteArray {
        val subs = parseSubs(p) ?: return p
        for (k in subs.indices) {
            val (s, d) = subs[k]
            if (sg == "TES4" && s == "HEDR" && d.size == 12) {
                val old = java.lang.Float.intBitsToFloat(r32(d, 0))
                if (Math.abs(old - 1.70f) > 0.005f) ps.hedr = String.format(java.util.Locale.US, "%.2f -> 1.70", old)
                val n = d.copyOf(); w32(n, 0, java.lang.Float.floatToIntBits(1.70f))
                if (ps.map.isNotEmpty()) w32(n, 8, maxOf(r32(d, 8), ps.nextFree))
                subs[k] = s to n
            } else if (sg == "TES4" && s == "MAST") {
                val m = String(d, ISO).trimEnd('\u0000')
                val repl = MASTER_RENAME[m.lowercase()]
                if (repl != null) { subs[k] = s to (repl.toByteArray(ISO) + 0.toByte()); ps.masterRenames.add("$m -> $repl") }
                else if (m.lowercase() !in LE_MASTERS) rep.missing.add("$m (needed by ${ps.name})")
            } else if (sg == "STAT" && s == "DNAM" && d.size == 12) {
                subs[k] = s to d.copyOf(8); ps.stat.add("%08X".format(fid))
            } else if (sg == "WEAP" && s == "CRDT" && d.size == 24) {
                // SE: dmg(2) unk(2) mult(4) flags(4) unk(4) effect(4) unk(4)  ->  LE: dmg(2) unused(2) mult(4) flags(4) effect(4)
                subs[k] = s to (d.copyOfRange(0, 12) + d.copyOfRange(16, 20)); ps.weap.add("%08X".format(fid))
            }
        }
        return build(subs)
    }

    private fun walk(d: ByteArray, start: Int, end: Int, rep: Report, ps: PStat): ByteArray {
        val out = ByteArrayOutputStream(end - start)
        var pos = start
        while (pos < end) {
            val sg = sig(d, pos)
            val size = r32(d, pos + 4)
            val hdr = d.copyOfRange(pos, pos + 24)
            if (sg == "GRUP") {
                val gtype = r32(d, pos + 12)
                val label = sig(d, pos + 8)
                if (gtype == 0 && label in SE_ONLY_GROUPS) { ps.dropped.add(label); pos += size; continue }
                if (ps.map.isNotEmpty() && gtype in setOf(1, 6, 7, 8, 9, 10)) w32(hdr, 8, remapId(r32(hdr, 8), ps))
                val body = walk(d, pos + 24, pos + size, rep, ps)
                w32(hdr, 4, 24 + body.size)
                out.write(hdr); out.write(body)
                pos += size
                continue
            }
            val old = r16(hdr, 20); ps.ver[old] = (ps.ver[old] ?: 0) + 1
            ps.types[sg] = (ps.types[sg] ?: 0) + 1
            var flags = r32(d, pos + 8)
            val fid = r32(d, pos + 12)
            var payload = d.copyOfRange(pos + 24, pos + 24 + size)
            if (sg == "TES4" && (flags and ESL) != 0) { ps.esl = true; flags = flags and ESL.inv() }
            val remap = ps.map.isNotEmpty() && sg != "TES4"
            if (sg == "TES4" || sg == "STAT" || sg == "WEAP" || remap) {
                val comp = (flags and COMPRESSED) != 0
                var raw = if (comp) inflate(payload) else payload
                if (sg == "TES4" || sg == "STAT" || sg == "WEAP") raw = patch(sg, raw, fid, rep, ps)
                if (remap) { raw = remapRaw(raw, ps); w32(hdr, 12, remapId(fid, ps)) }
                payload = if (comp) deflate(raw) else raw
            }
            w32(hdr, 4, payload.size); w32(hdr, 8, flags); w16(hdr, 20, 43)
            out.write(hdr); out.write(payload)
            pos += 24 + size
        }
        return out.toByteArray()
    }

    private fun convertPlugin(bytes: ByteArray, path: String, ext: String, rep: Report): Plug {
        val outPath = if (ext == "esl") path.dropLast(3) + "esp" else path
        val base = path.substringAfterLast('/')
        if (bytes.size < 24 || sig(bytes, 0) != "TES4") return Plug(path, base, path, bytes, emptyMap())
        val ps = PStat(base)
        ps.ownIdx = parseSubs(bytes.copyOfRange(24, 24 + r32(bytes, 4)))?.count { it.first == "MAST" } ?: 0
        if ((r32(bytes, 8) and ESL) != 0) {
            val ids = sortedSetOf<Int>(); var maxId = 0; var p = 0
            while (p + 24 <= bytes.size) {
                if (sig(bytes, p) == "GRUP") { p += 24; continue }
                val id = r32(bytes, p + 12)
                if (p > 0 && (id ushr 24) == ps.ownIdx) {
                    val o = id and 0xFFFFFF
                    if (o > maxId) maxId = o
                    if (o in 1..0x7FF) ids.add(o)
                }
                p += 24 + r32(bytes, p + 4)
            }
            if (ps.ownIdx == 0 && ids.isNotEmpty()) {
                ps.skipped = ids.size
                rep.warnings.add("${ps.name}: has ${ids.size} FormID(s) below 000800 but no masters, so references can't be told apart from plain numbers. They were NOT renumbered; renumber them in TES5Edit.")
            } else {
                var next = maxOf(0x800, maxId + 1)
                val m = HashMap<Int, Int>()
                for (o in ids) m[o] = next++
                ps.map = m; ps.nextFree = next
            }
        }
        val out = walk(bytes, 0, bytes.size, rep, ps)
        val n = base
        rep.plugins.add(path)
        if (ps.ver.keys.any { it != 43 })
            rep.changes.add("$n: form version set to 43 on " + ps.ver.entries.joinToString(", ") { "${it.value} item(s) of v${it.key}" })
        if (ps.hedr.isNotEmpty()) rep.changes.add("$n: header version ${ps.hedr}")
        if (ps.esl) rep.changes.add("$n: ESL (light) flag cleared; LE has no light plugins, so it now uses a normal load-order slot")
        if (ps.map.isNotEmpty()) rep.changes.add("$n: renumbered ${ps.map.size} FormID(s) from the SE extended range 000001-0007FF to 000800+ (" +
            ps.map.entries.take(5).joinToString { "%06X->%06X".format(it.key, it.value) } + (if (ps.map.size > 5) ", ..." else "") +
            "); rewrote ${ps.refs} reference(s) in records and group headers")
        ps.masterRenames.forEach { rep.changes.add("$n: master renamed $it") }
        ps.dropped.forEach { rep.changes.add("$n: removed top-level $it group (SE-only record type, not in LE); references to its records were left in place") }
        if (ps.stat.isNotEmpty()) rep.changes.add("$n: STAT DNAM cut 12 -> 8 bytes (SE-only field) on ${ps.stat.size} record(s): " +
            ps.stat.take(30).joinToString(", ") + if (ps.stat.size > 30) " ..." else "")
        if (ps.weap.isNotEmpty()) rep.changes.add("$n: WEAP CRDT (critical-hit data) converted from the SE 24-byte layout to LE's 16-byte layout on ${ps.weap.size} record(s): " +
            ps.weap.take(30).joinToString(", ") + if (ps.weap.size > 30) " ..." else "")
        val review = REVIEW_TYPES.filter { (ps.types[it] ?: 0) > 0 }.joinToString { "$it x${ps.types[it]}" }
        if (review.isNotEmpty()) rep.warnings.add("$n has record types that can carry SE-only data this app does not clean: $review. Run TES5Edit's check for errors on the result.")
        rep.changes.add("$n: all other records copied byte-for-byte (their layouts were not changed)")
        verifyPlugin(out, ps, base, rep)
        return Plug(path, base, outPath, out, ps.map)
    }

    private fun renameAsset(name: String, plugs: List<Plug>, rep: Report): String {
        val segs = name.split('/').toMutableList()
        var map: Map<Int, Int>? = null
        var changed = false
        for (i in 0 until segs.size - 1) for (p in plugs) if (segs[i].equals(p.origBase, true)) {
            val nb = p.outPath.substringAfterLast('/')
            if (nb != p.origBase) { segs[i] = nb; changed = true }
            map = p.map
        }
        val mp = map
        val low = name.lowercase()
        if (mp != null && mp.isNotEmpty() && ("facegendata" in low || "sound/voice" in low)) {
            val f = segs.last()
            val nf = ID_RE.replace(f) { m -> mp[m.groupValues[1].toInt(16)]?.let { "00%06X".format(it) } ?: m.value }
            if (nf != f) { segs[segs.size - 1] = nf; changed = true }
        }
        if (!changed) return name
        val n = segs.joinToString("/")
        rep.changes.add("Renamed $name -> $n")
        return n
    }

    private fun lz4Block(s: ByteArray, start: Int, end: Int, out: ByteArray, o0: Int): Int {
        var i = start; var o = o0
        while (i < end) {
            val t = s[i++].toInt() and 255
            var lit = t ushr 4
            if (lit == 15) do { val b = s[i++].toInt() and 255; lit += b } while (b == 255)
            System.arraycopy(s, i, out, o, lit); i += lit; o += lit
            if (i >= end) break
            val off = (s[i].toInt() and 255) or ((s[i + 1].toInt() and 255) shl 8); i += 2
            var ml = t and 15
            if (ml == 15) do { val b = s[i++].toInt() and 255; ml += b } while (b == 255)
            ml += 4
            var m = o - off
            repeat(ml) { out[o++] = out[m++] }
        }
        return o
    }

    private fun lz4Frame(s: ByteArray, start: Int, outSize: Int): ByteArray {
        var p = start
        if (r32(s, p) != 0x184D2204) throw IOException("bad LZ4 frame")
        val flg = s[p + 4].toInt() and 255
        p += 6
        if ((flg and 8) != 0) p += 8
        if ((flg and 1) != 0) p += 4
        p += 1
        val out = ByteArray(outSize); var o = 0
        while (true) {
            val bs = r32(s, p); p += 4
            if (bs == 0) break
            val n = bs and 0x7FFFFFFF
            if (bs < 0) { System.arraycopy(s, p, out, o, n); o += n } else o = lz4Block(s, p, p + n, out, o)
            p += n
            if ((flg and 16) != 0) p += 4
        }
        return out
    }

    private fun extractBsa(d: ByteArray, emit: (String, ByteArray) -> Unit): Int {
        val flags = r32(d, 12); val nFold = r32(d, 16); val fnLen = r32(d, 28)
        var p = r32(d, 8)
        val counts = IntArray(nFold) { r32(d, p + it * 24 + 8) }
        p += nFold * 24
        val files = ArrayList<Triple<String, Int, Int>>()
        for (c in counts) {
            val n = d[p].toInt() and 255
            val folder = String(d, p + 1, n - 1, ISO)
            p += 1 + n
            repeat(c) { files.add(Triple(folder, r32(d, p + 8), r32(d, p + 12))); p += 16 }
        }
        val names = String(d, p, fnLen, ISO).split('\u0000')
        val defComp = (flags and 4) != 0
        val embed = (flags and 0x100) != 0
        for ((i, f) in files.withIndex()) {
            val (folder, rawSize, off) = f
            val sz = rawSize and 0x3FFFFFFF
            var s = off
            if (embed) s += 1 + (d[s].toInt() and 255)
            val data = if (defComp != ((rawSize and 0x40000000) != 0)) lz4Frame(d, s + 4, r32(d, s)) else d.copyOfRange(s, off + sz)
            emit(folder.replace('\\', '/') + "/" + names[i], data)
        }
        return files.size
    }

    /** Converts SE-only DDS formats to LE-readable ones; on failure keeps the original and lists it for manual conversion. */
    private fun convertTexture(name: String, bytes: ByteArray, rep: Report): ByteArray {
        rep.onProgress?.invoke(name)
        // Shrink first when the file's own mip levels allow it: there are then far fewer blocks to convert.
        var src = bytes
        var dropNote: String? = null
        if (rep.maxTex > 0) {
            try { TextureConverter.dropMips(bytes, rep.maxTex)?.let { src = it.data; dropNote = it.note } } catch (t: Throwable) { }
        }
        var cur = src
        try {
            val r = TextureConverter.convert(src)
            if (r != null) {
                if (TextureConverter.convert(r.data) != null) throw IOException("converted file failed the re-check")
                cur = r.data
                rep.texConverted++
                rep.changes.add("$name: texture converted (${r.note})")
            }
        } catch (t: Throwable) {
            rep.dds.add("$name (${t.message ?: t.javaClass.simpleName})")
            return bytes
        }
        if (dropNote != null) {
            rep.texScaled++
            rep.scaledSaved += (bytes.size - src.size).toLong()
            rep.changes.add("$name: downscaled ($dropNote)")
        }
        if (rep.maxTex > 0) {                    // resample only when the mip levels were not enough
            try {
                val sc = TextureConverter.downscale(cur, rep.maxTex)
                if (sc != null) {
                    if (TextureConverter.convert(sc.data) != null) throw IOException("downscaled file failed the re-check")
                    rep.texScaled++
                    rep.scaledSaved += (cur.size - sc.data.size).toLong()
                    rep.changes.add("$name: downscaled (${sc.note})")
                    cur = sc.data
                }
            } catch (t: Throwable) {
                rep.notes.add("$name: not downscaled (${t.message ?: t.javaClass.simpleName})")
            }
        }
        return cur
    }

    /** Converts static SE meshes to LE. Anything it can't convert safely is kept as-is and listed for Cathedral Assets Optimizer. */
    private fun convertMesh(name: String, bytes: ByteArray, rep: Report): ByteArray {
        rep.onProgress?.invoke(name)
        return try {
            val r = NifConverter.convert(bytes) ?: return bytes
            if (NifConverter.convert(r.data) != null) throw IOException("converted mesh failed the re-check")
            rep.meshConverted++
            rep.changes.add("$name: mesh converted (${r.note})")
            r.data
        } catch (t: Throwable) {
            rep.nif.add("$name (${t.message ?: t.javaClass.simpleName})")
            bytes
        }
    }

    private fun dxgiName(id: Int) = when (id) {
        in 70..72 -> "BC1"; in 73..75 -> "BC2"; in 76..78 -> "BC3"; in 79..81 -> "BC4"; in 82..84 -> "BC5"
        in 94..96 -> "BC6H"; in 97..99 -> "BC7"; in 27..32 -> "R8G8B8A8"; in 87..93 -> "B8G8R8A8/X8"; 61 -> "R8"
        else -> "DXGI $id"
    }

    /** Allow-list: LE (Direct3D 9) reads legacy-header DXT1/3/5 and uncompressed DDS. Anything else is flagged. */
    private fun ddsProblem(h: ByteArray): String? {
        if (h.size < 128 || String(h, 0, 4, ISO) != "DDS ") return null
        if ((r32(h, 80) and 4) == 0) return null   // no FourCC = uncompressed
        return when (val cc = String(h, 84, 4, ISO)) {
            "DXT1", "DXT3", "DXT5" -> null
            "DX10" -> if (h.size >= 132) "DX10 header, ${dxgiName(r32(h, 128))}; LE's loader can't read DX10 headers" else "DX10 header"
            else -> "FourCC '$cc' is not DXT1/DXT3/DXT5"
        }
    }

    private fun classify(name: String, lower: String, ext: String, head: ByteArray, rep: Report) {
        if (ext == "dll" && "skse/plugins/" in lower) rep.skse++
        if (ext == "hkx" && head.size >= 20 && r32(head, 0) == 0x57E0E057 && r32(head, 4) == 0x10C0C010 && (head[16].toInt() and 255) == 8) rep.hkx.add(name)
        if (ext == "dll" && "skse/plugins/" in lower)
            rep.warnings.add("$name is an SKSE plugin DLL: SE DLLs cannot run in LE. Features that depend on it will not work.")
        if (lower.endsWith("_distr.ini") || lower.endsWith("_kid.ini") || lower.endsWith("_swap.ini") || "skypatcher" in lower)
            rep.warnings.add("$name is an SPID/KID/SkyPatcher file: it only works with that framework installed for LE.")
    }


    private val SCRIPT_LIBS = listOf("skse", "papyrusutil", "miscutil", "jcontainers", "consoleutil", "nioverride", "skyuilib", "uiextensions", "po3_")

    private class V(val ownIdx: Int, val missing: Map<Int, String>, val light: Boolean) {
        var err: String? = null
        var records = 0; var badVer = 0; var low = 0; var dup = 0; var stat12 = 0
        var crdt24 = 0; var badZlib = 0; var leftover = 0; var dropped = 0; var maxOwn = 0
        val ids = HashSet<Int>()
        val overMissing = sortedMapOf<String, Int>()
    }

    private fun countLow(raw: ByteArray, ownIdx: Int): Int {
        val parts = parseSubs(raw)?.filter { !isText(it.second) }?.map { it.second } ?: listOf(raw)
        var n = 0
        for (d in parts) {
            var i = 0
            while (i + 4 <= d.size) {
                if ((d[i + 3].toInt() and 255) == ownIdx && (r32(d, i) and 0xFFFFFF) in 1..0x7FF) { n++; i += 4 } else i++
            }
        }
        return n
    }

    private fun vWalk(d: ByteArray, start: Int, end: Int, v: V) {
        var pos = start
        while (pos < end && v.err == null) {
            if (pos + 24 > end) { v.err = "truncated header at byte $pos"; return }
            val sg = sig(d, pos)
            val size = r32(d, pos + 4)
            if (size < 0) { v.err = "bad size at byte $pos"; return }
            if (sg != "GRUP" && r16(d, pos + 20) != 43) v.badVer++
            if (sg == "GRUP") {
                if (size < 24 || pos + size > end) { v.err = "group at byte $pos overruns its parent"; return }
                if (r32(d, pos + 12) == 0 && sig(d, pos + 8) in SE_ONLY_GROUPS) v.dropped++
                vWalk(d, pos + 24, pos + size, v)
                pos += size
                continue
            }
            if (pos + 24 + size > end) { v.err = "record $sg at byte $pos overruns its group"; return }
            v.records++
            val fid = r32(d, pos + 12)
            val flags = r32(d, pos + 8)
            if (sg != "TES4") {
                if ((fid ushr 24) == v.ownIdx) {
                    val o = fid and 0xFFFFFF
                    if (!v.ids.add(fid)) v.dup++
                    if (o > v.maxOwn) v.maxOwn = o
                    if (v.light && o in 1..0x7FF) v.low++
                } else v.missing[fid ushr 24]?.let { v.overMissing[it] = (v.overMissing[it] ?: 0) + 1 }
            }
            var payload = d.copyOfRange(pos + 24, pos + 24 + size)
            if ((flags and COMPRESSED) != 0) {
                try {
                    val raw = inflate(payload)
                    if (r32(payload, 0) != raw.size) v.badZlib++
                    payload = raw
                } catch (e: Exception) { v.badZlib++; payload = ByteArray(0) }
            }
            if (sg == "WEAP") parseSubs(payload)?.forEach { if (it.first == "CRDT" && it.second.size == 24) v.crdt24++ }
            if (sg == "STAT") parseSubs(payload)?.forEach { if (it.first == "DNAM" && it.second.size == 12) v.stat12++ }
            if (v.light && sg != "TES4") v.leftover += countLow(payload, v.ownIdx)
            pos += 24 + size
        }
    }

    private fun verifyPlugin(out: ByteArray, ps: PStat, base: String, rep: Report) {
        val hs = parseSubs(out.copyOfRange(24, 24 + r32(out, 4))) ?: emptyList()
        val masters = hs.filter { it.first == "MAST" }.map { String(it.second, ISO).trimEnd('\u0000') }
        val renamed = MASTER_RENAME.values.map { it.lowercase() }.toSet()
        val missing = HashMap<Int, String>()
        masters.forEachIndexed { i, m -> val l = m.lowercase(); if (l !in LE_MASTERS && l !in renamed) missing[i] = m }
        val light = ps.esl && ps.skipped == 0
        val v = V(ps.ownIdx, missing, light)
        vWalk(out, 0, out.size, v)
        val hedr = hs.firstOrNull { it.first == "HEDR" }?.second?.takeIf { it.size == 12 }
        val hv = if (hedr != null) java.lang.Float.intBitsToFloat(r32(hedr, 0)) else 0f
        fun c(ok: Boolean, pass: String, fail: String, level: String = "FAIL") =
            rep.check(if (ok) "PASS" else level, "$base: " + (if (ok) pass else fail))
        c(v.err == null, "file structure is consistent (${v.records} records)", "structure is broken: ${v.err}")
        c(v.badVer == 0, "every record is form version 43", "${v.badVer} record(s) are not form version 43")
        c(hedr != null && Math.abs(hv - 1.70f) < 0.005f, "header version is 1.70", "header version is not 1.70")
        c((r32(out, 8) and ESL) == 0, "ESL flag is clear", "ESL flag is still set")
        c(masters.size == masters.map { it.lowercase() }.toSet().size, "no duplicate masters", "duplicate masters in the master list")
        c(v.dup == 0, "no duplicate FormIDs", "${v.dup} duplicate FormID(s)")
        c(v.badZlib == 0, "every compressed record inflates correctly", "${v.badZlib} compressed record(s) are corrupt")
        c(v.crdt24 == 0, "no 24-byte WEAP CRDT left", "${v.crdt24} WEAP record(s) still have the SE critical-hit layout")
        c(v.stat12 == 0, "no 12-byte STAT DNAM left", "${v.stat12} STAT record(s) still have the SE 12-byte DNAM")
        c(v.dropped == 0, "no SE-only record groups (LENS/VOLI) remain", "SE-only record groups remain")
        if (light) {
            c(v.low == 0, "no own FormIDs left in the 000001-0007FF range", "${v.low} own record(s) are still below 000800")
            c(hedr != null && r32(hedr, 8) > v.maxOwn, "next-object-ID counter is above every own FormID", "next-object-ID counter is not above the highest own FormID")
            c(v.leftover == 0, "no stale references to renumbered FormIDs", "${v.leftover} possible stale reference(s) to old IDs", "WARN")
        } else if (ps.skipped > 0) rep.check("WARN", "$base: ${ps.skipped} FormID(s) below 000800 could not be renumbered safely (plugin has no masters)")
        if (v.overMissing.isEmpty()) rep.check("PASS", "$base: no record overrides a form from a missing master")
        else v.overMissing.forEach { (m, n) -> rep.check("FAIL", "$base: $n record(s) override forms from missing master $m (they will break in LE)") }
    }

    private fun scanScript(name: String, data: ByteArray, plugs: List<Plug>, rep: Report) {
        val ext = name.substringAfterLast('.').lowercase()
        var issues = 0
        if (ext == "pex") {
            rep.pex++
            val ok = data.size >= 4 && (data[0].toInt() and 255) == 0xFA && (data[1].toInt() and 255) == 0x57 &&
                (data[2].toInt() and 255) == 0xC0 && (data[3].toInt() and 255) == 0xDE
            if (!ok) { rep.check("FAIL", "$name is not a valid compiled script (bad PEX header)"); rep.scriptIssues++; return }
        }
        val low = String(data, ISO).lowercase()
        for (p in plugs) {
            val outB = p.outPath.substringAfterLast('/')
            if (p.map.isEmpty() && outB == p.origBase) continue
            if (!low.contains(p.origBase.lowercase())) continue
            if (outB != p.origBase) { rep.check("WARN", "$name refers to plugin \"${p.origBase}\", which is now \"$outB\""); issues++ }
            if (p.map.isNotEmpty()) {
                val hits = sortedSetOf<Int>()
                if (ext == "pex") {
                    for (i in 0 until data.size - 4) if (data[i].toInt() == 3 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0) {
                        val id = ((data[i + 3].toInt() and 255) shl 8) or (data[i + 4].toInt() and 255)
                        if (id in p.map) hits.add(id)
                    }
                } else Regex("0x0*([0-9a-f]{1,6})").findAll(low).forEach { val id = it.groupValues[1].toInt(16); if (id in p.map) hits.add(id) }
                if (hits.isNotEmpty()) {
                    rep.check("WARN", "$name may hardcode renumbered FormID(s): " + hits.take(6).joinToString { "%06X->%06X".format(it, p.map[it]) } + " (check the script)")
                    issues++
                }
            }
        }
        val libs = SCRIPT_LIBS.filter { low.contains(it) }
        if (libs.isNotEmpty()) { rep.check("WARN", "$name uses SKSE/third-party library code (${libs.joinToString()}); the LE build of each must be installed"); issues++ }
        if (issues > 0) rep.scriptIssues++
    }

    private fun finalChecks(rep: Report, plugs: List<Plug>) {
        rep.check(if (plugs.isEmpty()) "WARN" else "PASS", if (plugs.isEmpty()) "no plugin found in the zip" else "${plugs.size} plugin(s) found and converted")
        rep.check(if (plugs.none { it.outPath.lowercase().endsWith(".esl") }) "PASS" else "FAIL", "output contains no .esl files")
        rep.check(if (rep.missing.isEmpty()) "PASS" else "FAIL", if (rep.missing.isEmpty()) "every master exists in LE" else "${rep.missing.size} master(s) do not exist in LE")
        rep.check(if (rep.bsaFailed == 0) "PASS" else "FAIL", if (rep.bsaFailed == 0) "no SE archive was left unextracted" else "${rep.bsaFailed} SE archive(s) could not be extracted")
        if (rep.meshConverted > 0) {
            rep.check("PASS", "${rep.meshConverted} mesh(es) converted to LE format and structurally re-checked")
            rep.notes.add("Converted meshes were checked against real LE and SE files but not loaded in the game. Test one in game or in NifSkope before trusting the rest.")
        }
        rep.check(if (rep.nif.isEmpty()) "PASS" else "WARN", if (rep.nif.isEmpty()) "every mesh is LE format" else "${rep.nif.size} mesh(es) could not be converted and are still SE format")
        if (rep.maxTex > 0) {
            rep.check("PASS", "texture size limit ${rep.maxTex} px: ${rep.texScaled} texture(s) downscaled, " +
                "%.1f MB less data before zip compression".format(rep.scaledSaved / 1048576.0))
            rep.notes.add("Texture size limit was ${rep.maxTex} px; textures already that small were left alone, and the limit applies to the larger side.")
        }
        if (rep.texConverted > 0) rep.check("PASS", "${rep.texConverted} texture(s) converted to LE-readable DDS and re-checked")
        rep.check(if (rep.dds.isEmpty()) "PASS" else "WARN", if (rep.dds.isEmpty()) "every DDS texture is LE-readable" else "${rep.dds.size} texture(s) could not be converted and won't load in LE")
        rep.check(if (rep.hkx.isEmpty()) "PASS" else "WARN", if (rep.hkx.isEmpty()) "no 64-bit animations found" else "${rep.hkx.size} SE animation(s) still need converting")
        rep.check(if (rep.skse == 0) "PASS" else "WARN", if (rep.skse == 0) "no SKSE plugin DLLs found" else "${rep.skse} SKSE plugin DLL(s) cannot run in LE")
        rep.check(if (rep.scriptIssues == 0) "PASS" else "WARN", "${rep.pex} compiled script(s) scanned, ${rep.scriptIssues} script(s) with issues")
    }

    private fun norm(n: String) = n.replace('\\', '/').trimStart('/')

    fun detect(f: File): String {
        val h = ByteArray(512)
        val n = f.inputStream().use { it.read(h) }
        fun starts(vararg b: Int) = n >= b.size && b.indices.all { (h[it].toInt() and 255) == b[it] }
        return when {
            starts(0x50, 0x4B) -> "zip"
            starts(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> "7z"
            starts(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00) -> "rar5"
            starts(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> "rar"
            starts(0x1F, 0x8B) -> "gz"
            starts(0x42, 0x5A, 0x68) -> "bz2"
            starts(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> "xz"
            n >= 262 && String(h, 257, 5, ISO) == "ustar" -> "tar"
            else -> "unknown"
        }
    }

    /** Calls action(name, stream) for each file in any supported archive. The stream is only valid inside the call. */
    private fun forEachEntry(f: File, action: (String, InputStream) -> Unit) {
        when (val kind = detect(f)) {
            "zip" -> ZipInputStream(BufferedInputStream(f.inputStream())).use { z ->
                while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) action(norm(e.name), z) }
            }
            "7z" -> SevenZFile.builder().setFile(f).get().use { sz ->
                val st = object : InputStream() {
                    override fun read(): Int = sz.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int = sz.read(b, off, len)
                }
                while (true) { val e = sz.nextEntry ?: break; if (!e.isDirectory) action(norm(e.name), st) }
            }
            "rar" -> Archive(f).use { a ->
                for (h in a.fileHeaders) {
                    if (h.isDirectory) continue
                    val bo = ByteArrayOutputStream()
                    a.extractFile(h, bo)
                    action(norm(h.fileName), ByteArrayInputStream(bo.toByteArray()))
                }
            }
            "rar5" -> throw IOException("RAR5 archives are not supported by the bundled RAR library. Re-pack the mod as .7z or .zip (7-Zip or WinRAR) and try again.")
            "gz", "bz2", "xz", "tar" -> {
                val raw = BufferedInputStream(f.inputStream())
                val st: InputStream = when (kind) {
                    "gz" -> GZIPInputStream(raw)
                    "bz2" -> BZip2CompressorInputStream(raw)
                    "xz" -> XZInputStream(raw)
                    else -> raw
                }
                TarArchiveInputStream(st).use { t ->
                    while (true) { val e = t.nextEntry ?: break; if (!e.isDirectory) action(norm(e.name), t) }
                }
            }
            else -> throw IOException("Unrecognised archive format. Supported: zip, 7z, rar (not RAR5), tar, tar.gz, tar.bz2, tar.xz.")
        }
    }

    fun run(input: File, output: OutputStream, rep: Report) {
        // Pass 1: convert plugins first, so assets can be renamed to match renumbered FormIDs.
        val plugs = ArrayList<Plug>()
        var total = 0
        fun onEntry(name: String, s: InputStream) {
            val ext = name.lowercase().substringAfterLast('.', "")
            if (ext in PLUGIN_EXT) plugs.add(convertPlugin(s.readBytes(), name, ext, rep))
        }
        // A zip has a central directory, so the first pass can read just the plugins instead of inflating every file.
        val zf = if (detect(input) == "zip") try { ZipFile(input) } catch (e: Exception) { null } else null
        if (zf != null) {
            zf.use {
                for (e in java.util.Collections.list(it.entries())) {
                    if (e.isDirectory) continue
                    total++
                    val n = norm(e.name)
                    if (n.lowercase().substringAfterLast('.', "") in PLUGIN_EXT) it.getInputStream(e).use { st -> onEntry(n, st) }
                }
            }
        } else forEachEntry(input) { name, s -> total++; onEntry(name, s) }
        if (total == 0) throw IOException("No files found in the archive (detected format: ${detect(input)})")
        rep.notes.add("Input archive format: ${detect(input)}; output is a .zip")
        ZipOutputStream(BufferedOutputStream(output)).use { zout ->
            zout.setLevel(Deflater.BEST_SPEED)
            val seen = HashSet<String>()
            fun emit(name: String, data: ByteArray): Boolean {
                val n = renameAsset(name, plugs, rep)
                if (!seen.add(n.lowercase())) return false
                val e2 = n.substringAfterLast('.', "").lowercase()
                if (e2 == "pex" || e2 == "psc") scanScript(n, data, plugs, rep)
                val out = if (e2 == "dds") convertTexture(n, data, rep) else if (e2 == "nif") convertMesh(n, data, rep) else data
                zout.putNextEntry(ZipEntry(n)); zout.write(out); zout.closeEntry()
                return out === data
            }
            for (p in plugs) {
                if (p.outPath != p.path) rep.changes.add("Renamed ${p.path} -> ${p.outPath}")
                seen.add(p.outPath.lowercase()); zout.putNextEntry(ZipEntry(p.outPath)); zout.write(p.data); zout.closeEntry()
            }
            val junk = setOf("desktop.ini", "thumbs.db", ".ds_store")
            val junkExt = setOf("pdb", "bak", "tmp", "lnk")
            val buf = ByteArray(65536)
            forEachEntry(input) { name, s ->
                val lower = name.lowercase()
                val ext = lower.substringAfterLast('.', "")
                if (name.split('/').contains("..")) { rep.notes.add("Skipped unsafe path $name"); return@forEachEntry }
                if (ext in PLUGIN_EXT) return@forEachEntry
                if (lower.substringAfterLast('/') in junk || ext in junkExt) { rep.changes.add("Removed junk file: $name"); return@forEachEntry }
                if (ext == "bsa") {
                    try {
                        val bytes = s.readBytes()
                        if (bytes.size > 36 && r32(bytes, 4) == 105) {
                            val n = extractBsa(bytes) { nm, data -> emit(nm, data) }
                            rep.changes.add("$name: SE archive (v105) extracted to $n loose file(s); original archive removed (LE can't load it)")
                        } else { emit(name, bytes); rep.kept++ }
                    } catch (t: Throwable) {
                        rep.bsaFailed++
                        rep.warnings.add("$name could not be extracted (${t.javaClass.simpleName}); it is NOT in the output. Extract it with BSArch on a PC.")
                    }
                    return@forEachEntry
                }
                if (ext == "pex" || ext == "psc" || ext == "dds" || ext == "nif") { if (emit(name, s.readBytes())) rep.kept++; return@forEachEntry }
                val outName = renameAsset(name, plugs, rep)
                if (!seen.add(outName.lowercase())) { rep.notes.add("Skipped duplicate entry $name"); return@forEachEntry }
                val head = ByteArrayOutputStream()
                zout.putNextEntry(ZipEntry(outName))
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    zout.write(buf, 0, n)
                    if (head.size() < 256) head.write(buf, 0, minOf(n, 256 - head.size()))
                }
                zout.closeEntry()
                rep.kept++
                classify(name, lower, ext, head.toByteArray(), rep)
            }
            finalChecks(rep, plugs)
            if (rep.embedReport) {
                // The report name contains the mod's name, so reports from different mods never collide in a mod manager.
                val safe = rep.modName.replace(Regex("[^A-Za-z0-9 ._()\\-]"), "_").trim().take(80).ifEmpty { "mod" }
                var reportName = "${safe}_BACKPORT_REPORT.md"
                var k = 2
                while (!seen.add(reportName.lowercase())) reportName = "${safe}_BACKPORT_REPORT_${k++}.md"
                zout.putNextEntry(ZipEntry(reportName))
                zout.write(rep.text().toByteArray()); zout.closeEntry()
            }
        }
    }
}

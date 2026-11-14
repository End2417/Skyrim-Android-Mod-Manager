package com.example.skyrimmm

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

data class FFile(val src: String, val dst: String, val folder: Boolean, val priority: Int)

const val GAME_VERSION = "1.9.32.0" // Skyrim Legendary Edition final patch

fun verAtLeast(have: String, need: String): Boolean {
    val a = have.split('.').map { it.toIntOrNull() ?: 0 }; val b = need.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return x > y }
    return true
}

fun normPath(p: String) = p.replace('\\', '/').split('/').filter { it.isNotEmpty() }.joinToString("/").lowercase()

class Cond(val op: String, val flags: List<Pair<String, String>>, val files: List<Pair<String, String>>,
           val game: List<String>, val kids: List<Cond>) {
    fun collect(out: MutableSet<String>) { files.forEach { out += it.first }; kids.forEach { it.collect(out) } }
    fun eval(set: Map<String, String>, has: (String) -> Boolean): Boolean {
        val r = flags.map { (k, v) -> set[k] == v } +
                files.map { (f, s) -> has(f) == s.equals("Active", true) } +
                game.map { verAtLeast(GAME_VERSION, it) } +
                kids.map { it.eval(set, has) }
        return if (op.equals("Or", true)) r.any { it } else r.all { it }
    }
}

class FPlugin(val name: String, val desc: String, val defType: String, val patterns: List<Pair<Cond, String>>,
              val files: List<FFile>, val setFlags: Map<String, String>, val image: String) {
    fun type(flags: Map<String, String>, has: (String) -> Boolean) =
        patterns.firstOrNull { it.first.eval(flags, has) }?.second ?: defType
}
class FGroup(val name: String, val type: String, val plugins: List<FPlugin>)
class FStep(val name: String, val visible: Cond?, val groups: List<FGroup>)
class FomodConfig(val required: List<FFile>, val steps: List<FStep>, val conditional: List<Pair<Cond, List<FFile>>>)
class FomodPack(val config: FomodConfig, val prefix: String, val names: List<String>)

private fun Element.kids(n: String) = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName.equals(n, true) }
private fun Element.kid(n: String) = kids(n).firstOrNull()

private fun files(e: Element?): List<FFile> = e?.let { el ->
    (el.kids("file").map { false to it } + el.kids("folder").map { true to it }).map { (fo, x) ->
        FFile(x.getAttribute("source"), x.getAttribute("destination"), fo, x.getAttribute("priority").toIntOrNull() ?: 0)
    }
}.orEmpty()

private fun cond(e: Element?): Cond? = e?.let { el ->
    Cond(el.getAttribute("operator").ifEmpty { "And" },
        el.kids("flagDependency").map { it.getAttribute("flag") to it.getAttribute("value") },
        el.kids("fileDependency").map { it.getAttribute("file") to it.getAttribute("state") },
        el.kids("gameDependency").map { it.getAttribute("version") },
        el.kids("dependencies").mapNotNull { cond(it) })
}

private fun <T> List<T>.ordered(order: String?, name: (T) -> String) = when (order?.lowercase()) {
    "ascending" -> sortedBy { name(it).lowercase() }
    "descending" -> sortedByDescending { name(it).lowercase() }
    else -> this
}

fun parseFomod(xml: ByteArray): FomodConfig {
    val r = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.inputStream()).documentElement
    fun plugin(p: Element): FPlugin {
        val td = p.kid("typeDescriptor"); val dt = td?.kid("dependencyType")
        return FPlugin(p.getAttribute("name"), p.kid("description")?.textContent?.trim().orEmpty(),
            dt?.kid("defaultType")?.getAttribute("name") ?: td?.kid("type")?.getAttribute("name") ?: "Optional",
            dt?.kid("patterns")?.kids("pattern").orEmpty().mapNotNull { pt ->
                cond(pt.kid("dependencies"))?.let { it to (pt.kid("type")?.getAttribute("name") ?: "Optional") } },
            files(p.kid("files")),
            p.kid("conditionFlags")?.kids("flag").orEmpty().associate { it.getAttribute("name") to it.textContent.trim() },
            p.kid("image")?.getAttribute("path").orEmpty())
    }
    fun group(g: Element) = FGroup(g.getAttribute("name"), g.getAttribute("type"),
        g.kid("plugins")?.let { ps -> ps.kids("plugin").map { plugin(it) }.ordered(ps.getAttribute("order")) { it.name } }.orEmpty())
    fun step(s: Element) = FStep(s.getAttribute("name"), cond(s.kid("visible")),
        s.kid("optionalFileGroups")?.let { gs -> gs.kids("group").map { group(it) }.ordered(gs.getAttribute("order")) { it.name } }.orEmpty())
    val steps = r.kid("installSteps")?.let { ss -> ss.kids("installStep").map { step(it) }.ordered(ss.getAttribute("order")) { it.name } }.orEmpty()
    val conditional = r.kid("conditionalFileInstalls")?.kid("patterns")?.kids("pattern").orEmpty().mapNotNull { pt ->
        cond(pt.kid("dependencies"))?.let { it to files(pt.kid("files")) } }
    return FomodConfig(files(r.kid("requiredInstallFiles")), steps, conditional)
}

/** Drives the installer pages: visibility, defaults, selection rules and the final file list. */
class FomodWizard(val cfg: FomodConfig, val has: (String) -> Boolean, val images: Map<String, Bitmap> = emptyMap()) {
    val picked = mutableStateMapOf<Triple<Int, Int, Int>, Boolean>()
    var cur by mutableStateOf(0)
    val done get() = cur >= cfg.steps.size

    init { cur = nextVisible(-1); if (!done) enter() }

    private fun walk(upTo: Int): Pair<Map<String, String>, List<FPlugin>> {
        val flags = mutableMapOf<String, String>(); val chosen = mutableListOf<FPlugin>()
        for ((i, s) in cfg.steps.withIndex()) {
            if (i >= upTo) break
            if (s.visible?.eval(flags, has) == false) continue
            s.groups.forEachIndexed { gi, g -> g.plugins.forEachIndexed { pi, p ->
                if (picked[Triple(i, gi, pi)] == true) { chosen += p; flags.putAll(p.setFlags) } } }
        }
        return flags to chosen
    }
    fun flagsAt(step: Int) = walk(step).first
    private fun visible(i: Int) = cfg.steps[i].visible?.eval(flagsAt(i), has) != false
    private fun nextVisible(from: Int): Int { for (i in from + 1 until cfg.steps.size) if (visible(i)) return i; return cfg.steps.size }
    private fun prevVisible(from: Int): Int { for (i in from - 1 downTo 0) if (visible(i)) return i; return -1 }
    fun hasNext() = nextVisible(cur) < cfg.steps.size
    fun hasPrev() = prevVisible(cur) >= 0
    fun next() { cur = nextVisible(cur); if (!done) enter() }
    fun back() { prevVisible(cur).takeIf { it >= 0 }?.let { cur = it } }

    private fun enter() {
        val i = cur; val fl = flagsAt(i)
        cfg.steps[i].groups.forEachIndexed { gi, g ->
            if (g.plugins.indices.any { picked.containsKey(Triple(i, gi, it)) }) return@forEachIndexed
            val types = g.plugins.map { it.type(fl, has) }
            var sel = types.map { it == "Required" || it == "Recommended" || g.type == "SelectAll" }
            val single = g.type == "SelectExactlyOne" || g.type == "SelectAtMostOne"
            if (single) { val f = sel.indexOf(true); sel = sel.indices.map { it == f } }
            if (sel.none { it } && (g.type == "SelectExactlyOne" || g.type == "SelectAtLeastOne")) {
                val u = types.indexOfFirst { it != "NotUsable" }; sel = sel.indices.map { it == u }
            }
            sel.forEachIndexed { pi, b -> picked[Triple(i, gi, pi)] = b && types[pi] != "NotUsable" }
        }
    }

    fun toggle(i: Int, gi: Int, pi: Int) {
        val g = cfg.steps[i].groups[gi]; val k = Triple(i, gi, pi); val was = picked[k] == true
        when (g.type) {
            "SelectExactlyOne", "SelectAtMostOne" -> {
                g.plugins.indices.forEach { picked[Triple(i, gi, it)] = false }
                picked[k] = if (g.type == "SelectAtMostOne") !was else true
            }
            "SelectAll" -> {}
            else -> picked[k] = !was
        }
    }

    /** Explains why the current page can't be advanced yet, or null if its selection rules are met. */
    fun problem(): String? {
        val fl = flagsAt(cur)
        return cfg.steps[cur].groups.withIndex().firstNotNullOfOrNull { (gi, g) ->
            val n = g.plugins.indices.count { picked[Triple(cur, gi, it)] == true }
            val usable = g.plugins.any { it.type(fl, has) != "NotUsable" }
            when {
                g.type == "SelectExactlyOne" && usable && n != 1 -> "Choose exactly one option in \"${g.name}\""
                g.type == "SelectAtLeastOne" && usable && n < 1 -> "Choose at least one option in \"${g.name}\""
                g.type == "SelectAtMostOne" && n > 1 -> "Choose at most one option in \"${g.name}\""
                else -> null
            }
        }
    }

    /** Required files, then chosen options, then conditional installs (later entries win equal-priority ties). */
    fun result(): List<FFile> {
        val (flags, chosen) = walk(cfg.steps.size)
        return cfg.required + chosen.flatMap { it.files } +
                cfg.conditional.filter { it.first.eval(flags, has) }.flatMap { it.second }
    }
}

/** Every file path the config's conditions ask about, so they can be looked up once up front. */
fun FomodConfig.fileDeps(): Set<String> {
    val out = mutableSetOf<String>()
    steps.forEach { s -> s.visible?.collect(out); s.groups.forEach { g -> g.plugins.forEach { p -> p.patterns.forEach { it.first.collect(out) } } } }
    conditional.forEach { it.first.collect(out) }
    return out
}

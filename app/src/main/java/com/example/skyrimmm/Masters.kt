package com.example.skyrimmm

/** What a plugin's header says: its master flag and the plugins it depends on. */
class PluginInfo(val name: String, val isMaster: Boolean, val masters: List<String>)

/** Headers of the plugins in the load order, plus the names of every file present in Data (lower-case). */
class PluginScan(val info: Map<String, PluginInfo>, val present: Set<String>)

data class Issue(val plugin: String, val text: String)

/** One file that two enabled mods both wrote. `wins` is true when `other` is the older mod and this mod's copy is the live one. */
data class Conflict(val path: String, val other: Mod, val wins: Boolean)

private val IMPLICIT = setOf("skyrim.esm", "update.esm")
private val OFFICIAL = listOf("skyrim.esm", "update.esm", "dawnguard.esm", "hearthfires.esm", "dragonborn.esm")

private fun PluginScan.flagged(name: String) = info[name.lowercase()]?.isMaster ?: name.endsWith(".esm", true)

/** Dependency problems in the current order: missing or inactive masters, plugins loading before a master, regular plugins ahead of master-flagged ones. */
fun checkMasters(order: List<PluginEntry>, scan: PluginScan): List<Issue> {
    val issues = mutableListOf<Issue>()
    val pos = HashMap<String, Int>()
    order.forEachIndexed { i, e -> pos.putIfAbsent(e.name.lowercase(), i) }
    val active = order.filter { it.active }.map { it.name.lowercase() }.toSet()
    order.forEachIndexed { i, e ->
        if (!e.active) return@forEachIndexed
        val info = scan.info[e.name.lowercase()] ?: return@forEachIndexed
        for (m in info.masters) {
            val k = m.lowercase()
            when {
                k in IMPLICIT && k in scan.present -> {}
                k !in scan.present -> issues += Issue(e.name, "needs $m, which is not in your Data folder")
                k !in active -> issues += Issue(e.name, "needs $m, which is not active")
                (pos[k] ?: -1) > i -> issues += Issue(e.name, "loads before its master $m")
            }
        }
    }
    var firstRegular: String? = null
    for (e in order) {
        if (!e.active) continue
        if (!scan.flagged(e.name)) { if (firstRegular == null) firstRegular = e.name }
        else if (firstRegular != null) issues += Issue(e.name, "is a master but loads after $firstRegular")
    }
    return issues
}

/**
 * Stable dependency sort (not LOOT): official masters first, master-flagged plugins before regular ones, every plugin after the
 * plugins it depends on. Everything else keeps its current relative order.
 */
fun fixOrder(order: List<PluginEntry>, scan: PluginScan): List<PluginEntry> {
    val n = order.size
    val keys = order.map { it.name.lowercase() }
    val index = HashMap<String, Int>(); keys.forEachIndexed { i, k -> index.putIfAbsent(k, i) }
    val deps = order.indices.map { i -> scan.info[keys[i]]?.masters.orEmpty().mapNotNull { index[it.lowercase()] }.filter { it != i } }
    val rank = order.indices.map { i ->
        val off = OFFICIAL.indexOf(keys[i])
        Triple(if (off >= 0) off else OFFICIAL.size, if (scan.flagged(order[i].name)) 0 else 1, i)
    }
    val cmp = compareBy<Triple<Int, Int, Int>>({ it.first }, { it.second }, { it.third })
    val placed = BooleanArray(n); val out = ArrayList<PluginEntry>(n)
    repeat(n) {
        var best = -1
        for (i in 0 until n) {
            if (placed[i] || !deps[i].all { placed[it] }) continue
            if (best < 0 || cmp.compare(rank[i], rank[best]) < 0) best = i
        }
        if (best < 0) best = (0 until n).first { !placed[it] } // dependency cycle: keep the original order for the rest
        placed[best] = true; out += order[best]
    }
    return out
}

/** For every enabled mod: the files it shares with other enabled mods. The mod installed last wins. */
fun conflictMap(mods: List<Mod>): Map<String, List<Conflict>> {
    val owners = HashMap<String, MutableList<Mod>>()
    for (m in mods.filter { it.enabled }.sortedBy { it.time }) for (f in m.files) owners.getOrPut(normPath(f)) { mutableListOf() } += m
    val out = HashMap<String, MutableList<Conflict>>()
    for ((path, ms) in owners) {
        if (ms.size < 2) continue
        for (i in ms.indices) for (j in ms.indices) if (i != j) out.getOrPut(ms[i].id) { mutableListOf() } += Conflict(path, ms[j], i > j)
    }
    return out
}

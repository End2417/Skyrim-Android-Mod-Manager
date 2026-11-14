package com.example.skyrimmm

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.github.junrar.Junrar
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.Closeable
import java.io.File
import java.io.InputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.tukaani.xz.LZMAInputStream
import java.io.DataInputStream

/** A readable mod archive. forEach visits file entries in order; return false from the callback to stop early. */
interface Arch : Closeable {
    fun names(): List<String>
    fun forEach(block: (name: String, stream: InputStream) -> Boolean)
}

/** Keeps one staged copy of a 7z/RAR archive on disk so it is copied (and RAR extracted) only once per install. */
object Stage {
    private var key: Uri? = null
    fun dir(ctx: Context, uri: Uri): File {
        val d = File(ctx.cacheDir, "staged")
        if (key != uri || !File(d, "a.bin").exists()) {
            clear(ctx); d.mkdirs()
            val total = DocumentFile.fromSingleUri(ctx, uri)?.length() ?: 0L
            ctx.contentResolver.openInputStream(uri)!!.use { i -> File(d, "a.bin").outputStream().use { o ->
                val buf = ByteArray(1 shl 16); var done = 0L
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    Progress.report("Reading archive", "Copying archive… ${done shr 20} MB" + (if (total > 0) " of ${total shr 20} MB" else ""),
                        if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null)
                }
            } }
            key = uri
        }
        return d
    }
    fun clear(ctx: Context) { File(ctx.cacheDir, "staged").deleteRecursively(); key = null }
}

fun openArchive(ctx: Context, uri: Uri): Arch {
    val head = ByteArray(8)
    ctx.contentResolver.openInputStream(uri)!!.use { it.read(head) }
    fun starts(vararg b: Int) = b.indices.all { head[it] == b[it].toByte() }
    return when {
        starts(0x50, 0x4B) -> ZipArch(ctx, uri)
        starts(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> SevenArch(ctx, uri)
        starts(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> RarArch(ctx, uri)
        else -> error("Unsupported archive format (supported: zip, 7z, rar)")
    }
}

/** Reads zips with commons-compress, which (unlike java.util.zip) handles Deflate64 and BZip2; LZMA entries (method 14) are decoded here. */
private class ZipArch(ctx: Context, uri: Uri) : Arch {
    private val zip = ZipFile(File(Stage.dir(ctx, uri), "a.bin"))
    private fun files() = zip.entriesInPhysicalOrder.asSequence().filter { !it.isDirectory }
    override fun names() = files().map { it.name }.toList()

    private fun open(e: ZipArchiveEntry): InputStream = when {
        e.generalPurposeBit.usesEncryption() -> error("Password-protected archives are not supported (${e.name}).")
        zip.canReadEntryData(e) -> zip.getInputStream(e)
        e.method == 14 -> lzmaZipStream(zip.getRawInputStream(e), e.size)
        else -> error("Unsupported zip compression method ${e.method} (${e.name}). Re-pack the mod as a normal zip or a 7z.")
    }

    override fun forEach(block: (String, InputStream) -> Boolean) {
        for (e in files()) open(e).use { if (!block(e.name, it)) return }
    }
    override fun close() = zip.close()
}

/** Zip's LZMA entries: 2 bytes version, 2 bytes properties size, 5 bytes properties, then the raw LZMA data. */
private fun lzmaZipStream(raw: InputStream, size: Long): InputStream {
    val h = ByteArray(9)
    DataInputStream(raw).readFully(h)
    val dict = (h[5].toInt() and 0xFF) or ((h[6].toInt() and 0xFF) shl 8) or ((h[7].toInt() and 0xFF) shl 16) or ((h[8].toInt() and 0xFF) shl 24)
    return LZMAInputStream(raw, size, h[4], dict)
}

private class SevenArch(ctx: Context, uri: Uri) : Arch {
    private val sz = SevenZFile(File(Stage.dir(ctx, uri), "a.bin"))
    override fun names() = sz.entries.filter { !it.isDirectory }.map { it.name }
    override fun forEach(block: (String, InputStream) -> Boolean) {
        for (e in sz.entries) { if (e.isDirectory) continue; if (!block(e.name, sz.getInputStream(e))) break }
    }
    override fun close() = sz.close()
}

/** RAR4 only (and not encrypted or multi-part): extracted once into the staging folder, then read from there. */
private class RarArch(ctx: Context, uri: Uri) : Arch {
    private val dir = File(Stage.dir(ctx, uri), "rar").also { d ->
        if (!d.exists()) {
            d.mkdirs()
            try { Junrar.extract(File(d.parentFile, "a.bin"), d) }
            catch (t: Throwable) {
                d.deleteRecursively()
                throw IllegalStateException("Cannot extract RAR (RAR5, encrypted and multi-part archives are not supported): ${t.message}")
            }
        }
    }
    private fun rel() = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path.replace('\\', '/') }.toList()
    override fun names() = rel()
    override fun forEach(block: (String, InputStream) -> Boolean) {
        for (n in rel()) File(dir, n).inputStream().use { if (!block(n, it)) return }
    }
    override fun close() {}
}

/** Lets long operations report what they are doing; the UI shows it in a progress dialog. */
object Progress {
    @Volatile var sink: ((String, String, Float?) -> Unit)? = null
    private var last = 0L
    fun report(title: String, detail: String, fraction: Float? = null, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - last < 80) return // don't flood the UI with thousands of updates
        last = now
        sink?.invoke(title, detail, fraction)
    }
}

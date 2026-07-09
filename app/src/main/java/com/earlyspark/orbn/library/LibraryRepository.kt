package com.earlyspark.orbn.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import com.earlyspark.orbn.data.OrbnDatabase
import com.earlyspark.orbn.data.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Bridges the orbn music folder and the Room store: discovers audio files,
 * registers new/changed ones for analysis, and prunes rows for deleted files.
 */
class LibraryRepository(private val context: Context) {

    private val dao = OrbnDatabase.get(context).trackDao()

    val totalCount: Flow<Int> = dao.totalCount()
    val analyzedCount: Flow<Int> = dao.analyzedCount()

    /** The app-owned Music folder (no storage permission required). */
    fun musicDir(): File? = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)

    /**
     * Walk the folder and reconcile it with the DB. New files are inserted
     * (unanalyzed); files whose size/mtime changed are re-queued; rows for
     * vanished files are removed. Returns the number of audio files present.
     */
    suspend fun scan(): Int = withContext(Dispatchers.IO) {
        val dir = musicDir()
        if (dir == null || !dir.exists()) {
            dao.deleteAll()
            return@withContext 0
        }

        val files = dir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            .toList()

        for (f in files) {
            val path = f.absolutePath
            val existing = dao.byPath(path)
            when {
                existing == null -> {
                    val (artist, title) = readTags(f)
                    dao.insertIfAbsent(
                        TrackEntity(path, f.length(), f.lastModified(), artist = artist, title = title)
                    )
                }
                existing.sizeBytes != f.length() || existing.lastModified != f.lastModified() -> {
                    // File changed on disk → re-read tags and re-queue for analysis.
                    val (artist, title) = readTags(f)
                    dao.update(
                        existing.copy(
                            sizeBytes = f.length(),
                            lastModified = f.lastModified(),
                            artist = artist,
                            title = title,
                            analyzedAt = null,
                        )
                    )
                }
                existing.artist == null && existing.title == null -> {
                    // One-time backfill for rows that predate tag-reading (post-migration).
                    val (artist, title) = readTags(f)
                    if (artist != null || title != null) {
                        dao.update(existing.copy(artist = artist, title = title))
                    }
                }
            }
        }

        if (files.isEmpty()) {
            dao.deleteAll()
        } else {
            dao.deleteMissing(files.map { it.absolutePath })
        }

        Log.i(TAG, "Scan complete: ${files.size} audio files in ${dir.absolutePath}")
        files.size
    }

    /**
     * Copy each picked document (SAF URIs) into the Music folder on IO — the shared "add music"
     * import used by both the home CTA and the settings entry. The originals are never touched;
     * orbn owns its copies. Unreadable entries are skipped and half-written files cleaned up.
     * Returns the count copied. Callers follow up with [scan] + TaggingService to register them.
     */
    suspend fun importFiles(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val dir = musicDir()?.apply { mkdirs() } ?: return@withContext 0
        var added = 0
        for (uri in uris) {
            val name = displayName(uri) ?: continue
            val target = uniqueFile(dir, name)
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: error("no input stream")
                added++
            }.onFailure {
                Log.w(TAG, "import failed for $name: ${it.message}")
                target.delete() // don't leave a half-written file behind
            }
        }
        added
    }

    /** The picked file's display name (e.g. "song.mp3"), or the URI's last path segment as a fallback. */
    private fun displayName(uri: Uri): String? {
        val raw = run {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { return@run it }
            }
            uri.lastPathSegment
        } ?: return null
        // Reduce to a bare filename: never let a provider-supplied name with path separators ("../…")
        // escape the Music folder when used in File(dir, name).
        return raw.substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { null }
    }

    /** Avoid clobbering an existing file: "song.mp3" → "song (1).mp3" if taken. */
    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return f
    }

    /**
     * Read embedded artist/title tags (read-only — the file is never written, F1). Uses the
     * built-in MediaMetadataRetriever via a file descriptor (robust under scoped storage). Returns
     * (null, null) if the file has no tags or can't be read.
     */
    private fun readTags(file: File): Pair<String?, String?> {
        val mmr = MediaMetadataRetriever()
        return try {
            FileInputStream(file).use { mmr.setDataSource(it.fd) }
            val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()?.ifBlank { null }
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.ifBlank { null }
            artist to title
        } catch (e: Exception) {
            Log.w(TAG, "tag read failed for ${file.name}: ${e.message}")
            null to null
        } finally {
            runCatching { mmr.release() }
        }
    }

    companion object {
        private const val TAG = "LibraryRepository"
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "wav")
    }
}

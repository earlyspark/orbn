package com.earlyspark.orbn.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
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

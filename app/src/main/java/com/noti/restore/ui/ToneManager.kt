package com.noti.restore.ui

import android.content.ContentValues
import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

private const val TAG = "ToneManager"

data class ToneInfo(
    val uri: Uri,
    val title: String,
    val isCustom: Boolean = false
)

enum class ToneType(val ringtoneType: Int, val subDir: String, val mediaColumn: String) {
    RINGTONE(RingtoneManager.TYPE_RINGTONE, "Ringtones", MediaStore.Audio.AudioColumns.IS_RINGTONE),
    NOTIFICATION(RingtoneManager.TYPE_NOTIFICATION, "Notifications", MediaStore.Audio.AudioColumns.IS_NOTIFICATION),
    ALARM(RingtoneManager.TYPE_ALARM, "Alarms", MediaStore.Audio.AudioColumns.IS_ALARM)
}

object ToneManager {

    private var mediaPlayer: MediaPlayer? = null

    /** List all available tones of a given type (system + custom) */
    fun listTones(context: Context, type: ToneType): List<ToneInfo> {
        val tones = mutableListOf<ToneInfo>()
        try {
            val rm = RingtoneManager(context)
            rm.setType(type.ringtoneType)
            val cursor = rm.cursor
            while (cursor.moveToNext()) {
                val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                val uri = rm.getRingtoneUri(cursor.position)
                tones.add(ToneInfo(uri, title))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list tones", e)
        }
        return tones
    }

    /** Get the currently set tone for a type */
    fun getCurrentTone(context: Context, type: ToneType): Uri? {
        return RingtoneManager.getActualDefaultRingtoneUri(context, type.ringtoneType)
    }

    /** Get the display name for a tone URI. Does NOT hold a Ringtone object after returning. */
    fun getToneName(context: Context, uri: Uri?): String {
        if (uri == null) return "Silent"
        return try {
            val ringtone = RingtoneManager.getRingtone(context, uri)
            val title = ringtone?.getTitle(context)
            ringtone?.stop()
            title ?: uri.lastPathSegment ?: "Unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get tone name", e)
            uri.lastPathSegment ?: "Unknown"
        }
    }

    /** Compare two tone URIs, tolerating format differences (external_primary vs volume index) */
    fun sameUri(a: Uri?, b: Uri?): Boolean {
        if (a == b) return true
        if (a == null || b == null) return false
        return a.lastPathSegment != null && a.lastPathSegment == b.lastPathSegment
    }

    /** Set a tone as the default for a type. Pass null uri for silent. Requires WRITE_SETTINGS permission. */
    fun setTone(context: Context, type: ToneType, uri: Uri?): Boolean {
        return try {
            RingtoneManager.setActualDefaultRingtoneUri(context, type.ringtoneType, uri)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set tone", e)
            false
        }
    }

    /** Import an audio file from a content URI into the appropriate media directory */
    fun importTone(context: Context, sourceUri: Uri, displayName: String, type: ToneType): Uri? {
        try {
            val resolver = context.contentResolver
            val inputStream = resolver.openInputStream(sourceUri) ?: return null
            val bytes = inputStream.readBytes()
            inputStream.close()

            val cleanName = displayName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "")
                .ifEmpty { "custom_tone" }

            val mimeType = resolver.getType(sourceUri)?.takeIf { it.startsWith("audio/") } ?: "audio/mpeg"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, cleanName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
                when (type) {
                    ToneType.RINGTONE -> {
                        put(MediaStore.Audio.Media.IS_RINGTONE, true)
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_RINGTONES}/")
                    }
                    ToneType.NOTIFICATION -> {
                        put(MediaStore.Audio.Media.IS_NOTIFICATION, true)
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_NOTIFICATIONS}/")
                    }
                    ToneType.ALARM -> {
                        put(MediaStore.Audio.Media.IS_ALARM, true)
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_ALARMS}/")
                    }
                }
            }

            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val newUri = resolver.insert(collection, values) ?: return null

            resolver.openOutputStream(newUri)?.use { os ->
                os.write(bytes)
            }

            // Mark file as fully written so the system ringtone player can access it
            resolver.update(newUri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)

            return newUri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import tone", e)
            return null
        }
    }

    /** Play a preview of a tone */
    fun playPreview(context: Context, uri: Uri) {
        stopPreview()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                setOnCompletionListener { stopPreview() }
                setOnErrorListener { _, _, _ -> stopPreview(); true }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play preview", e)
            stopPreview()
        }
    }

    /** Stop any currently playing preview */
    fun stopPreview() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    /** Check if a preview is currently playing */
    fun isPlaying(): Boolean {
        return try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false }
    }

    /** Delete a custom tone from MediaStore */
    fun deleteTone(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete tone", e)
            false
        }
    }
}

package com.mediabackup.utility.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MediaListenerService
 *
 * Extends [NotificationListenerService] to passively monitor incoming notifications
 * from targeted messaging clients (e.g. WhatsApp, WhatsApp Business).
 *
 * When an incoming media notification is detected (Photo/Video arrival), it scans
 * the accessible local temporary/hidden directories (.Statuses, .shared) and performs
 * an atomic file-stream copy into Scoped Storage (/Pictures/SavedMediaBackup/).
 */
class MediaListenerService : NotificationListenerService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    companion object {
        private const val TAG = "MediaListenerService"

        // Targeted messaging client package names
        const val PKG_WHATSAPP = "com.whatsapp"
        const val PKG_WHATSAPP_BUSINESS = "com.whatsapp.w4b"

        val TARGET_PACKAGES = setOf(
            PKG_WHATSAPP,
            PKG_WHATSAPP_BUSINESS
        )

        // Subdirectory under Environment.DIRECTORY_PICTURES
        const val BACKUP_SUBDIRECTORY = "SavedMediaBackup"

        // Keywords commonly present in notification payload indicating media arrivals
        private val MEDIA_INDICATOR_KEYWORDS = listOf(
            "photo", "video", "gif", "image", "media", "picture", "voice message", "audio"
        )

        // Event bus for broadcasting real-time backup events to UI
        private val _mediaBackupEvents = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 64)
        val mediaBackupEvents = _mediaBackupEvents.asSharedFlow()

        // Active state observable
        var isServiceConnected: Boolean = false
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isServiceConnected = true
        Log.i(TAG, "NotificationListenerService connected and active.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isServiceConnected = false
        Log.w(TAG, "NotificationListenerService disconnected.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)

        if (sbn == null) return

        val packageName = sbn.packageName ?: return

        // Filter for targeted packages only
        if (!TARGET_PACKAGES.contains(packageName)) {
            return
        }

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        serviceScope.launch {
            handleIncomingNotification(packageName, extras, notification)
        }
    }

    /**
     * Inspects notification extras for media arrival indicators and executes
     * backup synchronization when matched.
     */
    private suspend fun handleIncomingNotification(
        packageName: String,
        extras: Bundle,
        notification: Notification
    ) {
        val title = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT)?.toString().orEmpty()

        val fullContent = "$title $text $bigText $subText".lowercase(Locale.getDefault())

        val containsMediaKeyword = MEDIA_INDICATOR_KEYWORDS.any { keyword ->
            fullContent.contains(keyword)
        }

        Log.d(TAG, "Notification received from $packageName: '$title' - '$text' [MediaIndicator=$containsMediaKeyword]")

        if (containsMediaKeyword) {
            val copiedCount = scanAndBackupMedia(packageName)
            _mediaBackupEvents.emit(
                BackupEvent.NotificationProcessed(
                    packageName = packageName,
                    title = title,
                    text = text,
                    mediaKeywordDetected = true,
                    filesCopied = copiedCount
                )
            )
        }
    }

    /**
     * Scans known temporary and hidden status directories for the matching messaging package.
     * Copies any new files to Scoped Storage.
     *
     * @return Number of newly backed up files.
     */
    suspend fun scanAndBackupMedia(packageName: String): Int = withContext(Dispatchers.IO) {
        val candidateDirs = resolveSourceDirectories(packageName)
        val destinationDir = getBackupDestinationDirectory()

        var copiedCounter = 0

        for (sourceDir in candidateDirs) {
            if (!sourceDir.exists() || !sourceDir.isDirectory) {
                continue
            }

            val files = sourceDir.listFiles { file ->
                file.isFile && !file.name.startsWith(".nomedia") && file.length() > 0
            } ?: continue

            for (sourceFile in files) {
                val wasCopied = copyFileSafely(sourceFile, destinationDir, packageName)
                if (wasCopied) {
                    copiedCounter++
                }
            }
        }

        Log.i(TAG, "Scan complete for $packageName. Backed up $copiedCounter new media files.")
        return@withContext copiedCounter
    }

    /**
     * Safely copies sourceFile to destinationDir using atomic streaming.
     * Prevents duplication by checking both filename collisions and MD5 checksums.
     */
    private fun copyFileSafely(sourceFile: File, destinationDir: File, sourcePkg: String): Boolean {
        try {
            // Guard: Destination directory creation
            if (!destinationDir.exists()) {
                val created = destinationDir.mkdirs()
                if (!created && !destinationDir.exists()) {
                    Log.e(TAG, "Failed to create destination directory: ${destinationDir.absolutePath}")
                    return false
                }
            }

            val sourceChecksum by lazy { calculateMD5(sourceFile) }

            // 1. Check if identical name already exists in destination
            val destFileByName = File(destinationDir, sourceFile.name)
            if (destFileByName.exists()) {
                // Verify if content is identical
                if (destFileByName.length() == sourceFile.length()) {
                    val destChecksum = calculateMD5(destFileByName)
                    if (destChecksum == sourceChecksum) {
                        Log.d(TAG, "File '${sourceFile.name}' already exists with identical checksum. Skipping.")
                        return false
                    }
                }
            }

            // 2. Prevent duplicate files even if source uses temporary rotated names
            val existingFiles = destinationDir.listFiles() ?: emptyArray()
            val isDuplicate = existingFiles.any { existing ->
                existing.length() == sourceFile.length() && calculateMD5(existing) == sourceChecksum
            }

            if (isDuplicate) {
                Log.d(TAG, "Duplicate content detected for '${sourceFile.name}'. Skipping.")
                return false
            }

            // 3. Generate structured destination filename if needed
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(sourceFile.lastModified()))
            val extension = sourceFile.extension.ifBlank { "jpg" }
            val prefix = if (sourcePkg == PKG_WHATSAPP_BUSINESS) "WA_BIZ" else "WA"
            val targetFileName = "BACKUP_${prefix}_${timeStamp}_${sourceFile.nameWithoutExtension}.${extension}"
            val targetFile = File(destinationDir, targetFileName)

            // 4. Perform direct atomic file-stream copy
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }

            // Set last modified date to match original media
            targetFile.setLastModified(sourceFile.lastModified())

            Log.i(TAG, "Successfully backed up: ${sourceFile.name} -> ${targetFile.name} (${targetFile.length()} bytes)")
            return true

        } catch (e: IOException) {
            Log.e(TAG, "I/O error backing up file: ${sourceFile.absolutePath}", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error backing up file: ${sourceFile.absolutePath}", e)
            return false
        }
    }

    /**
     * Resolves all possible storage paths where temporary messaging media/.Statuses reside,
     * accounting for Android 11+ scoped storage path changes (Android/media/...)
     * and legacy root paths.
     */
    private fun resolveSourceDirectories(packageName: String): List<File> {
        val paths = mutableListOf<File>()
        val externalStorage = Environment.getExternalStorageDirectory()

        when (packageName) {
            PKG_WHATSAPP -> {
                // Modern Android 11+ path: Android/media/com.whatsapp/WhatsApp/Media/.Statuses
                paths.add(File(externalStorage, "Android/media/com.whatsapp/WhatsApp/Media/.Statuses"))
                paths.add(File(externalStorage, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"))
                paths.add(File(externalStorage, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video"))
                paths.add(File(externalStorage, "Android/media/com.whatsapp/WhatsApp/Media/.shared"))

                // Legacy Android 10 and below paths
                paths.add(File(externalStorage, "WhatsApp/Media/.Statuses"))
                paths.add(File(externalStorage, "WhatsApp/Media/WhatsApp Images"))
                paths.add(File(externalStorage, "WhatsApp/Media/WhatsApp Video"))
            }
            PKG_WHATSAPP_BUSINESS -> {
                // Modern Android 11+ Business path
                paths.add(File(externalStorage, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses"))
                paths.add(File(externalStorage, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images"))
                paths.add(File(externalStorage, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Video"))

                // Legacy Business paths
                paths.add(File(externalStorage, "WhatsApp Business/Media/.Statuses"))
                paths.add(File(externalStorage, "WhatsApp Business/Media/WhatsApp Business Images"))
            }
        }

        // Also add application private external cache directories
        applicationContext.getExternalFilesDir(null)?.let { appDir ->
            paths.add(File(appDir, "temp_status_cache"))
        }

        return paths
    }

    /**
     * Resolves the dedicated local backup directory in Scoped Storage.
     * Default: /storage/emulated/0/Pictures/SavedMediaBackup/
     */
    private fun getBackupDestinationDirectory(): File {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val backupDir = File(picturesDir, BACKUP_SUBDIRECTORY)
        if (!backupDir.exists()) {
            backupDir.mkdirs()
        }
        return backupDir
    }

    /**
     * Computes MD5 checksum for file integrity and deduplication checks.
     */
    private fun calculateMD5(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            file.name + "_" + file.length()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}

/**
 * Event sealed class emitted when notification processing completes.
 */
sealed class BackupEvent {
    data class NotificationProcessed(
        val packageName: String,
        val title: String,
        val text: String,
        val mediaKeywordDetected: Boolean,
        val filesCopied: Int
    ) : BackupEvent()
}

package com.mediabackup.utility.data

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mediabackup.utility.service.MediaListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

/**
 * Data representation of a backed up media file.
 */
data class BackupMediaItem(
    val id: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val timestamp: Long,
    val mimeType: String,
    val isVideo: Boolean,
    val sourcePackage: String,
    val durationFormatted: String? = null
) {
    val fileSizeFormatted: String
        get() {
            if (fileSize <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            val digitGroups = (log10(fileSize.toDouble()) / log10(1024.0)).toInt()
            return DecimalFormat("#,##0.#").format(fileSize / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
        }
}

enum class MediaFilterType {
    ALL,
    PHOTOS,
    VIDEOS
}

/**
 * ViewModel managing the UI state of backed-up media files.
 */
class MediaViewModel(application: Application) : AndroidViewModel(application) {

    private val _rawMediaItems = MutableStateFlow<List<BackupMediaItem>>(emptyList())
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _selectedFilter = MutableStateFlow(MediaFilterType.ALL)
    val selectedFilter: StateFlow<MediaFilterType> = _selectedFilter.asStateFlow()

    private val _recentEventLog = MutableStateFlow<String?>(null)
    val recentEventLog: StateFlow<String?> = _recentEventLog.asStateFlow()

    // Filtered media items flow
    val mediaItems: StateFlow<List<BackupMediaItem>> = combine(
        _rawMediaItems,
        _selectedFilter
    ) { items, filter ->
        when (filter) {
            MediaFilterType.ALL -> items
            MediaFilterType.PHOTOS -> items.filter { !it.isVideo }
            MediaFilterType.VIDEOS -> items.filter { it.isVideo }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        refreshMediaFiles()
        listenToServiceEvents()
    }

    private fun listenToServiceEvents() {
        viewModelScope.launch {
            MediaListenerService.mediaBackupEvents.collect { event ->
                when (event) {
                    is com.mediabackup.utility.service.BackupEvent.NotificationProcessed -> {
                        _recentEventLog.value = "Captured ${event.filesCopied} files from ${event.packageName}"
                        refreshMediaFiles()
                    }
                }
            }
        }
    }

    fun setFilter(filter: MediaFilterType) {
        _selectedFilter.value = filter
    }

    /**
     * Reads /Pictures/SavedMediaBackup/ directory and populates media item list.
     */
    fun refreshMediaFiles() {
        viewModelScope.launch {
            _isScanning.value = true
            val items = withContext(Dispatchers.IO) {
                loadBackupFilesFromDisk()
            }
            _rawMediaItems.value = items
            _isScanning.value = false
        }
    }

    /**
     * Trigger manual scanner across status folders.
     */
    fun scanTempDirectories() {
        viewModelScope.launch {
            _isScanning.value = true
            withContext(Dispatchers.IO) {
                // Perform scan
            }
            refreshMediaFiles()
        }
    }

    fun deleteMediaItem(item: BackupMediaItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val file = File(item.filePath)
                if (file.exists()) {
                    file.delete()
                }
            }
            refreshMediaFiles()
        }
    }

    private fun loadBackupFilesFromDisk(): List<BackupMediaItem> {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val backupDir = File(picturesDir, MediaListenerService.BACKUP_SUBDIRECTORY)

        if (!backupDir.exists() || !backupDir.isDirectory) {
            return emptyList()
        }

        val files = backupDir.listFiles { file ->
            file.isFile && !file.name.startsWith(".")
        } ?: return emptyList()

        return files
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val ext = file.extension.lowercase()
                val isVideo = ext in listOf("mp4", "mkv", "3gp", "webm", "avi")
                val isBiz = file.name.contains("WA_BIZ")

                BackupMediaItem(
                    id = file.absolutePath,
                    fileName = file.name,
                    filePath = file.absolutePath,
                    fileSize = file.length(),
                    timestamp = file.lastModified(),
                    mimeType = if (isVideo) "video/$ext" else "image/$ext",
                    isVideo = isVideo,
                    sourcePackage = if (isBiz) "com.whatsapp.w4b" else "com.whatsapp"
                )
            }
    }
}

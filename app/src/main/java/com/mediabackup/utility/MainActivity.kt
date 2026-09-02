package com.mediabackup.utility

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mediabackup.utility.data.BackupMediaItem
import com.mediabackup.utility.data.MediaFilterType
import com.mediabackup.utility.data.MediaViewModel
import com.mediabackup.utility.ui.theme.MediaBackupTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MediaBackupTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MediaViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mediaItems by viewModel.mediaItems.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()

    var isNotificationServiceEnabled by remember { mutableStateOf(false) }
    var hasStoragePermission by remember { mutableStateOf(false) }
    var selectedMediaForPreview by remember { mutableStateOf<BackupMediaItem?>(null) }

    fun checkNotificationListenerStatus(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        val packageName = context.packageName
        return flat?.contains(packageName) == true
    }

    fun checkStoragePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val readImages = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_IMAGES
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val readVideos = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_VIDEO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            readImages && readVideos
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isNotificationServiceEnabled = checkNotificationListenerStatus()
                hasStoragePermission = checkStoragePermissions()
                viewModel.refreshMediaFiles()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        hasStoragePermission = permissionsMap.values.all { it }
        if (hasStoragePermission) {
            Toast.makeText(context, "Storage permissions granted!", Toast.LENGTH_SHORT).show()
            viewModel.refreshMediaFiles()
        } else {
            Toast.makeText(context, "Media permissions required for gallery indexing.", Toast.LENGTH_LONG).show()
        }
    }

    fun requestMediaPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            storagePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            )
        } else {
            storagePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "Media Backup Utility",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Local Notification Media Vault",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshMediaFiles() },
                        enabled = !isScanning
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Gallery")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            ServiceStatusCard(
                isServiceEnabled = isNotificationServiceEnabled,
                onOpenSettings = {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (!hasStoragePermission) {
                StoragePermissionCard(
                    onRequestPermission = { requestMediaPermissions() }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            MediaFilterRow(
                totalCount = mediaItems.size,
                selectedFilter = selectedFilter,
                onFilterSelected = { viewModel.setFilter(it) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (mediaItems.isEmpty()) {
                EmptyStateView(
                    isServiceActive = isNotificationServiceEnabled,
                    onManualScan = { viewModel.scanTempDirectories() }
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(
                        items = mediaItems,
                        key = { it.id }
                    ) { item ->
                        MediaThumbnailItem(
                            item = item,
                            onClick = { selectedMediaForPreview = item }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    selectedMediaForPreview?.let { item ->
        MediaPreviewDialog(
            item = item,
            onDismiss = { selectedMediaForPreview = null },
            onShare = {
                val file = File(item.filePath)
                if (file.exists()) {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.provider",
                        file
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = item.mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Media"))
                }
            },
            onDelete = {
                viewModel.deleteMediaItem(item)
                selectedMediaForPreview = null
            }
        )
    }
}

@Composable
fun ServiceStatusCard(
    isServiceEnabled: Boolean,
    onOpenSettings: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isServiceEnabled)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (isServiceEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isServiceEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column {
                    Text(
                        text = if (isServiceEnabled) "Notification Listener: ACTIVE" else "Listener Service: DISABLED",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isServiceEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = if (isServiceEnabled) "Monitoring WhatsApp / W4B media arrivals" else "Grant notification access to intercept status media",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!isServiceEnabled) {
                Button(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Enable", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun StoragePermissionCard(
    onRequestPermission: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(32.dp)
                )
                Column {
                    Text(
                        text = "Storage Access Required",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Allows reading backup vault at /Pictures/SavedMediaBackup/",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            FilledTonalButton(
                onClick = onRequestPermission,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Allow", fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun MediaFilterRow(
    totalCount: Int,
    selectedFilter: MediaFilterType,
    onFilterSelected: (MediaFilterType) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedFilter == MediaFilterType.ALL,
                onClick = { onFilterSelected(MediaFilterType.ALL) },
                label = { Text("All ($totalCount)") },
                shape = RoundedCornerShape(8.dp)
            )
            FilterChip(
                selected = selectedFilter == MediaFilterType.PHOTOS,
                onClick = { onFilterSelected(MediaFilterType.PHOTOS) },
                label = { Text("Photos") },
                leadingIcon = { Icon(Icons.Default.AccountBox, contentDescription = null, modifier = Modifier.size(16.dp)) },
                shape = RoundedCornerShape(8.dp)
            )
            FilterChip(
                selected = selectedFilter == MediaFilterType.VIDEOS,
                onClick = { onFilterSelected(MediaFilterType.VIDEOS) },
                label = { Text("Videos") },
                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp)) },
                shape = RoundedCornerShape(8.dp)
            )
        }

        Text(
            text = "/Pictures/SavedMediaBackup",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun MediaThumbnailItem(
    item: BackupMediaItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current

    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .aspectRatio(1f)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(File(item.filePath))
                    .crossfade(true)
                    .build(),
                contentDescription = item.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            if (item.isVideo) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = item.durationFormatted ?: "VIDEO",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (item.sourcePackage == "com.whatsapp.w4b")
                            Color(0xFF0F9D58).copy(alpha = 0.85f)
                        else
                            Color(0xFF25D366).copy(alpha = 0.85f)
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (item.sourcePackage == "com.whatsapp.w4b") "WA BIZ" else "WA",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun EmptyStateView(
    isServiceActive: Boolean,
    onManualScan: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.AccountBox,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "No Backed-up Media Yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (isServiceActive)
                    "Waiting for incoming WhatsApp/W4B media notifications..."
                else
                    "Enable the Notification Listener Service above to start capturing media automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedButton(
                onClick = onManualScan,
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan .Statuses Folders Now")
            }
        }
    }
}

@Composable
fun MediaPreviewDialog(
    item: BackupMediaItem,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val formattedDate = remember(item.timestamp) {
        SimpleDateFormat("MMM dd, yyyy - HH:mm:ss", Locale.getDefault()).format(Date(item.timestamp))
    }

    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

    DisposableEffect(item) {
        if (item.isVideo) {
            val player = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(File(item.filePath))))
                prepare()
                playWhenReady = true
            }
            exoPlayer = player
        }

        onDispose {
            exoPlayer?.release()
            exoPlayer = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = item.fileName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.isVideo) {
                        exoPlayer?.let { player ->
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        this.player = player
                                        useController = true
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(File(item.filePath))
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Text("Saved at: $formattedDate", style = MaterialTheme.typography.bodySmall)
                Text("Size: ${item.fileSizeFormatted}", style = MaterialTheme.typography.bodySmall)
                Text("Source: ${item.sourcePackage}", style = MaterialTheme.typography.bodySmall)
                Text("Path: ${item.filePath}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = {
            TextButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Share")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Delete")
            }
        }
    )
}

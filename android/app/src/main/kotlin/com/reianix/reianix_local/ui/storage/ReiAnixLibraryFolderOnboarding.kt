package com.reianix.reianix_local.ui.storage

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reianix.reianix_local.R
import com.reianix.reianix_local.data.releases.ReiAnixReleaseMonitor
import com.reianix.reianix_local.ui.ReiAnixProgressIndicator

/** The SAF folder is optional at first launch. No implicit storage source is created. */
@Composable
fun ReiAnixLibraryFolderOnboarding(
    state: String,
    error: String?,
    selectedFolderPath: String?,
    onStart: () -> Unit,
    onSelectFolder: () -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        ReiAnixReleaseMonitor.recordPermissionDecision(context, granted)
        onStart()
    }
    val pickerOpen = state.equals("folder_picker_open", ignoreCase = true)
    val errorMessage = error?.trim()?.takeIf { it.isNotBlank() && it.any(Char::isWhitespace) }
    val selected = !selectedFolderPath.isNullOrBlank()
    val blue = Color(0xFF2874EA)
    val muted = Color(0xFFA0A0A0)
    val white = Color.White

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black, contentColor = white) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Bottom +
                        WindowInsetsSides.Horizontal,
                ))
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            val topSpace = (maxHeight * 0.12f).coerceIn(24.dp, 94.dp)
            val folderSpace = (maxHeight * 0.072f).coerceIn(24.dp, 56.dp)
            val mascotSize = if (maxHeight < 480.dp) 96.dp else 120.dp
            Column(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = topSpace, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(R.drawable.reianix_welcome_mascot),
                        contentDescription = null,
                        modifier = Modifier.size(mascotSize),
                    )
                    Spacer(Modifier.height(26.dp))
                    Text(
                        text = "Bem-vindo(a) ao ReiAnix",
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        lineHeight = 30.sp,
                        textAlign = TextAlign.Center,
                        color = white,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Organize e assista aos seus animes favoritos em um só lugar.",
                        modifier = Modifier.padding(horizontal = 12.dp),
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 16.sp,
                        lineHeight = 21.sp,
                        color = muted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(folderSpace))
                    Surface(
                        onClick = onSelectFolder,
                        enabled = !pickerOpen,
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF0D0D0D),
                        contentColor = white,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0xFF282828)),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 78.dp)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FolderOpen,
                                contentDescription = null,
                                tint = Color(0xFF5796FF),
                                modifier = Modifier.size(38.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Selecionar uma pasta",
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp,
                                    lineHeight = 20.sp,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = selectedFolderPath?.takeIf { it.isNotBlank() }
                                        ?: "Nenhuma pasta selecionada",
                                    fontFamily = FontFamily.SansSerif,
                                    fontSize = 13.sp,
                                    lineHeight = 17.sp,
                                    color = muted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                imageVector = if (selected) Icons.Filled.CheckCircle
                                    else Icons.Filled.ChevronRight,
                                contentDescription = if (selected) "Pasta autorizada" else null,
                                tint = if (selected) Color(0xFF65C987) else Color(0xFF777777),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Seus arquivos permanecem no dispositivo.",
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = muted,
                        textAlign = TextAlign.Center,
                    )
                    if (pickerOpen) {
                        Spacer(Modifier.height(16.dp))
                        ReiAnixProgressIndicator(
                            progress = 0f,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            announceProgress = false,
                        )
                    }
                    if (errorMessage != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            errorMessage,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = Color(0xFFFFA8A8),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Button(
                    onClick = {
                        when {
                            // The folder is optional; keep the old skip behavior.
                            !selected || ReiAnixReleaseMonitor.hasSavedPreference(context) -> onStart()
                            ReiAnixReleaseMonitor.notificationsAllowed(context) -> {
                                ReiAnixReleaseMonitor.recordPermissionDecision(context, true)
                                onStart()
                            }
                            Build.VERSION.SDK_INT >= 33 -> {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            else -> {
                                // On older Android versions notifications are managed in app settings.
                                ReiAnixReleaseMonitor.recordPermissionDecision(context, false)
                                onStart()
                            }
                        }
                    },
                    enabled = !pickerOpen,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = blue, contentColor = white),
                ) {
                    Text("Começar", fontFamily = FontFamily.SansSerif,
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.ArrowForward, contentDescription = null,
                        modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Você poderá alterar suas pastas nas configurações.",
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
        }
    }
}

package com.reianix.reianix_local.downloads

import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/** Only the selected tab owns a live WebView; inactive tabs retain history and a small RAM-only preview. */
internal class BrowserTab(val initialUrl:String="",val headers:Map<String,String> = emptyMap(),val id:String=java.util.UUID.randomUUID().toString()) {
    var title by mutableStateOf("Nova guia")
    var url by mutableStateOf(initialUrl)
    var home by mutableStateOf(initialUrl.isBlank())
    var preview by mutableStateOf<Bitmap?>(null)
    var saved=Bundle()
    var scrollX=0;var scrollY=0
}

@Composable internal fun BrowserTabOverview(tabs:List<BrowserTab>,selected:String,onSelect:(BrowserTab)->Unit,
    onClose:(BrowserTab)->Unit,onNew:()->Unit,onBack:()->Unit) {
    androidx.activity.compose.BackHandler(onBack=onBack)
    Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=onBack){Icon(Icons.Filled.ArrowBack,"Voltar")}
                Text("Guias (${tabs.size})",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                IconButton(onClick=onNew){Icon(Icons.Filled.Add,"Nova guia")}
            }
            LazyVerticalGrid(columns=GridCells.Fixed(2),contentPadding=PaddingValues(12.dp),
                horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                items(tabs,key={it.id}) {tab ->
                    Card(onClick={onSelect(tab)},colors=CardDefaults.cardColors(
                        containerColor=if(tab.id==selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(tab.title,Modifier.weight(1f).padding(start=12.dp),maxLines=1,
                                overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            IconButton(onClick={onClose(tab)}){Icon(Icons.Filled.Close,"Fechar ${tab.title}")}
                        }
                        val bitmap=tab.preview
                        if(bitmap!=null && !tab.home)Image(bitmap.asImageBitmap(),tab.title,
                            Modifier.fillMaxWidth().aspectRatio(0.62f),contentScale=ContentScale.Crop)
                        else Box(Modifier.fillMaxWidth().aspectRatio(0.62f),contentAlignment=Alignment.Center) {
                            Icon(Icons.Filled.Home,null,Modifier.size(52.dp),tint=MaterialTheme.colorScheme.primary)
                        }
                        Text(if(tab.home)"Início" else BrowserAdBlock.host(tab.url),
                            Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall,maxLines=1)
                    }
                }
            }
        }
    }
}

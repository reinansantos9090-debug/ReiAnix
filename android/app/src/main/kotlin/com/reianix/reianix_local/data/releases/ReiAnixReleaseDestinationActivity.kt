package com.reianix.reianix_local.data.releases

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.reianix.reianix_local.player.SystemUiController
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reianix.reianix_local.data.anilist.ReiAnixOfficialLink
import com.reianix.reianix_local.ui.theme.ReiAnixComposeTheme

/** Shown only on notification tap. No permanent calendar tab is created. */
class ReiAnixReleaseDestinationActivity : ComponentActivity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) SystemUiController(window).applyApplicationImmersivePolicy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemUiController(window).applyApplicationImmersivePolicy()
        val title = intent.getStringExtra("title").orEmpty().take(180)
        val episode = intent.getIntExtra("episode", -1)
        val aniListId = intent.getIntExtra("anilist_id", -1)
        val site = intent.getStringExtra("official_site").orEmpty()
        val url = intent.getStringExtra("official_link").orEmpty()
        val trusted = ReiAnixReleaseMonitor.officialWatchLink(listOf(ReiAnixOfficialLink(site, url)))
        setContent {
            ReiAnixComposeTheme {
                Column(
                    modifier = Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text("Novo episódio", style = MaterialTheme.typography.headlineMedium)
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    if (episode > 0) Text("Episódio " + episode)
                    Text(
                        "O episódio estreou segundo a programação do AniList. " +
                            "A disponibilidade em serviços oficiais no Brasil pode variar.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (trusted != null) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { open(trusted.url) },
                        ) { Text("Abrir " + trusted.site) }
                    } else {
                        Text(
                            "Nenhum link oficial de reprodução foi confirmado para este episódio.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (aniListId > 0) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { open("https://anilist.co/anime/" + aniListId) },
                        ) { Text("Ver informações no AniList (não é streaming)") }
                    }
                    OutlinedButton(onClick = { finish() }) { Text("Fechar") }
                }
            }
        }
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            // No browser installed; this screen stays available for the user.
        }
    }
}

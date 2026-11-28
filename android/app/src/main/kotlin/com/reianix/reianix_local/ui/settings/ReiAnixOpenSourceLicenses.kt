package com.reianix.reianix_local.ui.settings

import com.reianix.reianix_local.R

import androidx.compose.ui.res.stringResource

import com.reianix.reianix_local.ui.theme.reiAnixScreenBackground
import android.content.Intent
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reianix.reianix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reianix.reianix_local.ui.theme.ReiAnixTokens
import androidx.compose.foundation.layout.widthIn

/** Credits for the app and direct runtime dependencies declared in app/build.gradle.kts.
 * Test-only dependencies are intentionally excluded. Each item opens the license
 * text packaged in this APK; Google Sans retains its own OFL and modification notice.
 * Transitive dependencies should also be audited when publishing a new release.
 */
internal data class ReiAnixLicenseCredit(
    val id: String,
    @StringRes val title: Int,
    @StringRes val attribution: Int,
    val licenseName: String,
    val licenseAsset: String = "licenses/apache_2_0.txt",
    val noticeAsset: String? = null,
    @StringRes val licenseNameRes: Int? = null,
)

internal val ReiAnixLicenseCredits = listOf(
    ReiAnixLicenseCredit("reianix", R.string.license_title_reianix, R.string.license_attribution_reianix, "Apache License 2.0",
        noticeAsset = "licenses/reianix_NOTICE.txt"),
    ReiAnixLicenseCredit("kotlin", R.string.license_title_kotlin, R.string.license_attribution_jetbrains, "Apache License 2.0"),
    ReiAnixLicenseCredit("coroutines", R.string.license_title_coroutines, R.string.license_attribution_jetbrains, "Apache License 2.0"),
    ReiAnixLicenseCredit("activity", R.string.license_title_activity, R.string.license_attribution_aosp, "Apache License 2.0"),
    ReiAnixLicenseCredit("compose", R.string.license_title_compose, R.string.license_attribution_aosp, "Apache License 2.0"),
    ReiAnixLicenseCredit("lifecycle", R.string.license_title_lifecycle, R.string.license_attribution_aosp, "Apache License 2.0"),
    ReiAnixLicenseCredit("navigation", R.string.license_title_navigation, R.string.license_attribution_aosp, "Apache License 2.0"),
    ReiAnixLicenseCredit("documentfile", R.string.license_title_documentfile, R.string.license_attribution_aosp, "Apache License 2.0"),
    ReiAnixLicenseCredit("webkit", R.string.license_title_webkit, R.string.license_attribution_aosp, "Apache License 2.0"),
    ReiAnixLicenseCredit("workmanager", R.string.license_title_workmanager, R.string.license_attribution_aosp, "Apache License 2.0"),
    ReiAnixLicenseCredit("media3", R.string.license_title_media3, R.string.license_attribution_aosp, "Apache License 2.0"),
    ReiAnixLicenseCredit("metrics", R.string.license_title_metrics, R.string.license_attribution_aosp, "Apache License 2.0"),
    ReiAnixLicenseCredit("mediarouter", R.string.license_title_mediarouter, R.string.license_attribution_aosp, "Apache License 2.0"),
    ReiAnixLicenseCredit("fragment", R.string.license_title_fragment, R.string.license_attribution_aosp, "Apache License 2.0"),
    ReiAnixLicenseCredit("appcompat", R.string.license_title_appcompat, R.string.license_attribution_aosp, "Apache License 2.0"),
    ReiAnixLicenseCredit("coil", R.string.license_title_coil, R.string.license_attribution_coil, "Apache License 2.0"),
    ReiAnixLicenseCredit("external-services", R.string.license_title_external_services, R.string.license_attribution_services, "",
        licenseAsset = "licenses/external_services.txt", licenseNameRes = R.string.license_service_terms),
    ReiAnixLicenseCredit("google-sans", R.string.license_title_google_sans, R.string.license_attribution_google_sans, "SIL Open Font License 1.1",
        licenseAsset = "licenses/google_sans_OFL.txt"),
)

@Composable
private fun ReiAnixLicenseCredit.localizedLicenseName(): String =
    licenseNameRes?.let { stringResource(it) } ?: licenseName

@Composable
internal fun ReiAnixOpenSourceLicensesScreen(onBack: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = ReiAnixLicenseCredits.firstOrNull { it.id == selectedId }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    BackHandler(enabled = selected != null) { selectedId = null }
    LaunchedEffect(selectedId) { listState.scrollToItem(0) }

    // License texts are bundled offline, not fetched through a network request.
    val licenseText = remember(selected?.licenseAsset) {
        selected?.let { credit ->
            runCatching {
                context.assets.open(credit.licenseAsset).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        }
    }
    val noticeText = remember(selected?.noticeAsset) {
        selected?.noticeAsset?.let { asset ->
            runCatching {
                context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        }
    }

    ReiAnixSettingsRoot {
        Surface(modifier = Modifier.fillMaxSize(), color = reiAnixScreenBackground(), contentColor = MaterialTheme.colorScheme.onBackground) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = LocalReiAnixResponsiveMetrics.current.settingsMaxWidth)
                    .fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = ReiAnixTokens.Spacing.xl),
            ) {
                item(key = "licenses:header") {
                    SettingsHeader(
                        title = stringResource(selected?.title ?: R.string.pref_licenses),
                        subtitle = "",
                        onBack = { if (selected == null) onBack() else selectedId = null },
                        backContentDescription = stringResource(R.string.ui_back),
                    )
                }
                if (selected == null) {
                    item(key = "licenses:intro") {
                        Text(
                            stringResource(R.string.license_intro),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(ReiAnixLicenseCredits, key = { "license:" + it.id }) { credit ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .settingsClickable(role = Role.Button) { selectedId = credit.id }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(credit.title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    credit.localizedLicenseName(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    item(key = "licenses:generated") {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .settingsClickable(role = Role.Button) {
                                    OssLicensesMenuActivity.setActivityTitle(context.getString(R.string.tail_third_party_licenses))
                                    context.startActivity(Intent(context, OssLicensesMenuActivity::class.java))
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.tail_third_party_licenses), style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.tail_library_notices), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(24.dp))
                        }
                    }
                    item(key = "licenses:footnote") {
                        Text(
                            stringResource(R.string.license_footnote),
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    item(key = "licenses:detail") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(selected.localizedLicenseName(), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(selected.attribution),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            noticeText?.let { notice ->
                                Text(notice.getOrNull() ?: stringResource(R.string.license_notice_read_error), style = MaterialTheme.typography.bodySmall)
                            }
                            Text(licenseText?.getOrNull() ?: stringResource(R.string.license_read_error), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

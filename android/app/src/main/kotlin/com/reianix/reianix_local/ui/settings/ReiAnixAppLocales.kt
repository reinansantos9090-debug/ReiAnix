package com.reianix.reianix_local.ui.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import com.reianix.reianix_local.MainActivity
import java.util.Locale

internal object ReiAnixAppLocales {
    private const val PREFS = "reianix_app_locale"
    private const val KEY = "language_tag"
    private const val DEFAULT_LOCALE = "pt-BR"
    val supported = listOf("pt-BR", "en", "es", "ru", "ja", "de", "ko", "zh-Hans", "it", "fr")

    fun selected(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            supportedTag(PlatformLocales.selected(context))
        } else legacySelection(context)

    fun initializeDefault(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 && PlatformLocales.selected(context).isEmpty()) {
            PlatformLocales.change(context, legacySelection(context))
        }
    }

    private fun legacySelection(context: Context): String =
        supportedTag(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    private fun supportedTag(tag: String?): String {
        val locale = Locale.forLanguageTag(tag.orEmpty())
        val canonicalTag = locale.toLanguageTag()
        return canonicalTag.takeIf { it in supported }
            ?: supported.firstOrNull { it.substringBefore('-') == locale.language }
            ?: DEFAULT_LOCALE
    }

    fun localizedContext(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val tag = selected(context)
        val configuration = Configuration()
        configuration.setLocales(LocaleList.forLanguageTags(tag))
        return context.createConfigurationContext(configuration)
    }

    fun change(context: Context, tag: String) {
        if (tag !in supported || selected(context) == tag) return
        if (Build.VERSION.SDK_INT >= 33) {
            PlatformLocales.change(context, tag)
        } else {
            val activity = context.findActivity() ?: return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, tag).apply()
            when (activity) {
                is MainActivity -> activity.refreshLegacyLocaleResources()
                else -> activity.recreate()
            }
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
        else -> null
    }

    @RequiresApi(33)
    private object PlatformLocales {
        fun selected(context: Context): String =
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales?.get(0)?.toLanguageTag().orEmpty()

        fun change(context: Context, tag: String) {
            context.getSystemService(LocaleManager::class.java)?.let {
                it.applicationLocales = LocaleList.forLanguageTags(tag)
            }
        }
    }
}

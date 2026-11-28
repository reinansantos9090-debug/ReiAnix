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

internal object ReiAnixAppLocales {
    private const val PREFS = "reianix_app_locale"
    private const val KEY = "language_tag"
    private val supported = setOf("", "pt-BR", "en", "es", "ru", "ja")

    fun selected(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) PlatformLocales.selected(context)
        else context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty().takeIf { it in supported }.orEmpty()

    fun localizedContext(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val tag = selected(context)
        if (tag.isEmpty()) return context
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
                ?.applicationLocales?.toLanguageTags().orEmpty()

        fun change(context: Context, tag: String) {
            context.getSystemService(LocaleManager::class.java)?.let {
                it.applicationLocales = LocaleList.forLanguageTags(tag)
            }
        }
    }
}

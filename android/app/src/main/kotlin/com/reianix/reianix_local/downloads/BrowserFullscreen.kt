package com.reianix.reianix_local.downloads

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Own the custom view requested by WebView's Fullscreen API, including Back and disposal. */
internal class BrowserFullscreen(private val activity:Activity) {
    private var container:FrameLayout?=null
    private var callback:WebChromeClient.CustomViewCallback?=null
    private var barsWereVisible=true
    private var previousBehavior=0
    private var previousOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var fullscreenWasSet=false
    val active:Boolean get()=container!=null

    fun show(view:View,result:WebChromeClient.CustomViewCallback) {
        if(active){result.onCustomViewHidden();return}
        val decor=activity.window.decorView as ViewGroup
        barsWereVisible=ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.systemBars()) ?: true
        val controls=WindowCompat.getInsetsController(activity.window,decor)
        previousBehavior=controls.systemBarsBehavior
        previousOrientation=activity.requestedOrientation
        fullscreenWasSet=activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // A landscape video kept inside a portrait WebView leaves most of the
        // screen black. Match normal mobile browser behavior while fullscreen.
        activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        callback=result
        container=FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn=true
            addView(view,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
            decor.addView(this,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
        }
        controls.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controls.hide(WindowInsetsCompat.Type.systemBars())
    }
    fun hide() {
        val holder=container ?: return
        container=null
        holder.removeAllViews()
        (holder.parent as? ViewGroup)?.removeView(holder)
        activity.requestedOrientation=previousOrientation
        if(!fullscreenWasSet)activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val controls=WindowCompat.getInsetsController(activity.window,activity.window.decorView)
        controls.systemBarsBehavior=previousBehavior
        if(barsWereVisible)controls.show(WindowInsetsCompat.Type.systemBars())
        val done=callback;callback=null
        done?.onCustomViewHidden()
    }
}

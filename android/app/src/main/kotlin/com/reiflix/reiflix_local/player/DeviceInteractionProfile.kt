package com.reiflix.reiflix_local.player
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.view.InputDevice
import org.json.JSONObject

/** Detects physical interaction capabilities without guessing from resolution. */
data class DeviceInteractionProfile(
    val isTelevision: Boolean,
    val hasTouchscreen: Boolean,
    val hasDpad: Boolean,
    val hasGamepad: Boolean,
    val hasKeyboard: Boolean,
    val hasMouse: Boolean,
) {
    val prefersRemoteNavigation: Boolean
        get() = isTelevision || hasDpad || hasGamepad

    fun fingerprint(): String = listOf(
        isTelevision, hasTouchscreen, hasDpad, hasGamepad, hasKeyboard, hasMouse,
    ).joinToString(",")

    fun toJson(): JSONObject = JSONObject()
        .put("isTelevision", isTelevision)
        .put("hasTouchscreen", hasTouchscreen)
        .put("hasDpad", hasDpad)
        .put("hasGamepad", hasGamepad)
        .put("hasKeyboard", hasKeyboard)
        .put("hasMouse", hasMouse)
        .put("prefersRemoteNavigation", prefersRemoteNavigation)

    companion object {
        fun detect(context: Context): DeviceInteractionProfile {
            val packageManager = context.packageManager
            val uiMode = context.getSystemService(UiModeManager::class.java)
            val isTelevision = uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            var hasTouchscreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
            var hasDpad = false
            var hasGamepad = false
            var hasKeyboard = false
            var hasMouse = false
            val inputManager = context.getSystemService(InputManager::class.java)
            inputManager?.inputDeviceIds?.forEach { id ->
                val device = inputManager.getInputDevice(id) ?: return@forEach
                val sources = device.sources
                hasDpad = hasDpad || (sources and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
                hasGamepad = hasGamepad || (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                hasKeyboard = hasKeyboard || (sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
                hasMouse = hasMouse || (sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                hasTouchscreen = hasTouchscreen || (sources and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
            }
            return DeviceInteractionProfile(
                isTelevision, hasTouchscreen, hasDpad, hasGamepad, hasKeyboard, hasMouse,
            )
        }
    }
}

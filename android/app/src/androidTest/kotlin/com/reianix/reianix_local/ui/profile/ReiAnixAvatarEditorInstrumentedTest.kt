package com.reianix.reianix_local.ui.profile

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.reianix.reianix_local.ui.theme.ReiAnixComposeTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReiAnixAvatarEditorInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun editorKeepsLargeControlsWhilePhotoIsPrepared() {
        val draft = mutableStateOf<File?>(null)
        val loading = mutableStateOf(true)
        var saved: AvatarCropRect? = null
        var cancelled = 0
        val file = File.createTempFile("avatar-editor-test", ".jpg", composeRule.activity.cacheDir)
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally {
            bitmap.recycle()
        }
        try {
            composeRule.setContent {
                ReiAnixComposeTheme(themeMode = "dark") {
                    ReiAnixAvatarEditor(
                        draft = draft.value,
                        busy = loading.value,
                        error = null,
                        onCancel = { cancelled++ },
                        onSave = { saved = it },
                        loadingSelection = loading.value,
                    )
                }
            }
            val cancel = composeRule.onNodeWithTag("avatar-editor-cancel")
            val save = composeRule.onNodeWithTag("avatar-editor-save")
            cancel.assertIsDisplayed().assertHeightIsAtLeast(56.dp).assertIsNotEnabled()
            save.assertIsDisplayed().assertHeightIsAtLeast(56.dp).assertIsNotEnabled()
            composeRule.onNodeWithText("Reverter").assertDoesNotExist()
            composeRule.onNodeWithTag("avatar-editor-loading").assertIsDisplayed()
            val before = save.fetchSemanticsNode().boundsInRoot
            composeRule.runOnIdle {
                draft.value = file
                loading.value = false
            }
            composeRule.waitUntil(timeoutMillis = 5000) {
                composeRule.onAllNodes(hasTestTag("avatar-editor-save") and isEnabled())
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(before, save.fetchSemanticsNode().boundsInRoot)
            save.performClick()
            cancel.assertIsEnabled().performClick()
            composeRule.runOnIdle {
                assertEquals(AvatarCropRect(20, 0, 80), saved)
                assertEquals(1, cancelled)
            }
        } finally {
            file.delete()
        }
    }
}

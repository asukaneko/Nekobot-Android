package com.nekobot.app.ui.screens.sessions

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import com.nekobot.app.R
import com.nekobot.app.ui.theme.NekobotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CompanionAvatarEditorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun accessibilityCanMoveCropWithoutDraggingAndSaveThatPosition() {
        var saved: AvatarCrop? = null
        val source = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        compose.setContent { NekobotTheme { CompanionAvatarCropDialog(source, false, false, {}, {}, { saved = it }) } }
        val node = compose.onNodeWithTag("companion-avatar-crop")
        node.assertContentDescriptionEquals(compose.activity.getString(R.string.avatar_crop_area))
        val actions = node.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertEquals(4, actions.size)
            assertTrue(actions.single { it.label == compose.activity.getString(R.string.avatar_crop_right) }.action())
        }
        compose.onNodeWithText(compose.activity.getString(R.string.companion_avatar_save)).performClick()
        compose.runOnIdle { assertEquals(AvatarCrop(240, 0, 400), saved) }
    }
    @Test fun busyCropHasNoAccessibilityMutationActions() {
        val source = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        compose.setContent { NekobotTheme { CompanionAvatarCropDialog(source, true, false, {}, {}, {}) } }
        val node = compose.onNodeWithTag("companion-avatar-crop")
        node.assertIsNotEnabled()
        assertTrue(node.fetchSemanticsNode().config[SemanticsActions.CustomActions].isEmpty())
    }
    @Test fun cancelAfterEditingDoesNotCallSave() {
        var saved = 0; var cancelled = 0
        val source = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        compose.setContent { NekobotTheme { CompanionAvatarCropDialog(source, false, false, {}, { cancelled++ }, { saved++ }) } }
        compose.onNodeWithTag("companion-avatar-zoom").performTouchInput { swipeRight() }
        compose.onNodeWithText(compose.activity.getString(R.string.common_cancel)).performClick()
        compose.runOnIdle { assertEquals(0, saved); assertEquals(1, cancelled) }
    }
    @Test fun busySaveIsDisabledAndPreviewDoesNotPersist() {
        var saved = 0
        val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        compose.setContent { NekobotTheme { CompanionAvatarCropDialog(source, true, false, {}, {}, { saved++ }) } }
        compose.onNodeWithText(compose.activity.getString(R.string.companion_avatar_save)).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, saved) }
    }
    @Test fun savingUsesTheVisibleCropAndProducesSquareImage() {
        var result: AvatarCrop? = null
        val source = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        for (x in 400 until 800) for (y in 0 until 400) source.setPixel(x, y, Color.BLUE)
        compose.setContent { NekobotTheme { CompanionAvatarCropDialog(source, false, false, {}, {}, { result = it }) } }
        compose.onNodeWithText(compose.activity.getString(R.string.companion_avatar_save)).performClick()
        compose.runOnIdle {
            assertEquals(AvatarCrop(200, 0, 400), result)
            val encoded = encodeAvatarCrop(source, AvatarCrop(400, 0, 400))
            val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
            assertEquals(512, decoded.width); assertEquals(512, decoded.height)
            assertEquals(Color.BLUE, decoded.getPixel(256, 256))
            assertFalse(source.isRecycled)
            decoded.recycle()
        }
    }
}

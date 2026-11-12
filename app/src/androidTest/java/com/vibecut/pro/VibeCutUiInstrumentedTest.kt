package com.vibecut.pro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibecut.pro.ui.screens.OnboardingScreen
import com.vibecut.pro.ui.theme.VibeCutProTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke tests: the app must render on a compact phone and on a tablet without
 * clipped or unreachable controls, and the onboarding flow must actually complete.
 */
@RunWith(AndroidJUnit4::class)
class VibeCutUiInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun onboardingFlowRendersAcrossCompactPhoneAndTabletSizes() {
        var completed = false
        composeTestRule.setContent {
            VibeCutProTheme {
                Box(modifier = Modifier.size(width = 360.dp, height = 780.dp)) {
                    OnboardingScreen(onFinish = { completed = true })
                }
            }
        }

        composeTestRule.onNodeWithText("Precision VN-Style Timeline").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continue").performClick()
        composeTestRule.onNodeWithText("OpenGL ES 2.0 SHADERS").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continue").performClick()
        composeTestRule.onNodeWithText("Music Sync, Captions & Audio Mixing").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open ZERO Studio").performClick()
        assertTrue(completed)
    }

    @Test
    fun tabletWidthKeepsThePrimaryActionOnScreen() {
        var completed = false
        composeTestRule.setContent {
            VibeCutProTheme {
                Box(modifier = Modifier.size(width = 800.dp, height = 1280.dp)) {
                    OnboardingScreen(onFinish = { completed = true })
                }
            }
        }
        composeTestRule.onNodeWithText("Continue").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Continue").assertIsDisplayed()
        composeTestRule.onNodeWithText("Skip").assertIsDisplayed().performClick()
        assertTrue(completed)
    }
}

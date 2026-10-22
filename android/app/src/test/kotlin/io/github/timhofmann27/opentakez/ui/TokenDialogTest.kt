package io.github.timhofmann27.opentakez.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.timhofmann27.opentakez.ui.home.TokenDialog
import io.github.timhofmann27.opentakez.ui.home.TokenDialogState
import io.github.timhofmann27.opentakez.ui.ports.ConnectionResult
import io.github.timhofmann27.opentakez.ui.theme.OpenTakezTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Default window size on purpose: dialogs in a very tall Robolectric window never settle.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class TokenDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private fun passwordMatcher() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

    @Test
    fun `the token dialog masks what is typed and offers no copy or cut`() {
        rule.setContent {
            OpenTakezTheme(dynamicColor = false) {
                TokenDialog(TokenDialogState(), onSubmit = {}, onDismiss = {})
            }
        }

        rule.onNodeWithText("Access token required").assertExists()
        rule.onNode(passwordMatcher()).performTextInput("hunter2-secret")

        rule.onNode(passwordMatcher()).assertMasked("hunter2-secret")
    }

    @Test
    fun `the token is not restored after the activity is recreated`() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            OpenTakezTheme(dynamicColor = false) {
                TokenDialog(TokenDialogState(), onSubmit = {}, onDismiss = {})
            }
        }
        rule.onNode(passwordMatcher()).performTextInput("hunter2-secret")
        rule.onNode(hasText("Continue") and hasClickAction()).assertIsEnabled()

        restoration.emulateSavedInstanceStateRestore()

        rule.onNode(hasText("Continue") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun `a rejected token is explained in the dialog`() {
        rule.setContent {
            OpenTakezTheme(dynamicColor = false) {
                TokenDialog(TokenDialogState(failure = ConnectionResult.TokenRejected), onSubmit = {}, onDismiss = {})
            }
        }
        rule.onNodeWithText("That token was not accepted.").assertExists()
    }

    @Test
    fun `submitting hands the typed token to the callback`() {
        val submitted = mutableListOf<String>()
        rule.setContent {
            OpenTakezTheme(dynamicColor = false) {
                TokenDialog(TokenDialogState(), onSubmit = { submitted += it }, onDismiss = {})
            }
        }
        rule.onNode(passwordMatcher()).performTextInput("abc")
        rule.onNode(hasText("Continue") and hasClickAction()).performClick()
        assertEquals(listOf("abc"), submitted)
    }
}

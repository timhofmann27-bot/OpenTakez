package io.github.timhofmann27.opentakez.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Runs `viewModelScope` work eagerly so ViewModel tests need no waiting. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

/**
 * Robolectric on API 36 and up needs JVM flags that the build does not set (`--add-exports
 * java.base/jdk.internal.access=ALL-UNNAMED`), so the UI tests run on API 35, the newest SDK that
 * works out of the box. Raise it once the build sets the flag.
 */
const val ROBOLECTRIC_SDK = 35

/** A phone-sized window tall enough that nothing needs scrolling; tests that click use it. */
const val TALL_SCREEN = "w411dp-h2000dp"

/**
 * A password field exposes only dots as its text, is flagged as a password, and offers no copy or cut.
 * (`InputText` still carries the raw value for autofill and the test framework; that is Compose's
 * own behavior and not something the UI sets.)
 */
fun SemanticsNodeInteraction.assertMasked(secret: String) {
    val config = fetchSemanticsNode().config
    assertTrue("not flagged as a password", config.contains(SemanticsProperties.Password))
    val shown = config[SemanticsProperties.EditableText].text
    assertFalse("clear text in EditableText", shown.contains(secret))
    assertTrue("not masked: $shown", shown.isNotEmpty() && shown.all { it == '\u2022' })
    assertTrue(config.getOrNull(SemanticsProperties.Text).orEmpty().none { it.text.contains(secret) })
    assertFalse(config.contains(SemanticsActions.CopyText))
    assertFalse(config.contains(SemanticsActions.CutText))
}

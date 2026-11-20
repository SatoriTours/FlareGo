package com.flarego

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.application.connection.TokenConnectionStatus
import com.flarego.core.application.connection.TokenPhase
import com.flarego.core.ports.AccountSummary
import com.flarego.ui.FlareGoTheme
import com.flarego.ui.TokenConnection
import com.flarego.ui.TokenConnectionDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Narrow device + large font + a real IME. The acceptance failure was geometric: with no IME inset
 * the scroll range ended at the window bottom, so the last content stayed behind the keyboard no
 * matter how far the user scrolled, and the top bar sat under the status bar.
 */
class TokenConnectionImeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Fake : TokenConnection {
        val mutable = MutableStateFlow(TokenConnectionStatus())
        override val state: StateFlow<TokenConnectionStatus> = mutable.asStateFlow()
        val manual = mutableListOf<String>()
        var cancels = 0
        val submitted = mutableListOf<String>()
        override fun submit(token: String) { submitted += token }
        override fun retry() {}
        override fun loadMore() {}
        override fun selectAccount(accountId: String) {}
        override fun useAccountId(accountId: String) { manual += accountId }
        override fun cancel() { cancels++ }
    }

    private fun density(): Float = compose.activity.resources.displayMetrics.density

    private fun imeBottom(): Int =
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0

    private fun statusBarBottom(): Int =
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0

    private fun awaitIme(): Int {
        repeat(40) {
            compose.waitForIdle()
            val bottom = imeBottom()
            if (bottom > 0) return bottom
            Thread.sleep(50)
        }
        return imeBottom()
    }

    private fun install(fake: Fake) {
        compose.setContent {
            FlareGoTheme { Surface(Modifier.fillMaxSize()) { TokenConnectionDialog({}, fake) } }
        }
    }

    private fun shoot(name: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val bitmap = checkNotNull(automation.takeScreenshot())
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p /sdcard/Pictures/token-ime")).use { it.readBytes() }
        val pipes = automation.executeShellCommandRw("dd of=/sdcard/Pictures/token-ime/" + name + ".png bs=8192")
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        } finally { bitmap.recycle(); pipes.forEach { runCatching { it.close() } } }
    }

    /** Scrolls a node into its viewport; with no IME inset the viewport bottom sits under the keyboard. */
    private fun scrollIntoView(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo()
        compose.waitForIdle()
    }

    private fun assertAboveIme(tag: String, imeHeight: Int) {
        val limitDp = (compose.activity.window.decorView.height - imeHeight) / density()
        val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        assertTrue(
            tag + " 必须完全位于键盘上方（bottom=" + bounds.bottom.value + "dp, 键盘顶=" + limitDp + "dp）",
            bounds.bottom.value <= limitDp + 1f,
        )
    }

    @Test fun narrowLargeFontKeepsActionsAboveKeyboardAndBelowStatusBar() {
        val config = compose.activity.resources.configuration
        assertEquals("本用例需要在 320dp 宽设备上运行", 320, config.screenWidthDp)
        assertTrue("本用例需要在 2x 字体下运行", config.fontScale >= 1.9f)
        val fake = Fake()
        install(fake)
        compose.onNodeWithTag("token-create").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("token-existing").performScrollTo().performClick()
        compose.onNodeWithTag("token-field").performScrollTo().performClick()
        val ime = awaitIme()
        assertTrue("键盘必须实际弹出，实际 ime 高度=" + ime, ime > 0)
        compose.onNodeWithTag("token-field").performTextInput("fake-token-not-real")
        val titleTop = compose.onNodeWithTag("token-title").getUnclippedBoundsInRoot().top.value
        assertTrue("顶栏不能压到状态栏（top=" + titleTop + "dp）", titleTop >= statusBarBottom() / density() - 1f)
        // Creation now lives on the preceding page; its entry from the paste step stays reachable.
        scrollIntoView("token-create-back")
        assertAboveIme("token-create-back", ime)
        scrollIntoView("token-paste")
        assertAboveIme("token-paste", ime)
        scrollIntoView("token-submit")
        assertAboveIme("token-submit", ime)
        shoot("ime-scrolled-end")
        compose.onNodeWithTag("token-submit").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("fake-token-not-real"), fake.submitted) }
    }

    @Test fun narrowLargeFontManualAccountIdChainWorksAboveKeyboard() {
        val config = compose.activity.resources.configuration
        assertEquals("本用例需要在 320dp 宽设备上运行", 320, config.screenWidthDp)
        assertTrue("本用例需要在 2x 字体下运行", config.fontScale >= 1.9f)
        val fake = Fake()
        install(fake)
        compose.runOnIdle {
            fake.mutable.value = TokenConnectionStatus(
                phase = TokenPhase.CHOOSING, tokenHeld = true, needsAccountId = true,
                accounts = listOf(AccountSummary("0123456789abcdef0123456789abcdef", "示例账号")),
            )
        }
        compose.onNodeWithTag("token-manual-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("token-account-id").performScrollTo().performClick()
        val ime = awaitIme()
        assertTrue("键盘必须实际弹出，实际 ime 高度=" + ime, ime > 0)
        compose.onNodeWithTag("token-account-id").performTextInput("0123456789abcdef0123456789abcdef")
        compose.onNodeWithTag("token-manual-submit").performScrollTo()
        assertAboveIme("token-manual-submit", ime)
        shoot("ime-manual")
        compose.onNodeWithTag("token-manual-submit").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("0123456789abcdef0123456789abcdef"), fake.manual) }
    }
}

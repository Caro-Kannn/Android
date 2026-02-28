/*
 * Copyright (c) 2024 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.autoconsent.impl

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.duckduckgo.autoconsent.api.AutoconsentCallback
import com.duckduckgo.autoconsent.impl.cache.RealAutoconsentSettingsCache
import com.duckduckgo.autoconsent.impl.handlers.InitMessageHandlerPlugin
import com.duckduckgo.autoconsent.impl.remoteconfig.AutoconsentFeature
import com.duckduckgo.common.test.CoroutineTestRule
import com.duckduckgo.common.utils.plugins.PluginPoint
import com.duckduckgo.feature.toggles.api.FakeFeatureToggleFactory
import kotlinx.coroutines.test.TestScope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.Shadows.shadowOf

/**
 * Proof-of-Concept test for the UXSS vulnerability in AutoconsentInterface.
 *
 * VULNERABILITY SUMMARY
 * =====================
 * Android's `WebView.addJavascriptInterface()` makes the registered Kotlin object callable
 * from ALL frames in the WebView — including cross-origin iframes. This is especially
 * dangerous in a browser because browsers load arbitrary untrusted pages that commonly
 * embed cross-origin iframes (ads, analytics, social widgets, etc.).
 *
 * The old `AutoconsentInterface.process(message)` had no authentication mechanism.
 * Any JavaScript running in any frame could call `AutoconsentAndroid.process(...)`.
 *
 * ATTACK CHAIN (before fix)
 * =========================
 * 1. User navigates to https://victim.com in the DuckDuckGo browser.
 * 2. victim.com embeds an attacker-controlled iframe: <iframe src="https://evil.com/attack.html">
 * 3. evil.com/attack.html executes (from within the WebView, cross-origin):
 *      AutoconsentAndroid.process('{"type":"init","url":"http://victim.com"}')
 * 4. InitMessageHandlerPlugin processes the message and calls:
 *      webView.evaluateJavascript("javascript:window.autoconsentMessageCallback(...)", null)
 * 5. evaluateJavascript() ALWAYS runs in the main frame's origin context (victim.com),
 *    regardless of which frame triggered the process() call.
 * 6. The attacker has now caused JavaScript to execute in victim.com's origin context,
 *    with full access to victim.com's cookies, localStorage, and DOM.
 *
 * FIX
 * ===
 * A per-session random UUID secret is generated in Kotlin and injected exclusively into
 * the main frame via:
 *   webView.evaluateJavascript("javascript:window.autoconsentAndroidSecret='<uuid>';", null)
 *
 * Cross-origin iframes cannot read window.autoconsentAndroidSecret (Same-Origin Policy).
 * Every call to AutoconsentInterface.process() now requires the secret as a second parameter.
 * Calls without the correct secret are silently rejected before reaching any handler.
 */
@RunWith(AndroidJUnit4::class)
class UxssCrossOriginPocTest {

    @get:Rule
    val coroutineRule = CoroutineTestRule()

    private val webView: WebView = WebView(InstrumentationRegistry.getInstrumentation().targetContext)
    private val mockCallback: AutoconsentCallback = mock()
    private val fakePluginPoint = FakePluginPoint()
    private val settingsRepository = FakeSettingsRepository()
    private val settingsCache = RealAutoconsentSettingsCache()
    private val feature = FakeFeatureToggleFactory.create(AutoconsentFeature::class.java)

    // --- PoC 1: Secret gate blocks unauthenticated cross-origin calls ----------------------

    @Test
    fun poc1_attackerIframeWithoutSecretIsRejectedAtTheGate() {
        // GIVEN: The fixed AutoconsentInterface (requires a valid per-session secret)
        val secureInterface = AutoconsentInterface(fakePluginPoint, webView, mockCallback, TEST_SESSION_SECRET)

        // WHEN: An attacker iframe tries to call process()
        //   – the iframe cannot read window.autoconsentAndroidSecret from the cross-origin
        //     main frame (blocked by Same-Origin Policy), so it has no valid secret.
        val attackMessage = """{"type":"fake"}"""
        secureInterface.process(attackMessage, "")            // empty secret
        secureInterface.process(attackMessage, "guessed")     // guessed secret

        // THEN: No handler plugin is ever invoked — attack is stopped at the interface level
        assertEquals(
            "Handler must NOT be called when the caller provides no valid secret",
            0,
            fakePluginPoint.plugin.count,
        )
    }

    @Test
    fun poc1_legitimateMainFrameCallWithCorrectSecretSucceeds() {
        // GIVEN: The fixed interface and the main frame JS that holds window.autoconsentAndroidSecret
        val secureInterface = AutoconsentInterface(fakePluginPoint, webView, mockCallback, TEST_SESSION_SECRET)

        // WHEN: The main frame's own autoconsent JS passes the correct secret
        secureInterface.process("""{"type":"fake"}""", TEST_SESSION_SECRET)

        // THEN: Handler is invoked — the legitimate flow continues as designed
        assertEquals(
            "Handler must be called when the correct session secret is provided",
            1,
            fakePluginPoint.plugin.count,
        )
    }

    // --- PoC 2: End-to-end demonstration — evaluateJavascript in the main frame -----------

    @Test
    fun poc2_vulnerableInterface_attackerIframeTriggers_evaluateJavascript_inMainFrameContext() {
        // GIVEN: The pre-fix, vulnerable AutoconsentInterface (no secret parameter, no check),
        //        with autoconsent enabled and settings populated so the handler produces output.
        settingsRepository.userSetting = true
        settingsCache.updateSettings(RULESET_JSON)

        val initPlugin = buildInitPlugin()
        val vulnerableInterface = VulnerableAutoconsentInterface(
            object : PluginPoint<MessageHandlerPlugin> {
                override fun getPlugins() = listOf(initPlugin)
            },
            webView,
            mockCallback,
        )

        // WHEN: An attacker-controlled cross-origin iframe executes from within the WebView:
        //   AutoconsentAndroid.process('{"type":"init","url":"http://victim.com"}')
        //
        // In the pre-fix code this was the complete call — no second argument required.
        vulnerableInterface.process("""{"type":"init","url":"http://victim.com"}""")

        // THEN: webView.evaluateJavascript() was invoked — JavaScript is now executing in the
        //       main frame's origin context (victim.com), triggered by the attacker's iframe.
        //       This is the UXSS: the attacker caused code to run in a cross-origin context.
        val injectedJs = shadowOf(webView).lastEvaluatedJavascript
        assertNotNull(
            "UXSS confirmed: attacker iframe successfully triggered evaluateJavascript() " +
                "in the main frame's origin context without any authentication",
            injectedJs,
        )
        // The injected JS invokes the autoconsent callback that runs *as* the victim page
        assertTrue(
            "Injected JS invokes autoconsentMessageCallback in the main frame's origin",
            injectedJs!!.contains("autoconsentMessageCallback"),
        )
    }

    @Test
    fun poc2_fixedInterface_attackerIframeCannotTrigger_evaluateJavascript() {
        // GIVEN: The fixed AutoconsentInterface with a per-session random secret,
        //        and the same active autoconsent settings as the vulnerable test above.
        settingsRepository.userSetting = true
        settingsCache.updateSettings(RULESET_JSON)

        val initPlugin = buildInitPlugin()
        val secureInterface = AutoconsentInterface(
            object : PluginPoint<MessageHandlerPlugin> {
                override fun getPlugins() = listOf(initPlugin)
            },
            webView,
            mockCallback,
            TEST_SESSION_SECRET,
        )

        // WHEN: The same attacker iframe call — but now the interface demands the secret.
        //   The iframe cannot read window.autoconsentAndroidSecret from the main frame
        //   (Same-Origin Policy blocks cross-origin window property access), so it sends
        //   an empty or guessed secret.
        secureInterface.process("""{"type":"init","url":"http://victim.com"}""", "")
        secureInterface.process("""{"type":"init","url":"http://victim.com"}""", "cracked-guess")

        // THEN: evaluateJavascript() is NOT called — the UXSS attack is completely neutralised.
        assertNull(
            "Fix confirmed: attacker iframe cannot trigger evaluateJavascript() " +
                "without the per-session secret that is inaccessible across origins",
            shadowOf(webView).lastEvaluatedJavascript,
        )
    }

    // --------------------------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------------------------

    private fun buildInitPlugin() = InitMessageHandlerPlugin(
        TestScope(),
        coroutineRule.testDispatcherProvider,
        settingsRepository,
        settingsCache,
        feature,
        mock(),
    )

    /**
     * Represents the old, vulnerable AutoconsentInterface **before** the secret check was
     * introduced. The only difference from the current production class is that [process]
     * accepts no secret parameter and performs no authentication check whatsoever —
     * exactly what any cross-origin iframe could exploit.
     */
    private class VulnerableAutoconsentInterface(
        private val messageHandlerPlugins: PluginPoint<MessageHandlerPlugin>,
        private val webView: WebView,
        private val autoconsentCallback: AutoconsentCallback,
    ) {
        // No secret parameter — mirrors the pre-fix API
        fun process(message: String) {
            try {
                val parsedMessage = JSONObject(message)
                val type: String = parsedMessage.getString("type")
                messageHandlerPlugins.getPlugins()
                    .firstOrNull { it.supportedTypes.contains(type) }
                    ?.process(type, message, webView, autoconsentCallback)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    companion object {
        // Session secret used in tests that verify the fixed interface.
        // Any non-empty UUID value works; this represents what the app generates at runtime.
        private const val TEST_SESSION_SECRET = "e7f2a1b3-41c0-4d9e-8f5a-0b2c3d4e5f6a"

        // Minimal ruleset JSON that allows InitMessageHandlerPlugin to produce a reply.
        // Verified by the existing test `whenProcessMessageWithEmptyObjectsInSettingsResponseSentIsCorrect`.
        private const val RULESET_JSON = "{\"disabledCMPs\": [], \"compactRuleList\": {}}"
    }
}

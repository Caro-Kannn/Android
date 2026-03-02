/*
 * Copyright (c) 2018 DuckDuckGo
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

@file:Suppress("RemoveExplicitTypeArguments")

package com.duckduckgo.app.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.core.graphics.get
import androidx.core.net.toUri
import androidx.test.annotation.UiThreadTest
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.duckduckgo.appbuildconfig.api.AppBuildConfig
import com.duckduckgo.common.test.CoroutineTestRule
import com.duckduckgo.site.permissions.api.SitePermissionsManager
import com.duckduckgo.site.permissions.api.SitePermissionsManager.SitePermissions
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.*

class BrowserChromeClientTest {

    private lateinit var testee: BrowserChromeClient
    private lateinit var webView: TestWebView
    private lateinit var mockWebViewClientListener: WebViewClientListener
    private lateinit var mockFilePathCallback: ValueCallback<Array<Uri>>
    private lateinit var mockFileChooserParams: WebChromeClient.FileChooserParams
    private lateinit var mockAppBuildConfig: AppBuildConfig
    private lateinit var mockSitePermissionsManager: SitePermissionsManager
    private val fakeView = View(getInstrumentation().targetContext)

    @get:Rule
    val coroutineTestRule = CoroutineTestRule()

    @UiThreadTest
    @Before
    fun setup() {
        mockAppBuildConfig = mock()
        mockSitePermissionsManager = mock()
        testee = BrowserChromeClient(
            mockAppBuildConfig,
            TestScope(),
            coroutineTestRule.testDispatcherProvider,
            mockSitePermissionsManager,
        )
        mockWebViewClientListener = mock()
        mockFilePathCallback = mock()
        mockFileChooserParams = mock()
        testee.webViewClientListener = mockWebViewClientListener
        webView = TestWebView(getInstrumentation().targetContext)
        mockSitePermissionsManager.stub { onBlocking { getSitePermissions(any(), any()) }.thenReturn(SitePermissions(emptyList(), emptyList())) }
    }

    // ===== Original Tests =====

    @Test
    fun whenWindowClosedThenCloseCurrentTab() {
        testee.onCloseWindow(window = null)
        verify(mockWebViewClientListener).closeCurrentTab()
    }

    @Test
    fun whenCustomViewShownForFirstTimeListenerInstructedToGoFullScreen() {
        testee.onShowCustomView(fakeView, null)
        verify(mockWebViewClientListener).goFullScreen(fakeView)
    }

    @Test
    fun whenCustomViewShownMultipleTimesListenerInstructedToGoFullScreenOnlyOnce() {
        testee.onShowCustomView(fakeView, null)
        testee.onShowCustomView(fakeView, null)
        testee.onShowCustomView(fakeView, null)
        verify(mockWebViewClientListener, times(1)).goFullScreen(fakeView)
    }

    @Test
    fun whenCustomViewShownMultipleTimesCallbackInstructedToHideForAllButTheFirstCall() {
        val mockCustomViewCallback: WebChromeClient.CustomViewCallback = mock()
        testee.onShowCustomView(fakeView, mockCustomViewCallback)
        testee.onShowCustomView(fakeView, mockCustomViewCallback)
        testee.onShowCustomView(fakeView, mockCustomViewCallback)
        verify(mockCustomViewCallback, times(2)).onCustomViewHidden()
    }

    @Test
    fun whenHideCustomViewCalledThenListenerInstructedToExistFullScreen() = runTest {
        testee.onHideCustomView()
        verify(mockWebViewClientListener).exitFullScreen()
    }

    @UiThreadTest
    @Test
    fun whenOnProgressChangedCalledThenListenerInstructedToUpdateProgress() {
        testee.onProgressChanged(webView, 10)
        verify(mockWebViewClientListener).progressChanged(eq(20), any()) // Value should come from the webView instance
    }

    @UiThreadTest
    @Test
    fun whenOnProgressChangedCalledAndValueIsZeroThenNothingCalled() {
        val mockWebView: WebView = mock()
        whenever(mockWebView.progress).thenReturn(0)
        testee.onProgressChanged(mockWebView, 10)
        verify(mockWebViewClientListener, never()).progressChanged(any(), any())
    }

    @UiThreadTest
    @Test
    fun whenOnCreateWindowWithUserGestureThenMessageOpenedInNewTab() {
        testee.onCreateWindow(webView, isDialog = false, isUserGesture = true, resultMsg = mockMsg)
        verify(mockWebViewClientListener).openMessageInNewTab(eq(mockMsg))
        verifyNoMoreInteractions(mockWebViewClientListener)
    }

    @UiThreadTest
    @Test
    fun whenOnCreateWindowWithoutUserGestureThenNewTabNotOpened() {
        testee.onCreateWindow(webView, isDialog = false, isUserGesture = false, resultMsg = mockMsg)
        verifyNoInteractions(mockWebViewClientListener)
    }

    @Test
    fun whenOnReceivedIconThenIconReceived() {
        val bitmap: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
        testee.onReceivedIcon(webView, bitmap)
        verify(mockWebViewClientListener).iconReceived(webView.url, bitmap)
    }

    @Test
    fun whenOnReceivedTitleThenTitleReceived() {
        val title = "title"
        testee.onReceivedTitle(webView, title)
        verify(mockWebViewClientListener).titleReceived(title)
    }

    @Test
    fun whenOnShowFileChooserCalledThenShowFileChooser() {
        assertTrue(testee.onShowFileChooser(webView, mockFilePathCallback, mockFileChooserParams))
        verify(mockWebViewClientListener).showFileChooser(mockFilePathCallback, mockFileChooserParams)
    }

    @Test(expected = java.lang.RuntimeException::class)
    fun whenShowFileChooserThrowsExceptionThenRecordException() = runTest {
        val exception = RuntimeException("deliberate")

        whenever(mockWebViewClientListener.showFileChooser(any(), any())).thenThrow(exception)
        testee.onShowFileChooser(webView, mockFilePathCallback, mockFileChooserParams)

        verify(mockFilePathCallback).onReceiveValue(null)
    }

    @Test
    fun whenOnMediaPermissionRequestIfDomainIsAllowToAskThenRequestPermission() = runTest {
        val permissions = SitePermissions(
            userHandled = listOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID),
            autoAccept = emptyList(),
        )
        val mockPermission: PermissionRequest = mock()
        whenever(mockWebViewClientListener.getCurrentTabId()).thenReturn("id")
        whenever(mockPermission.resources).thenReturn(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
        whenever(mockPermission.origin).thenReturn("https://open.spotify.com".toUri())
        whenever(mockSitePermissionsManager.getSitePermissions(any(), any())).thenReturn(permissions)
        testee.onPermissionRequest(mockPermission)

        verify(mockWebViewClientListener).onSitePermissionRequested(mockPermission, permissions)
    }

    @Test
    fun whenOnCameraPermissionRequestIfDomainIsAllowToAskThenRequestPermission() = runTest {
        val permissions = SitePermissions(
            userHandled = listOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE),
            autoAccept = emptyList(),
        )
        val mockRequest: PermissionRequest = mock()
        whenever(mockWebViewClientListener.getCurrentTabId()).thenReturn("id")
        whenever(mockRequest.resources).thenReturn(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        whenever(mockRequest.origin).thenReturn("https://www.example.com".toUri())
        whenever(mockSitePermissionsManager.getSitePermissions(any(), any())).thenReturn(permissions)

        testee.onPermissionRequest(mockRequest)

        verify(mockWebViewClientListener).onSitePermissionRequested(mockRequest, permissions)
    }

    @Test
    fun whenOnMicPermissionRequestIfDomainIsAllowToAskThenRequestPermission() = runTest {
        val permissions = SitePermissions(
            userHandled = listOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE),
            autoAccept = emptyList(),
        )
        val mockRequest: PermissionRequest = mock()
        whenever(mockWebViewClientListener.getCurrentTabId()).thenReturn("id")
        whenever(mockRequest.resources).thenReturn(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        whenever(mockRequest.origin).thenReturn("https://www.example.com".toUri())
        whenever(mockSitePermissionsManager.getSitePermissions(any(), any())).thenReturn(permissions)

        testee.onPermissionRequest(mockRequest)

        verify(mockWebViewClientListener).onSitePermissionRequested(mockRequest, permissions)
    }

    @Test
    fun whenNotSitePermissionsAreRequestedThenCallOnSitePermissionRequested() = runTest {
        val permissions = SitePermissions(emptyList(), emptyList())
        val mockRequest: PermissionRequest = mock()
        whenever(mockWebViewClientListener.getCurrentTabId()).thenReturn("id")
        whenever(mockRequest.resources).thenReturn(arrayOf())
        whenever(mockRequest.origin).thenReturn("https://www.example.com".toUri())
        whenever(mockSitePermissionsManager.getSitePermissions(any(), any())).thenReturn(permissions)

        testee.onPermissionRequest(mockRequest)

        verify(mockWebViewClientListener, never()).onSitePermissionRequested(mockRequest, permissions)
    }

    @Test
    fun whenGetDefaultVideoPosterThenReturnTransparentPixel() = runTest {
        val bitmap = testee.defaultVideoPoster

        assertEquals(1, bitmap.width)
        assertEquals(1, bitmap.height)
        assertEquals(Color.TRANSPARENT, bitmap[0, 0])
    }

    // ===== Fullscreen Security: Edge Cases =====

    @Test
    fun whenShowCustomViewWithNullCallbackThenFullScreenStillActivated() {
        testee.onShowCustomView(fakeView, null)
        verify(mockWebViewClientListener).goFullScreen(fakeView)
    }

    @Test
    fun whenShowCustomViewWithNullCallbackAndAlreadyFullScreenThenNoExceptionThrown() {
        testee.onShowCustomView(fakeView, null)
        testee.onShowCustomView(fakeView, null)
        // Should not throw; second call is silently ignored
        verify(mockWebViewClientListener, times(1)).goFullScreen(fakeView)
    }

    @Test
    fun whenHideCustomViewCalledWithoutPriorShowThenExitFullScreenStillCalled() {
        testee.onHideCustomView()
        verify(mockWebViewClientListener).exitFullScreen()
    }

    @Test
    fun whenHideCustomViewCalledTwiceThenExitFullScreenCalledTwice() {
        testee.onShowCustomView(fakeView, null)
        testee.onHideCustomView()
        testee.onHideCustomView()
        verify(mockWebViewClientListener, times(2)).exitFullScreen()
    }

    @Test
    fun whenShowHideShowSequenceThenSecondShowAllowed() {
        val fakeView2 = View(getInstrumentation().targetContext)
        testee.onShowCustomView(fakeView, null)
        testee.onHideCustomView()
        testee.onShowCustomView(fakeView2, null)
        verify(mockWebViewClientListener).goFullScreen(fakeView)
        verify(mockWebViewClientListener).goFullScreen(fakeView2)
    }

    @Test
    fun whenRapidShowHideCyclesThenEachCycleIsHandledCorrectly() {
        for (i in 1..10) {
            val view = View(getInstrumentation().targetContext)
            testee.onShowCustomView(view, null)
            testee.onHideCustomView()
        }
        verify(mockWebViewClientListener, times(10)).goFullScreen(any())
        verify(mockWebViewClientListener, times(10)).exitFullScreen()
    }

    @Test
    fun whenShowWithDifferentViewWhileAlreadyFullScreenThenSecondViewRejected() {
        val fakeView2 = View(getInstrumentation().targetContext)
        val mockCallback: WebChromeClient.CustomViewCallback = mock()
        testee.onShowCustomView(fakeView, null)
        testee.onShowCustomView(fakeView2, mockCallback)
        verify(mockWebViewClientListener, times(1)).goFullScreen(fakeView)
        verify(mockWebViewClientListener, never()).goFullScreen(fakeView2)
        verify(mockCallback).onCustomViewHidden()
    }

    @Test
    fun whenListenerRemovedDuringFullScreenThenHideDoesNotCrash() {
        testee.onShowCustomView(fakeView, null)
        testee.webViewClientListener = null
        testee.onHideCustomView()
        // Should not throw NPE; listener is null
    }

    @Test
    fun whenListenerIsNullOnShowCustomViewThenNoInteractionAndNoCrash() {
        testee.webViewClientListener = null
        testee.onShowCustomView(fakeView, null)
        // No exception, goFullScreen not called (no listener)
    }

    @Test
    fun whenListenerIsNullAndCallbackProvidedThenCallbackNotCalledOnFirstEntry() {
        testee.webViewClientListener = null
        val mockCallback: WebChromeClient.CustomViewCallback = mock()
        testee.onShowCustomView(fakeView, mockCallback)
        // Callback should not be called (this is the first entry, not a duplicate)
        verify(mockCallback, never()).onCustomViewHidden()
    }

    @Test
    fun whenListenerIsNullAndAlreadyInFullScreenThenDuplicateCallbackStillFired() {
        testee.onShowCustomView(fakeView, null)
        testee.webViewClientListener = null
        val mockCallback: WebChromeClient.CustomViewCallback = mock()
        testee.onShowCustomView(fakeView, mockCallback)
        verify(mockCallback).onCustomViewHidden()
    }

    // ===== Fullscreen Security: JS Dialog Suppression =====

    @Test
    fun whenJsAlertOnActiveTabThenNotSuppressed() {
        val mockResult: JsResult = mock()
        whenever(mockWebViewClientListener.isActiveTab()).thenReturn(true)
        val suppressed = testee.onJsAlert(null, "https://example.com", "Hello", mockResult)
        assertFalse(suppressed)
        verify(mockResult, never()).cancel()
    }

    @Test
    fun whenJsAlertOnInactiveTabThenSuppressed() {
        val mockResult: JsResult = mock()
        whenever(mockWebViewClientListener.isActiveTab()).thenReturn(false)
        val suppressed = testee.onJsAlert(null, "https://example.com", "Hello", mockResult)
        assertTrue(suppressed)
        verify(mockResult).cancel()
    }

    @Test
    fun whenJsConfirmOnActiveTabThenNotSuppressed() {
        val mockResult: JsResult = mock()
        whenever(mockWebViewClientListener.isActiveTab()).thenReturn(true)
        val suppressed = testee.onJsConfirm(null, "https://example.com", "Confirm?", mockResult)
        assertFalse(suppressed)
        verify(mockResult, never()).cancel()
    }

    @Test
    fun whenJsConfirmOnInactiveTabThenSuppressed() {
        val mockResult: JsResult = mock()
        whenever(mockWebViewClientListener.isActiveTab()).thenReturn(false)
        val suppressed = testee.onJsConfirm(null, "https://example.com", "Confirm?", mockResult)
        assertTrue(suppressed)
        verify(mockResult).cancel()
    }

    @Test
    fun whenJsPromptOnActiveTabThenNotSuppressed() {
        val mockResult: JsPromptResult = mock()
        whenever(mockWebViewClientListener.isActiveTab()).thenReturn(true)
        val suppressed = testee.onJsPrompt(null, "https://example.com", "Enter:", "default", mockResult)
        assertFalse(suppressed)
        verify(mockResult, never()).cancel()
    }

    @Test
    fun whenJsPromptOnInactiveTabThenSuppressed() {
        val mockResult: JsPromptResult = mock()
        whenever(mockWebViewClientListener.isActiveTab()).thenReturn(false)
        val suppressed = testee.onJsPrompt(null, "https://example.com", "Enter:", "default", mockResult)
        assertTrue(suppressed)
        verify(mockResult).cancel()
    }

    @Test
    fun whenJsAlertWithNullListenerThenSuppressed() {
        testee.webViewClientListener = null
        val mockResult: JsResult = mock()
        val suppressed = testee.onJsAlert(null, "https://example.com", "Hello", mockResult)
        assertTrue(suppressed)
        verify(mockResult).cancel()
    }

    @Test
    fun whenJsConfirmWithNullListenerThenSuppressed() {
        testee.webViewClientListener = null
        val mockResult: JsResult = mock()
        val suppressed = testee.onJsConfirm(null, "https://example.com", "Confirm?", mockResult)
        assertTrue(suppressed)
        verify(mockResult).cancel()
    }

    @Test
    fun whenJsPromptWithNullListenerThenSuppressed() {
        testee.webViewClientListener = null
        val mockResult: JsPromptResult = mock()
        val suppressed = testee.onJsPrompt(null, "https://example.com", "Enter:", "default", mockResult)
        assertTrue(suppressed)
        verify(mockResult).cancel()
    }

    // ===== Fullscreen Security: JS Dialogs with Malformed/Invalid Inputs =====

    @Test
    fun whenJsAlertWithEmptyUrlThenBehaviorConsistentWithActiveTab() {
        val mockResult: JsResult = mock()
        whenever(mockWebViewClientListener.isActiveTab()).thenReturn(true)
        val suppressed = testee.onJsAlert(null, "", "", mockResult)
        assertFalse(suppressed)
    }

    @Test
    fun whenJsAlertWithNullViewThenHandledNormally() {
        val mockResult: JsResult = mock()
        whenever(mockWebViewClientListener.isActiveTab()).thenReturn(true)
        val suppressed = testee.onJsAlert(null, "https://example.com", "message", mockResult)
        assertFalse(suppressed)
    }

    @Test
    fun whenJsPromptWithNullUrlAndMessageThenBehaviorConsistentWithActiveTab() {
        val mockResult: JsPromptResult = mock()
        whenever(mockWebViewClientListener.isActiveTab()).thenReturn(true)
        val suppressed = testee.onJsPrompt(null, null, null, null, mockResult)
        assertFalse(suppressed)
    }

    @Test
    fun whenJsConfirmWithNullUrlAndMessageThenBehaviorConsistentWithActiveTab() {
        val mockResult: JsResult = mock()
        whenever(mockWebViewClientListener.isActiveTab()).thenReturn(true)
        val suppressed = testee.onJsConfirm(null, null, null, mockResult)
        assertFalse(suppressed)
    }

    @Test
    fun whenJsAlertWithVeryLongMessageOnActiveTabThenNotSuppressed() {
        val mockResult: JsResult = mock()
        whenever(mockWebViewClientListener.isActiveTab()).thenReturn(true)
        val longMessage = "A".repeat(10000)
        val suppressed = testee.onJsAlert(null, "https://example.com", longMessage, mockResult)
        assertFalse(suppressed)
    }

    @Test
    fun whenJsAlertWithSpecialCharactersInUrlOnActiveTabThenNotSuppressed() {
        val mockResult: JsResult = mock()
        whenever(mockWebViewClientListener.isActiveTab()).thenReturn(true)
        val suppressed = testee.onJsAlert(null, "javascript:void(0)", "XSS attempt", mockResult)
        assertFalse(suppressed)
    }

    @Test
    fun whenJsAlertWithDataUriOnActiveTabThenNotSuppressed() {
        val mockResult: JsResult = mock()
        whenever(mockWebViewClientListener.isActiveTab()).thenReturn(true)
        val suppressed = testee.onJsAlert(null, "data:text/html,<h1>test</h1>", "data uri alert", mockResult)
        assertFalse(suppressed)
    }

    // ===== Fullscreen Security: Fullscreen State During Other Operations =====

    @UiThreadTest
    @Test
    fun whenInFullScreenAndProgressChangedThenProgressStillReported() {
        testee.onShowCustomView(fakeView, null)
        testee.onProgressChanged(webView, 50)
        verify(mockWebViewClientListener).progressChanged(eq(20), any())
    }

    @Test
    fun whenInFullScreenAndTitleReceivedThenTitleStillReported() {
        testee.onShowCustomView(fakeView, null)
        testee.onReceivedTitle(webView, "New Title")
        verify(mockWebViewClientListener).titleReceived("New Title")
    }

    @Test
    fun whenInFullScreenAndIconReceivedThenIconStillReported() {
        val bitmap: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
        testee.onShowCustomView(fakeView, null)
        testee.onReceivedIcon(webView, bitmap)
        verify(mockWebViewClientListener).iconReceived(webView.url, bitmap)
    }

    @Test
    fun whenInFullScreenAndFileChooserRequestedThenFileChooserStillShown() {
        testee.onShowCustomView(fakeView, null)
        assertTrue(testee.onShowFileChooser(webView, mockFilePathCallback, mockFileChooserParams))
        verify(mockWebViewClientListener).showFileChooser(mockFilePathCallback, mockFileChooserParams)
    }

    @Test
    fun whenInFullScreenAndWindowClosedThenTabStillClosed() {
        testee.onShowCustomView(fakeView, null)
        testee.onCloseWindow(null)
        verify(mockWebViewClientListener).closeCurrentTab()
    }

    @Test
    fun whenInFullScreenAndPermissionRequestedThenStillProcessed() = runTest {
        val permissions = SitePermissions(
            userHandled = listOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE),
            autoAccept = emptyList(),
        )
        val mockRequest: PermissionRequest = mock()
        whenever(mockWebViewClientListener.getCurrentTabId()).thenReturn("id")
        whenever(mockRequest.resources).thenReturn(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        whenever(mockRequest.origin).thenReturn("https://www.example.com".toUri())
        whenever(mockSitePermissionsManager.getSitePermissions(any(), any())).thenReturn(permissions)

        testee.onShowCustomView(fakeView, null)
        testee.onPermissionRequest(mockRequest)

        verify(mockWebViewClientListener).onSitePermissionRequested(mockRequest, permissions)
    }

    // ===== Fullscreen Security: Window Creation =====

    @UiThreadTest
    @Test
    fun whenInFullScreenAndWindowCreatedWithGestureThenNewTabOpened() {
        whenever(mockAppBuildConfig.isTest).thenReturn(false)
        testee.onShowCustomView(fakeView, null)
        testee.onCreateWindow(webView, isDialog = false, isUserGesture = true, resultMsg = mockMsg)
        verify(mockWebViewClientListener).openMessageInNewTab(eq(mockMsg))
    }

    @UiThreadTest
    @Test
    fun whenWindowCreatedWithNullResultMsgThenNoNewTab() {
        testee.onCreateWindow(webView, isDialog = false, isUserGesture = true, resultMsg = null)
        verify(mockWebViewClientListener, never()).openMessageInNewTab(any())
    }

    @UiThreadTest
    @Test
    fun whenWindowCreatedAsDialogWithGestureThenNewTabOpened() {
        testee.onCreateWindow(webView, isDialog = true, isUserGesture = true, resultMsg = mockMsg)
        verify(mockWebViewClientListener).openMessageInNewTab(eq(mockMsg))
    }

    @UiThreadTest
    @Test
    fun whenWindowCreatedWithNonTransportObjectThenNoNewTab() {
        val msg = Message().apply {
            target = mock()
            obj = "not a transport"
        }
        testee.onCreateWindow(webView, isDialog = false, isUserGesture = true, resultMsg = msg)
        verify(mockWebViewClientListener, never()).openMessageInNewTab(any())
    }

    // ===== Fullscreen Security: Permission Edge Cases =====

    @Test
    fun whenPermissionRequestedWithNullTabIdThenNotProcessed() = runTest {
        val mockRequest: PermissionRequest = mock()
        whenever(mockWebViewClientListener.getCurrentTabId()).thenReturn(null)
        whenever(mockRequest.resources).thenReturn(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))

        testee.onPermissionRequest(mockRequest)

        verify(mockWebViewClientListener, never()).onSitePermissionRequested(any(), any())
    }

    @Test
    fun whenPermissionRequestedWithMultipleResourcesThenProcessed() = runTest {
        val permissions = SitePermissions(
            userHandled = listOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_AUDIO_CAPTURE),
            autoAccept = emptyList(),
        )
        val mockRequest: PermissionRequest = mock()
        whenever(mockWebViewClientListener.getCurrentTabId()).thenReturn("id")
        whenever(mockRequest.resources).thenReturn(arrayOf(
            PermissionRequest.RESOURCE_VIDEO_CAPTURE,
            PermissionRequest.RESOURCE_AUDIO_CAPTURE,
        ))
        whenever(mockRequest.origin).thenReturn("https://www.example.com".toUri())
        whenever(mockSitePermissionsManager.getSitePermissions(any(), any())).thenReturn(permissions)

        testee.onPermissionRequest(mockRequest)

        verify(mockWebViewClientListener).onSitePermissionRequested(mockRequest, permissions)
    }

    @Test
    fun whenPermissionRequestedWithEmptyResourcesThenNotForwarded() = runTest {
        val permissions = SitePermissions(emptyList(), emptyList())
        val mockRequest: PermissionRequest = mock()
        whenever(mockWebViewClientListener.getCurrentTabId()).thenReturn("id")
        whenever(mockRequest.resources).thenReturn(arrayOf())
        whenever(mockRequest.origin).thenReturn("https://www.example.com".toUri())
        whenever(mockSitePermissionsManager.getSitePermissions(any(), any())).thenReturn(permissions)

        testee.onPermissionRequest(mockRequest)

        verify(mockWebViewClientListener, never()).onSitePermissionRequested(any(), any())
    }

    @Test
    fun whenPermissionRequestedWithNullListenerThenNoException() = runTest {
        testee.webViewClientListener = null
        val mockRequest: PermissionRequest = mock()
        whenever(mockRequest.resources).thenReturn(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))

        testee.onPermissionRequest(mockRequest)
        // Should not throw
    }

    // ===== Fullscreen Security: Callback Behavior =====

    @Test
    fun whenCallbackProvidedAndCustomViewAlreadySetThenCallbackFiredImmediately() {
        val firstCallback: WebChromeClient.CustomViewCallback = mock()
        val secondCallback: WebChromeClient.CustomViewCallback = mock()
        testee.onShowCustomView(fakeView, firstCallback)
        testee.onShowCustomView(fakeView, secondCallback)
        verify(firstCallback, never()).onCustomViewHidden()
        verify(secondCallback).onCustomViewHidden()
    }

    @Test
    fun whenMultipleCallbacksProvidedInRapidSuccessionThenOnlyFirstAccepted() {
        val callbacks = (1..5).map { mock<WebChromeClient.CustomViewCallback>() }
        val views = (1..5).map { View(getInstrumentation().targetContext) }

        testee.onShowCustomView(views[0], callbacks[0])
        for (i in 1..4) {
            testee.onShowCustomView(views[i], callbacks[i])
        }

        verify(callbacks[0], never()).onCustomViewHidden()
        for (i in 1..4) {
            verify(callbacks[i]).onCustomViewHidden()
        }
        verify(mockWebViewClientListener, times(1)).goFullScreen(views[0])
    }

    @Test
    fun whenNullCallbackDuplicateEntryThenNoExceptionThrown() {
        testee.onShowCustomView(fakeView, null)
        testee.onShowCustomView(fakeView, null)
        // Second call with null callback should not crash
        verify(mockWebViewClientListener, times(1)).goFullScreen(fakeView)
    }

    // ===== Fullscreen Security: State Consistency =====

    @Test
    fun whenShowThenHideThenShowAgainThenBothShowsAllowed() {
        val view1 = View(getInstrumentation().targetContext)
        val view2 = View(getInstrumentation().targetContext)

        testee.onShowCustomView(view1, null)
        verify(mockWebViewClientListener).goFullScreen(view1)

        testee.onHideCustomView()
        verify(mockWebViewClientListener).exitFullScreen()

        testee.onShowCustomView(view2, null)
        verify(mockWebViewClientListener).goFullScreen(view2)
    }

    @Test
    fun whenShowThenHideThenShowSameViewAgainThenAllowed() {
        testee.onShowCustomView(fakeView, null)
        testee.onHideCustomView()
        testee.onShowCustomView(fakeView, null)
        verify(mockWebViewClientListener, times(2)).goFullScreen(fakeView)
    }

    @Test
    fun whenHideCalledMultipleTimesWithoutShowThenExitCalledEachTime() {
        testee.onHideCustomView()
        testee.onHideCustomView()
        testee.onHideCustomView()
        verify(mockWebViewClientListener, times(3)).exitFullScreen()
    }

    // ===== Fullscreen Security: Listener Lifecycle =====

    @Test
    fun whenListenerSetToNullAfterShowThenHideDoesNotCallExitFullScreen() {
        testee.onShowCustomView(fakeView, null)
        verify(mockWebViewClientListener).goFullScreen(fakeView)

        testee.webViewClientListener = null
        testee.onHideCustomView()
        // exitFullScreen was never called because listener is null
        verify(mockWebViewClientListener, never()).exitFullScreen()
    }

    @Test
    fun whenListenerReplacedBetweenShowAndHideThenNewListenerReceivesExit() {
        testee.onShowCustomView(fakeView, null)
        verify(mockWebViewClientListener).goFullScreen(fakeView)

        val newListener: WebViewClientListener = mock()
        testee.webViewClientListener = newListener
        testee.onHideCustomView()

        verify(mockWebViewClientListener, never()).exitFullScreen()
        verify(newListener).exitFullScreen()
    }

    @Test
    fun whenListenerReplacedDuringFullScreenThenNewListenerUsedForDuplicateRejection() {
        testee.onShowCustomView(fakeView, null)

        val newListener: WebViewClientListener = mock()
        testee.webViewClientListener = newListener

        val mockCallback: WebChromeClient.CustomViewCallback = mock()
        testee.onShowCustomView(fakeView, mockCallback)

        // Duplicate should still be rejected even with new listener
        verify(mockCallback).onCustomViewHidden()
        verify(newListener, never()).goFullScreen(any())
    }

    // ===== Fullscreen Security: Default Video Poster =====

    @Test
    fun whenDefaultVideoPosterRequestedMultipleTimesThenAlwaysReturnsBitmap() = runTest {
        val bitmap1 = testee.defaultVideoPoster
        val bitmap2 = testee.defaultVideoPoster
        assertEquals(1, bitmap1.width)
        assertEquals(1, bitmap1.height)
        assertEquals(1, bitmap2.width)
        assertEquals(1, bitmap2.height)
    }

    // ===== Fullscreen Security: Geolocation Permission =====

    @Test
    fun whenGeolocationPermissionRequestedThenDelegatedToOnPermissionRequest() = runTest {
        val mockCallback: android.webkit.GeolocationPermissions.Callback = mock()
        whenever(mockWebViewClientListener.getCurrentTabId()).thenReturn("id")

        testee.onGeolocationPermissionsShowPrompt("https://example.com", mockCallback)

        // The geolocation request is wrapped in a LocationPermissionRequest and delegated
        // to onPermissionRequest, which calls getSitePermissions
        verify(mockSitePermissionsManager).getSitePermissions(eq("id"), any())
    }

    @Test
    fun whenGeolocationPermissionRequestedWithNullListenerThenNoException() {
        testee.webViewClientListener = null
        val mockCallback: android.webkit.GeolocationPermissions.Callback = mock()
        testee.onGeolocationPermissionsShowPrompt("https://example.com", mockCallback)
        // Should not throw
    }

    private val mockMsg = Message().apply {
        target = mock()
        obj = mock<WebView.WebViewTransport>()
    }

    private class TestWebView(context: Context) : WebView(context) {
        override fun getUrl(): String {
            return "https://example.com"
        }

        override fun getProgress(): Int {
            return 20
        }
    }
}

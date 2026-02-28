/*
 * Copyright (c) 2020 DuckDuckGo
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

package com.duckduckgo.app.browser.logindetection

import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class LoginDetectionJavascriptInterfaceTest {

    private val validSecret = "test-secret"

    @Test
    fun whenLoginDetectedWithValidSecretThenNotifyCallback() {
        val loginDetected = mock<() -> Unit>()
        val loginDetectionInterface = LoginDetectionJavascriptInterface(validSecret, loginDetected)

        loginDetectionInterface.loginDetected(validSecret)

        verify(loginDetected).invoke()
    }

    @Test
    fun whenLoginDetectedWithInvalidSecretThenDoNotNotifyCallback() {
        val loginDetected = mock<() -> Unit>()
        val loginDetectionInterface = LoginDetectionJavascriptInterface(validSecret, loginDetected)

        loginDetectionInterface.loginDetected("wrong-secret")

        verify(loginDetected, never()).invoke()
    }

    @Test
    fun whenLoginDetectedWithEmptySecretThenDoNotNotifyCallback() {
        val loginDetected = mock<() -> Unit>()
        val loginDetectionInterface = LoginDetectionJavascriptInterface(validSecret, loginDetected)

        loginDetectionInterface.loginDetected("")

        verify(loginDetected, never()).invoke()
    }
}

/*
 * Copyright (c) 2022 DuckDuckGo
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
import com.duckduckgo.autoconsent.api.AutoconsentCallback
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@RunWith(AndroidJUnit4::class)
class AutoconsentInterfaceTest {
    private val mockWebView: WebView = mock()
    private val mockAutoconsentCallback: AutoconsentCallback = mock()
    private val pluginPoint = FakePluginPoint()
    private val validSecret = "test-secret"

    lateinit var autoconsentInterface: AutoconsentInterface

    @Before
    fun setup() {
        autoconsentInterface = AutoconsentInterface(pluginPoint, mockWebView, mockAutoconsentCallback, validSecret)
    }

    @Test
    fun whenMessagedParsedIfTypeMatchesThenCallProcess() {
        val message = """{"type":"fake"}"""

        autoconsentInterface.process(message, validSecret)

        assertEquals(1, pluginPoint.plugin.count)
    }

    @Test
    fun whenMessagedParsedIfTypeDoesNotMatchThenDoNotCallProcess() {
        val message = """{"type":"noMatchingType"}"""

        autoconsentInterface.process(message, validSecret)

        assertEquals(0, pluginPoint.plugin.count)
    }

    @Test
    fun whenSecretIsInvalidThenDoNotCallProcess() {
        val message = """{"type":"fake"}"""

        autoconsentInterface.process(message, "wrong-secret")

        assertEquals(0, pluginPoint.plugin.count)
    }

    @Test
    fun whenSecretIsEmptyThenDoNotCallProcess() {
        val message = """{"type":"fake"}"""

        autoconsentInterface.process(message, "")

        assertEquals(0, pluginPoint.plugin.count)
    }
}

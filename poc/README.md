# JS-Bridge Attack PoC — DuckDuckGo Android

This directory contains self-hostable Proof-of-Concept pages for the cross-origin JS bridge
vulnerabilities documented in [`SECURITY_FINDINGS.md`](../SECURITY_FINDINGS.md).

---

## PoC File Index

| File | Vulnerability | Works on patched build? |
|------|--------------|------------------------|
| `victim.html` + `attacker.html` | #1 AutoconsentInterface — UXSS | ❌ Code already patched |
| `login-detection-poc.html` | #2 LoginDetectionJavascriptInterface — Login State Spoofing | ✅ Yes — fires the real dialog |
| `login-detection-attacker.html` | #2 cross-origin iframe illustration | ❌ Cross-origin blocked by fix |

---

## Why the Autoconsent PoC Did Not Work

### Short answer
**The vulnerability is already fixed in this codebase.** The PoC calls the wrong method signature.

### Full explanation

The original attacker iframe called:
```javascript
AutoconsentAndroid.process(initPayload)   // one argument
```

The **vulnerable (pre-fix)** method signature was:
```kotlin
@JavascriptInterface
fun process(message: String)        // ← one arg, no secret
```

The **current (fixed)** method signature is:
```kotlin
@JavascriptInterface
fun process(message: String, secret: String)   // ← two args, secret required
```

Android WebView's JS bridge resolves methods by **exact name + argument count**. When the
cross-origin iframe calls `AutoconsentAndroid.process(initPayload)` with **one** argument,
the bridge searches for a `@JavascriptInterface` method named `process` that accepts exactly
one `String`. That method no longer exists — the call is **silently dropped**. No UXSS fires.

Even if you call `AutoconsentAndroid.process(initPayload, '')` with two args, the second arg
(an empty string) does not match the per-session UUID held server-side, and the method returns
immediately after the secret check.

### The secondary error you saw

```
failed to read a named window: blocked a frame with origin https://mygithub.github.io
from accessing a cross-origin frame
```

The old `attacker.html` also contained a **diagnostic step** that tried to read
`window.top.autoconsentAndroidSecret` from the cross-origin iframe — intentionally expecting
this to fail, to prove the secret is SOP-protected. The error is correct and *expected*.
It has been removed from `attacker.html` to avoid confusion.

### Summary of why the PoC failed

| Failure | Cause |
|---------|-------|
| **No UXSS fired** | Code is already patched — one-arg `process()` no longer exists |
| **SOP error in log** | Old diagnostic step tried to read `window.top.*` (now removed) |
| **Empty-secret call** | Even two-arg call with `''` is rejected by the secret check |

---

## PoC #2 — LoginDetection (Works on the Current Build)

### How it works

DDG's `JsLoginDetector` injects JavaScript into **every page** it loads, using
`webView.evaluateJavascript()` triggered from `WebViewClient.onPageStarted`. The injected code
sets a global variable in the main frame:

```javascript
window.loginDetectionSecret = '<per-session-UUID>';
```

This variable is then used by the legitimate login detection helpers on the page. Any JavaScript
running in the **main frame** can read `window.loginDetectionSecret` directly. The PoC does exactly this:

```javascript
// 1. Poll until DDG injects the secret
function checkSecret() {
  var s = window.loginDetectionSecret;
  if (s) {
    // 2. Call loginDetected with the real secret — triggers the DDG dialog
    LoginDetection.loginDetected(s);
  }
}
setInterval(checkSecret, 50);
```

Because the secret check in `LoginDetectionJavascriptInterface.loginDetected(secret)` compares
`this.secret == secret` and both values are the same per-session UUID, the check **passes** and
DDG fires the "Fireproof this site?" dialog for the current page's URL.

### Attack scenario

1. User visits an attacker-controlled page (phishing site, malicious ad landing page, etc.)
   in the DuckDuckGo Android browser.
2. DDG injects `window.loginDetectionSecret` into the page's JS context (this happens
   unconditionally for every page load).
3. The attacker's page reads `window.loginDetectionSecret` and immediately calls
   `LoginDetection.loginDetected(secret)`.
4. DDG shows "Fireproof this site?" — the user, trusting DDG, clicks yes.
5. The malicious site's cookies now survive DDG's "fire" clearing, giving it persistent tracking.

### Prerequisites

- Open `login-detection-poc.html` in the **DuckDuckGo Android browser** (not a desktop browser —
  the `LoginDetection` JS bridge is only registered by the app).
- DDG Settings → Privacy → "Automatically fireproof sites after login" must be set to
  **Ask every time** or **Always** (not *Never*). The default is *Ask every time*.

### Running the PoC

No cross-origin server setup required. Just serve the file and open it in DDG Android:

```bash
# From the poc/ directory:
python3 -m http.server 8080
# Then open http://YOUR_DEVICE_IP:8080/login-detection-poc.html in DDG Android
```

Or host it on GitHub Pages and open the URL in DDG Android.

### What to expect

Within 1–2 seconds of the page loading:

1. The evidence log shows: `✅ window.loginDetectionSecret injected by DDG: "xxxxxxxx…"`
2. Immediately after: `🚨 LoginDetection.loginDetected(secret) called successfully!`
3. DDG displays the native **"Fireproof this site?"** dialog for the page's URL.

The dialog appears **without any form submission, without typing a password**, purely from the
page's JavaScript reading the injected secret and calling the interface.

### Why the cross-origin iframe attack is blocked (the fix is effective for iframes)

The fix works correctly for iframes because:

- `webView.evaluateJavascript()` always executes in the **main frame** context.
- Android's `WebViewClient.onPageStarted` fires only for **main frame** navigations, not
  for subframe/iframe loads.
- Therefore `window.loginDetectionSecret` is **only** set in the main frame's JS context.
- Cross-origin iframes have their own separate JS context and cannot read the parent frame's
  `window.loginDetectionSecret` due to the Same-Origin Policy.
- A cross-origin iframe calling `LoginDetection.loginDetected('')` will always fail the
  secret check.

The **main-frame scenario** remains: any page loaded directly by the user can read its own
`window.loginDetectionSecret` and fire the dialog. This is the residual risk after the fix.

---

## PoC #1 — AutoconsentInterface UXSS (Pre-Fix Only)

This PoC is preserved for historical documentation. It demonstrates what the attack would have
done on a **vulnerable (pre-fix) build**. It does not work against the current codebase because
the secret-based fix is already applied.

### Setup (two-server cross-origin, for educational purposes)

You need two separate origins to make the iframe genuinely cross-origin:

```bash
# Terminal 1 — victim server (port 8080)
cd /path/to/poc/
python3 -m http.server 8080

# Terminal 2 — attacker server (port 8081)
cd /path/to/poc/
python3 -m http.server 8081
```

Then edit `victim.html` and set the iframe `src` to `http://localhost:8081/attacker.html`.

Open `http://localhost:8080/victim.html` in DDG Android. On a **pre-fix** build:
- The attacker iframe calls `AutoconsentAndroid.process(initMessage)` (one arg).
- DDG's `InitMessageHandlerPlugin` processes it and calls `webView.evaluateJavascript()`
  in the **main frame's** JS context.
- The `Object.defineProperty` interceptor in `victim.html` detects the injection and reads
  `document.cookie`, `localStorage`, and other sensitive data — proving UXSS.

On the **current (fixed) build**, the one-arg `process()` call has no matching method and is
silently dropped.


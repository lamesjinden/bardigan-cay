---
name: verify
description: Build, launch, and browser-test Bardigan Cay to verify client changes end-to-end. Use for BC UI and client/server integration checks; ordinary Clojure authoring and unit-test-only work do not require this workflow.
---

# Verify Bardigan Cay client changes

Adapted from this repository's `.claude/skills/verify/SKILL.md`.
Run commands from the repository root. Verify the affected behavior in the
running app and report observable results, including any checks that could
not be completed. Select the flows relevant to the change; the list below
is not a requirement to repeat every flow for every edit.

## Build and launch

The toolchain needs Babashka, Clojure, the JDK, Node.js 22+, and installed
npm dependencies. The repository's `nix develop` shell supplies the language
toolchain when it is missing from the current shell.

Always use a copy of `bedrock/`: saves, moves, and reorders write Markdown
files. Port 4545 is usually James's own dev server; use a different available
port, such as 4599. Keep browser debugging on a separate available port.

```bash
BC_VERIFY_SCRATCH=$(mktemp -d /tmp/bc-verify.XXXXXX)
cp -R bedrock "$BC_VERIFY_SCRATCH/bedrock-copy"
bb build-dev-client
```

`bb build-dev-client` runs `shadow-cljs compile app`, writing client assets
to `resources/public/js` (see `shadow-cljs.edn`). It also prepares Mermaid
assets for export. These build outputs belong to the current checkout.

Start the server in a managed execution session or background process:

```bash
bb run-dev-server --port 4599 \
  --directory "$BC_VERIFY_SCRATCH/bedrock-copy" \
  --config "$BC_VERIFY_SCRATCH/bedrock-copy/system/config.edn"
```

Shell variables do not persist across separate execution sessions. Carry the
scratch path explicitly into subsequent commands. Record the session or
process IDs for teardown. Poll `http://127.0.0.1:4599/api/init` with a bounded
timeout; successful JSON indicates readiness. On failure, inspect server
output before attempting browser flows.

## Drive headless Chrome through CDP

Launch Chrome with a fresh profile so localStorage and editor state are
isolated from the user's browser:

```bash
/usr/bin/google-chrome --headless=new \
  --remote-debugging-address=127.0.0.1 \
  --remote-debugging-port=9333 \
  --user-data-dir="$BC_VERIFY_SCRATCH/profile" \
  --no-first-run about:blank
```

Keep the browser in a managed session or record its PID. Node.js 22+ provides
global `WebSocket`: get the page target's `webSocketDebuggerUrl` from
`http://127.0.0.1:9333/json` and connect to it. Create a scratch `cdp.mjs`
driver if needed; no driver is bundled with this skill. Useful helpers send
commands with unique IDs, match responses, evaluate expressions, poll DOM
conditions with timeouts, and collect runtime errors and network events.

Enable `Page`, `Runtime`, and `Network`, then navigate to the test server.
Wait for app content, not just the document load event: initialization and
lazy modules are asynchronous.

### Project-specific interaction details

- **React controlled inputs:** use the native `HTMLInputElement.prototype`
  value setter and dispatch a bubbling `input` event. Setting `.value` alone
  does not reach Reagent's change handler.
- **Icon buttons:** find icons within `button span`, for example
  `[...document.querySelectorAll('button span')].find(s => s.textContent.trim() === 'edit')`,
  then click the owning button. A bare `span` query can match the wrapper
  `span.button-container`, whose click does nothing.
- **Lazy Ace editor:** after clicking edit, poll for `.ace_editor`. Drive it
  through `document.querySelector('.ace_editor').env.editor`, using
  `setValue` and `focus`.
- **Save shortcuts:** use CDP `Input.dispatchKeyEvent` for key-down and key-up,
  with key `s`, code `KeyS`, and `windowsVirtualKeyCode: 83`. Ctrl+S uses
  `modifiers: 2`; Ctrl+Shift+S uses `modifiers: 10`. Synthetic JavaScript
  keyboard events lack the keyCode the handlers expect.
- **Cancellation checks:** when relevant, add about 500 ms latency through
  `Network.emulateNetworkConditions` so rapid actions overlap. Correlate
  `requestWillBeSent`, `loadingFailed` with `canceled: true`, and
  `loadingFinished` by request ID. Aborting a client request does not prove
  a server mutation was canceled; reorders may still execute server-side.

## Flows and evidence

- **Startup:** load `/`; confirm the configured start page (normally
  HelloWorld) renders and client process wiring produces no startup errors.
- **Autocomplete:** type at least three characters into `.nav-input-text`;
  allow for its 300 ms debounce and wait for `.autocomplete-dropdown`.
- **Search:** enter a nonblank query and click the `search` icon button;
  confirm results prepend to the transcript view.
- **Reorder:** open `/pages/CardTypes`, reveal the card actions as needed,
  and click `expand_more` or `expand_less` inside `.actions-container`.
  Confirm the new order in both the UI and the scratch Markdown file.
- **Save:** edit through Ace and use Ctrl+S (silent save) or Ctrl+Shift+S
  (save and reload). Confirm the expected content in the scratch page file;
  a successful key dispatch alone is not evidence of persistence.

Selectors and timings above describe the existing UI. Check the relevant
view code if they have changed. Capture useful console errors, failed
requests, and screenshots when diagnosing failures. Run relevant existing
tests (`bb test-client`, `bb test-server`, or `bb test`) when the change calls
for them; browser verification complements those tests.

## Teardown and report

Close the CDP connection and terminate only the Chrome and server sessions
or processes started for this run. Avoid broad `pkill` patterns that could
stop another verification run or the user's processes. Preserve scratch
logs or screenshots when they explain a failure.

Report the flows exercised, their outcomes, and any remaining limitations.
If the toolchain, build, server, or browser could not run, state the precise
blocker and distinguish source inspection from executed verification.

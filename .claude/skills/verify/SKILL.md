---
name: verify
description: Build, launch, and drive the BC wiki app headlessly to verify client changes end-to-end
---

# Verifying BC client changes end-to-end

## Build + launch

```bash
bb build-dev-client                       # shadow-cljs compile app -> resources/out
bb run-dev-server --port 4599 --directory "$SCRATCH/bedrock-copy"   # background
```

- Port 4545 is usually occupied by James's own dev server — always pick another port.
- Always run against a COPY of `bedrock/` (cp -r to scratch) — saves/reorders mutate the page files on disk.
- Server is up when `curl localhost:<port>/api/init` returns JSON.

## Drive headless Chrome via CDP

`/usr/bin/google-chrome --headless=new --remote-debugging-port=9333 --user-data-dir=$SCRATCH/profile --no-first-run about:blank`

Node >= 22 has global WebSocket; connect to the page target from `http://127.0.0.1:9333/json`. A reusable driver (`cdp.mjs` with send/eval/networkLog helpers) worked well; recreate it if not in scratch.

Gotchas that cost time:
- **React controlled inputs**: set value via the native `HTMLInputElement` value setter + dispatch `input` (bubbles) — plain `.value =` does not reach reagent's `:on-change`.
- **Icon buttons**: select with `[...document.querySelectorAll('button span')].find(s => s.textContent === 'edit')` — a bare `span` query matches the *wrapper* `span.button-container` (same textContent) whose `.click()` does nothing.
- **Editor is lazy-loaded** (`suspended-editor-component`): after clicking `edit`, poll for `.ace_editor` for several seconds. Drive Ace via `document.querySelector('.ace_editor').env.editor` (`setValue`, `focus`).
- **Ctrl+S / Ctrl+Shift+S**: use CDP `Input.dispatchKeyEvent` (`modifiers: 2` / `10`, `windowsVirtualKeyCode: 83`) — synthetic KeyboardEvent lacks `keyCode`.
- **To observe request cancellation** (switchMap processes): `Network.emulateNetworkConditions` with ~500ms latency so rapid UI actions overlap in flight; correlate `Network.requestWillBeSent` with `loadingFailed(canceled: true)` / `loadingFinished`.

## Flows worth driving

- Load `/` — proves `client.cljs` process wiring didn't break startup (title "Bardigan Cay", HelloWorld renders).
- Autocomplete: type >= 3 chars into `.nav-input-text`, wait past the 300ms debounce; suggestions render in `.autocomplete-dropdown`.
- Search: click the `search` icon button; results prepend to the transcript view.
- Reorder: on `/pages/CardTypes`, click `expand_more`/`expand_less` divs in `.actions-container`.
- Save: `edit` button -> Ace -> Ctrl+S (silent) or Ctrl+Shift+S (reload); verify the page file in the bedrock copy.

## Teardown

Kill Chrome (`pkill -f remote-debugging-port=9333`) and the server task; scratch bedrock copy is disposable.

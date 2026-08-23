# Material Symbols font subset

The vendored font at
`resources/public/css/vendor/material-symbols/material-symbols-sharp.woff2` is
the **complete** Material Symbols Sharp variable font from the
`material-symbols` npm package (~3.5MB), so dev builds serve every icon.

Release builds do not ship it: during the uberjar build,
`build/inline.clj` runs `subset_icons.clj` against the `target/classes` copy,
cutting it to only the icons listed in `icons.txt` (~22KB) before the font is
base64-inlined into `index.html`. The standalone font files and `main.css` are
then deleted from the jar, so the packaged app serves the subset font only via
the inlined data: URI.

**When adding a new icon to the UI, add it to `icons.txt` — otherwise it
renders correctly in dev but as raw ligature text in release builds.**

`icons.txt` covers every icon name used in `src/client` (including
dynamically-selected names: job statuses in `app_menu.cljs`, theme submenu
icons, layout toggles) plus bedrock content (`WorkspaceExample.md`).

## How subsetting works

`subset_icons.clj` is a babashka script. It is ligature-aware: plain
text-based subsetting cannot work for this font because every icon name is
spelled from the same letter glyphs, so glyph closure would pull in the entire
icon set. Instead the script parses the font's cmap and GSUB tables itself,
resolves each name in `icons.txt` to its ligature glyph id, and subsets with
exactly those gids and layout closure disabled, then verifies every requested
icon still resolves in the output (the build fails if one doesn't). It
intentionally keeps the variable-font axes (FILL, wght, GRAD, opsz) so
`font-variation-settings` still works.

The mechanical woff2 decompress/compress and gid-level subsetting run in Node
(`font_tool.mjs`) using the wasm npm packages `harfbuzzjs` and `wawoff2` from
devDependencies — no toolchain additions beyond the Node the client build
already needs.

To run the subsetter standalone (e.g. to inspect the output):

```sh
bb build/font-subset/subset_icons.clj \
  node_modules/material-symbols/material-symbols-sharp.woff2 \
  /tmp/subset.woff2 \
  build/font-subset/icons.txt
```

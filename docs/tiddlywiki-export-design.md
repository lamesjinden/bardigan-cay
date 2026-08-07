# Export to TiddlyWiki — Design

Status: agreed 2026-08-06. Replaces the legacy static-HTML export outright.

## Goal

"Export" produces a single, self-contained TiddlyWiki HTML file containing the
whole wiki. The artifact is a live TiddlyWiki instance: searchable, editable,
re-saveable by the recipient. Export is one-way; BardiganCay never re-imports.

## Decisions

1. **Granularity: one tiddler per BC page.** Cards are converted and
   concatenated in page order. Transcluded cards are inlined at export time
   (snapshot semantics). Asset tiddlers (SVG, images) may exist alongside page
   tiddlers; the one-per-page rule applies to pages, not assets.
2. **Dynamic cards: freeze now, plugin later.** Workspace/graph cards export
   as source in a code block plus any captured output. A future BC-companion
   TW plugin (Scittle inside TiddlyWiki) may make workspaces live in the
   export; it is explicitly out of scope for v1.
3. **One-way, but source is preserved.** Each page tiddler carries the
   original page markdown in a `bc-source` field for debugging.
4. **Global export only.** Per-page export is removed (endpoint + toolbar UI).
5. **WikiText, not the Markdown plugin.** Card content is converted
   markdown → WikiText at export time.
6. **Self-contained single file.** Media is embedded as base64 tiddlers.
   The deliverable is one `.html` file — no zip, no `media/` folder.
7. **No Node.js at runtime.** The base TW file (`empty.html`) is pre-built
   once offline and vendored under `resources/`.
8. **Legacy export deleted**, including its config surface (see below).

## Architecture

New namespace `clj-ts.export.tiddlywiki` (server side):

1. Load all pages (excluding synthetic `AllPages`/`AllLinks`/`BrokenLinks`/
   `OrphanPages` — TiddlyWiki derives these natively via filters).
2. For each page: parse cards, run `card->tiddler-content` per card (see
   contract), join, emit a tiddler map.
3. Collect asset tiddlers (media files, generated SVG).
4. Add wiki-level tiddlers: `$:/SiteTitle` (wiki name), `$:/DefaultTiddlers`
   (start page).
5. Serialize all tiddlers as JSON and splice into the vendored `empty.html`
   store area (`<script class="tiddlywiki-tiddler-store"
   type="application/json">`, TW ≥ 5.2 format).
6. Respond with the finished HTML file from `/api/exportallpages`.

### Tiddler shape (per page)

| field | value |
|---|---|
| `title` | BC page name |
| `text` | converted WikiText |
| `type` | `text/vnd.tiddlywiki` |
| `modified` | page file mtime |
| `bc-source` | original page markdown, verbatim |

### Vendored base file

`resources/tiddlywiki/empty.html`: TiddlyWiki core only (WikiText means no
Markdown plugin needed). Built once with the Node tooling outside the
repo build, checked in. Record the TW version in a sibling `VERSION` note.
Refreshing it is a manual, deliberate act.

## Markdown → WikiText conversion

Implement over the commonmark-java AST (already a dependency, already used in
`parsing.clj`) — an AST renderer, not regex rewriting. BC dialect quirks are
handled before/around the AST pass.

| construct | markdown | WikiText |
|---|---|---|
| heading | `#`…`######` | `!`…`!!!!!!` |
| bold | `**x**` | `''x''` |
| italic | `*x*` / `_x_` | `//x//` |
| strikethrough | `~~x~~` | `~~x~~` |
| inline code | `` `x` `` | `` `x` `` |
| code fence | ` ``` ` | ` ``` ` |
| unordered list | `-` / `*`, nested by indent | `*`, nested by repetition (`**`) |
| ordered list | `1.` | `#` |
| blockquote | `>` | `<<<` … `<<<` |
| external link | `[text](url)` | `[[text\|url]]` |
| image | `![alt](url)` | `[img[url]]` |
| pipe table (GFM) | `\|a\|b\|` | `\|a\|b\|` (+ `\|h` header row) |
| wiki link | `[[Page]]` | `[[Page]]` (unchanged) |
| **aliased wiki link** | `[[target\|display]]` | `[[display\|target]]` — **segments swap** |
| double-comma table (BC) | `a,,b` lines | TW pipe table |
| bare URL | plain text (auto-links disabled in live app) | TW auto-links natively — no conversion logic |

Auto-links: `common/auto-links` is already `#_`-disabled in both live render
paths; its only caller is the legacy exporter. Delete the function and its
test with the legacy export.

## Card contract

Single multimethod dispatching on `:source_type`, replacing the twin
`condp`s (`card->html` on `:source_type`, `card-specific-wrapper` on
`:render_type`) in the legacy exporter.

| source_type | export behavior |
|---|---|
| `:markdown` | convert source md → WikiText |
| `:manual-copy` | convert like markdown, wrapped `@@.manual-copy ... @@` |
| `:raw` | preformatted block of source |
| `:code` | code fence of source |
| `:evalraw` | server-eval; output in preformatted block |
| `:evalmd` | server-eval; output converted md → WikiText |
| `:bookmark` | packaged markdown → WikiText |
| `:patterning` | server-render SVG → `image/svg+xml` asset tiddler + `[img[...]]` |
| `:network` | same as patterning |
| `:embed` | keep generated embed HTML (WikiText passes HTML elements through); media images → base64 image tiddlers + `[img[...]]` |
| `:filelink` | embed target file as base64 tiddler where feasible; else plain external link |
| `:workspace` | **freeze**: source in code fence + note (plugin later) |
| `:graph` | **freeze**: source in code fence |
| `:deadline` | freeze: render current state statically |
| `:transclude` | inline the target page's cards (snapshot) |
| `:system` (backlinks etc.) | drop — TW derives backlinks/AllPages natively |
| unrecognized | preformatted block of source (never fail the export) |

Per-page failures are collected and reported, not fatal (retain the spirit of
`_export-failures.txt`, e.g. as an `ExportFailures` tiddler).

## Deletions

- `src/server/clj_ts/export/static_export.clj`,
  `src/server/clj_ts/export/page_exporter.clj` (IPageExporter protocol)
- `/api/exportpage` route + handler; per-page export link in `tool_bar.cljs`
- `system/export_resources/` template + CSS loading; Selmer dependency if
  nothing else uses it
- CLI/config options `--links`, `--extension`; repoint `--export-dir` or drop
- `export-recentchanges-rss` (already orphaned)
- `common/auto-links` + `auto-links-test`

## Phases

1. Delete legacy export + auto-links (standalone commit).
2. md → WikiText converter over commonmark AST, with golden tests
   (including double-comma tables and aliased-link swap).
3. `card->tiddler-content` multimethod + per-type tests.
4. Store assembly + `empty.html` splice; vendor the base file.
5. Endpoint + UI swap (`Export All` downloads the `.html`).
6. (Later) BC-companion TW plugin for live workspaces.

## Future considerations

- Export-time SCI evaluation (`:evalraw`, `:evalmd`, workspace output
  capture) is unbounded: a pathological card can burn CPU or hang the
  export. If this becomes a problem, the preference is to **remove
  export-time evaluation** (fall back to frozen source) rather than add
  timeout/thread-interrupt machinery.

## Risks / verify early

- Store-splice format against the vendored TW version (JSON store, TW ≥ 5.2).
- HTML pass-through quirks in WikiText for embed iframes.
- Single-file size with heavy media (base64 ≈ +33%); accepted trade-off.
- Page names are constrained by BC's own link regex (`[\w\s-:]`), so tiddler
  title collisions with `$:/` are not expected; assert at export time anyway.

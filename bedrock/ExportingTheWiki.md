Bardigan Cay is intended to be an individual authoring tool. (Or used by a group,  using something like the git version control system to share a collection of files in common).

When you want to share your wiki, or just carry it with you, you can export it as a single self-contained HTML file in [TiddlyWiki](https://tiddlywiki.com/) format.

To export, open the app menu (top right), expand **Export**, and choose **Export All**. The export runs as a background job; **Export Status** in the same submenu shows its progress and, once it finishes, offers the resulting file for download.

The download is one flat HTML file containing every page of the wiki. You can open it directly in a browser, host it on any static web server, or send it to someone — no Bardigan Cay server required. And because it is a genuine TiddlyWiki, the exported copy is itself browsable and editable with TiddlyWiki's own tools.

Each page becomes a tiddler, and the original Bardigan Cay source of the page is preserved in a `bc-source` field on that tiddler, so nothing is lost in translation.

Note that export downloads are kept in memory on the server and only a small number of recent exports are retained — an old download link may eventually report that it has expired. Just run the export again to get a fresh file.

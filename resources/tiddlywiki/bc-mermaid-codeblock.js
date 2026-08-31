/*\
Renders ```mermaid code fences as diagrams, in the manner of the official
Highlight plugin: the core codeblock widget gains a postRender that swaps
the <pre> for the rendered SVG. The diagram source stays in the tiddler
text as a normal code fence, so it remains searchable and editable; on a
parse failure the fence stays visible with an error style, mirroring the
live BardiganCay client.

Exported into TiddlyWiki by wiki.bc.export.tiddlywiki as a widget module
tiddler, alongside the mermaid library tiddler (the vendored
mermaid.min.js IIFE, which assigns globalThis.mermaid when required).

Note: the official Highlight plugin patches the same postRender hook, so
adding it to an exported wiki would leave only the later-loaded of the
two active.
\*/
(function () {
  "use strict";

  var CodeBlockWidget = require("$:/core/modules/widgets/codeblock.js").codeblock;

  var renderCounter = 0;

  CodeBlockWidget.prototype.postRender = function () {
    if (!$tw.browser || this.language !== "mermaid") {
      return;
    }
    var pre = this.domNodes[0];
    var source = this.getAttribute("code", "");
    var showFenceAsError = function () {
      pre.style.display = "";
      pre.className += " bc-mermaid-error";
    };
    require("$:/plugins/bc/mermaid/mermaid.js");
    var mermaid = globalThis.mermaid;
    if (!mermaid) {
      showFenceAsError();
      return;
    }
    var container = this.document.createElement("div");
    container.className = "bc-mermaid-diagram";
    pre.parentNode.insertBefore(container, pre);
    pre.style.display = "none";
    this.domNodes.push(container);
    mermaid.initialize({ startOnLoad: false, theme: "default" });
    var renderId = "bc-mermaid-" + (++renderCounter);
    mermaid.render(renderId, source).then(function (result) {
      container.innerHTML = result.svg;
    }).catch(function (_error) {
      // mermaid leaks its temp measuring element on parse failure
      var orphan = document.getElementById("d" + renderId);
      if (orphan) {
        orphan.remove();
      }
      // keep the (empty) container in place so the widget's DOM
      // bookkeeping stays intact for teardown
      container.style.display = "none";
      showFenceAsError();
    });
  };
})();

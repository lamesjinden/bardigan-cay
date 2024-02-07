An example embedded ClojureScript Workspace. It uses the Small Clojure Interpreter (<https://github.com/borkdude/sci>), running in the browser.

Note that the output of the code is expected to be either:
* a string
* [hiccup](https://github.com/weavejester/hiccup) surrounded by [:div :]
* a [reagent component](https://github.com/reagent-project/reagent/blob/master/doc/CreatingReagentComponents.md) (experimental; Form 3 untested)
  * see 'Advanced' example below

----
:workspace

;; Write some code
[:div 
[:ul {:style {:font-size "small"}}
(map (fn [x] [:li (str x ", " (reduce + (range x)))]) (range 10))
]]

----

### Workspace Configuration (experimental)

A Workspace card supports several configuration options affecting appearance and behavior.


Workspace cards are configured by adding a clojure map following (next line) the initial `:workspace` declaration. See the 'Advanced Workspace Example' below.


The following configuration options are supported:
* `:eval-on-load` - [true | false] - causes the workspace code to be evaluated immediately upon loading
* `:layout` - [:horizontal | :vertical] - causes the layout of the workspace sections to be vertically stacked or side-by-side
* `:code-visibility` [true | false] - causes the 'code' section of the workspace to be visible or hidden
* `:result-visibility` [true | false] - causes the 'result' section of the workspace to be visible or hidden
* `:editor-size` [:small | :medium | :large] - controls the default editor height (respectively: 25 lines, 50 lines, all lines)

----

### Evaluation Environment

In addition to the default SCI execution environment, the following functions are available to be called by workspace code:

* `js/*` - exposes the global javascript object to the execution environment
* `prn` - exposes cljs.core/prn to the execution environment
* `println` - exposes cljs.core/println to the execution environment
* `util/parse-boolean` - exposes cljs.core/parse-boolean to the execution environment
* `util/parse-double` - exposes cljs.core/parse-double to the execution environment
* `util/parse-long` - exposes cljs.core/parse-long to the execution environment
* `util/pad2` - stringifies the argument and pads the string to 2 places with '0'
* `util/pad3` - stringifies the argument and pads the string to 3 places with '0'
* `util/pad4` - stringifies the argument and pads the string to 4 places with '0'
* `util/round1` - rounds the argument to 1 decimal
* `util/round2` - rounds the argument to 2 decimals
* `util/round3` - rounds the argument to 3 decimals
* `util/set-inner-html` - accepts an (DOM) element and a value; sets innerHTML of the `element` to `value`
* `util/set-display` - accepts an (DOM) element and a 'display' value; sets the display style of the element to `display`
* `util/set-display-none` - accepts an (DOM) element; sets the display style of the element to 'none'
* `util/set-display-block` - accepts an (DOM) element; sets the display style of the element to 'block'
* `util/get-element-by-id` - performs an element query using the provided argument id (do not prefix with '#' as this will be done for you) and returns the result; note - the query is scoped to the container element for the Workspace.
* `cb/update-card` - (experimental) - accepts a clojure map where keys represent the names of symbols within the containing workspace; for each pair in the map parameter, attempts to locate a top-level symbol named by the pair; if found, replaces the symbol's value provided by the pair. An optional keyword-arg, :with-eval, can be provided. When `true` (default), the updated workspace code will be evaluated; when `false` the updated workspace code is not evaluated.

----

### Publishing Code

A new, experimental feature of Cardigan Bay is that workspaces **are** now executable when exported to static HTML pages.

This is an exciting new feature. It means that we can add dynamic in-page calculations to public facing wikis and digital gardens.

The technology works (using SCI again, via [Scittle](https://github.com/babashka/scittle)) but presentation is still basic.

In the above Workspace example, we wrote code that output hiccup format which was automatically rendered as HTML. 

In an *exported* Workspace, hiccup isn't available. So if you want to write a program that renders correctly, you'll need it to produce HTML itself (unless plain text is sufficient)

Look at the example in the Workspace below. Note that if you run it in a live Cardigan Bay, the tags won't render correctly. But in an exported page they will.


----
:workspace

;; Code that renders as expected in exported pages.

(defn li [x] (str "<li>" x "</li>"))

(str
(apply str
  "<h3>Some numbers</h3>"
  (map li (range 20))
)
  "Total:" (reduce + (range 20))
)

----

To test this feature and see what exported workspaces look like, simply hit the Export button for this page and see the exported html file (probably at bedrock/exported/WorkspaceExample )

----

### Simplifying Published Code

You'll notice when you look at the above example, that all the exported code can be seen and edited in the textarea of the published page.

That's convenient if you want to give your readers access to the whole thing. But it can also be confusing. Especially for inexperienced readers.

Sometimes you have an algorithm or model you want to make available to people, without them having to look at, or think about, the whole thing.

You just want to allow them to put in some parameters and see how the algorithm works out or the model behaves.

For this we can separate the code into two sections : private and public.

This is done by placing a single comment in the code with four semi-colons and the word PUBLIC, as in 

;;;;PUBLIC

Clojure ignores this line, as it's justs a comment. But the exporter treats it as a separator : all the code *before* this line is considered **private** while all code after it is **public**. In the exported version of the page, the private code will be hidden, and can encapsulate as much complexity as you like. While the public code can by used to present a clean API / interface to the user, which is easily understood and changed.

See the following example in the exported page to understand how it works


----
:workspace

 
(defn tag [t s] (str "<" t ">" s "</" t ">"))

(defn ul [s] (tag "ul" s))
(defn li [s] (tag "li" s))
(defn div [s] (tag "div" s))

(defn total [xs] (reduce + xs))

(defn list-and-total [title xs]
  (str
     (tag "h3" title)
     (ul 
       (apply str (map li xs)) 
     )
     "Total:" (total xs)
  )
)

;;;;PUBLIC

(list-and-total "A List of Numbers" (range 20))

----

### Advanced Workspace Example

see below

----
:workspace

{:result-visibility true
 :eval-on-load true
 :layout :horizontal
 :editor-size :large}

(def x 1)
(def y "hello")
(def target-symbol nil)
(def target-value nil)

(fn []
  (let [input-replacement-symbol (r/atom target-symbol)
        input-replacement-value (r/atom target-value)]
    (fn []
      [:div
       [:div
        [:input {:type "text"
                 :value @input-replacement-symbol
                 :placeholder "replacement symbol"
                 :on-change (util/bind-ratom input-replacement-symbol)}]
        [:span (str "\"" @input-replacement-symbol "\"")]]
       [:div
        [:input {:type "text"
                 :value @input-replacement-value
                 :placeholder "replacement value"
                 :on-change (util/bind-ratom input-replacement-value)}]
        [:span @input-replacement-value]]
       [:button.big-btn.material-symbols-sharp
        {:on-click (fn []
                     (when-let [replacement-symbol @input-replacement-symbol]
                       (let [replacement-value (inc (if (number? @input-replacement-value)
                                                      @input-replacement-value
                                                      (util/parse-long @input-replacement-value)))]
                         (cb/update-card {"y" "static value"
                                          'target-symbol @input-replacement-symbol
                                          'target-value replacement-value
                                          replacement-symbol replacement-value}))))}
        "play_arrow"]])))

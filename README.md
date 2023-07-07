# (a fork of) [CardiganBay](https://github.com/interstar/cardigan-bay)

![How it looks](https://github-production-user-asset-6210df.s3.amazonaws.com/7298563/251646162-117b5389-a9d5-4621-8b0f-d055a2578bf0.png)

![How it looks](https://github-production-user-asset-6210df.s3.amazonaws.com/7298563/251645715-0c2951b3-77fc-4646-a87c-ec94cc9bbb59.png)

## Deviations from Upstream

### Usability

* Upstream release 0.7.3 transitioned to providing a 'paste bar' for accessing card templates. This change is not (yet) reflected here.
* Styling changes across the application
* Replaced default editor with [Ace](https://ace.c9.io/)
* Search is case-insensitive
* Included [highlight.js](https://highlightjs.org/) themes for `clojure` and `bash`
* Added dark mode option
* Replace forward/back functionality with browser-native `push-state`/`pop-state` navigation
* Exports are compressed (Zip) and downloadable
* Card-level editing (aka single-card editing) is accessed via double-clicking on the target card
* Workspace card editor is configured in `clojure` mode with syntax highlighting
* Added [cljfmt](https://github.com/weavejester/cljfmt) formatting to Workspace cards
* Added Expand-All / Collapse-All cards functionality 
* Added keyboard shortcuts when editing page content

### Development

* Project dependencies (additions, subtractions, updates)
* Restructure project configuration (aliases) within deps.edn
* Use [Babaska](https://book.babashka.org/#tasks) task runner to invoke dev scripts
* Restructure src layout (client and server applications)
* API endpoints (additions, subtractions, updates)
* Represent async client operations using Promesa promises

## Getting Started (as Developer)

Make sure you have the [JDK](https://openjdk.org/install/), [Clojure](https://clojure.org/guides/install_clojure), and [Babashka](https://github.com/babashka/babashka#installation) installed.

Then

1. clone this repository
```bash
git clone https://github.com/lamesjinden/cardigan-bay.git cb
```
2. change directory into the repository
```bash
cd cb
```
3. From here, the simplest way to get up and running:
```bash
bb run-minimal
```

Then navigate to [http://localhost:4545/](http://localhost:4545/) in your browser.

You should be running your wiki. By default CardiganBay starts on port 4545 and looks in the local `bedrock` directory for its pages. You'll find several pages with the beginnings of some documentation and examples there.

To change server settings during development, modify the parameters provided to the `run-dev-server` task in `bb.edn`.

### Advanced Development

Depending on the desired workflow, choose between:

#### Option 1: `run-minimal`
  * suitable for higher-level server development; not suitable for client development (see better options below) 
  * builds the client application once (**subsequent changes will not be reflected until another build**)
  * builds and runs the server application, serving the compiled client app
    * the server is executed under Ring's `wrap-reload` middleware, so changes made to server source files will be compiled, reloaded

#### Option 2: (and (Pick 1 of (or `run-dev-client` `run-dev-client-repl`)) (Pick 1 of (or `run-dev-server` `run-dev-server-repl`))
  * `run-dev-client`
    * starts `Figwheel.main` to continuously compile and reload client changes
      * changes (including css) are reflected (almost) immediately from Figwheel's reported url (defaults to port 9500)
      * changes are also reflected (less) immediately from the paired `run-dev-server*` process (defaults to port 4545)
        * because Figwheel compiles to a directory served by the server process
  * `run-dev-client-repl`
    * starts `rebel.main` to enable bootstrapping the client repl
    * see `dev/dev_client.clj` for usage instructions
    * this configuration is more involved, but enables fine-grained control over the client application
  * `run-dev-server`
    * starts the server application through `dev-server`
    * `dev-server` utilizes the Ring middleware, `wrap-reload` to update the running server with the latest updates
  * `run-dev-server-repl`
    * enables repl-based development workflow for server application development
      * i.e. connect to the repl from an editor
  * run the chosen 'dev-client' task and the chose 'dev-server' task in separate terminals 

### Building for Distribution

```bash
bb all
```

Will build everything into an UberJAR, under the `target` directory

You can then run the UberJAR like this:

```bash
java -jar PATH/TO/clj-ts-0.1.0-SNAPSHOT-standalone-YYYY-MM-DD.jar
```

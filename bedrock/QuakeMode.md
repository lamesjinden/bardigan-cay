# Overview

Quake Mode is an expanded Clojure evaluation interface inspired by the drop-down console in classic games like Quake. It provides a persistent REPL-like environment for evaluating Clojure expressions directly within [[CardiganBay]].

----

### Entering and Exiting Quake Mode

There are two ways to toggle Quake Mode:

1. **Keyboard shortcut**: Press `Ctrl + backtick` (`` ` ``) anywhere in the application
2. **Control-click**: Hold `Ctrl` and click anywhere on [[TheNavBar]]

When active, the navigation bar expands to show the Quake console, and the lambda button turns pink to indicate the mode is active.

----

### Using Quake Mode

The Quake console consists of two parts:

1. **Results area**: A scrollable panel showing previously evaluated expressions and their results
2. **Editor**: A Clojure-aware text editor at the bottom for entering expressions

To evaluate an expression:
- Type your Clojure code in the editor
- Press `Ctrl+Enter` (or `Cmd+Enter` on Mac), or click the lambda button

Results are displayed in the format `expression => result` and accumulate in the results area. They also appear in [[TheTranscript]].

----

### History Navigation

Use the arrow keys to navigate through previously evaluated expressions:
- **Up arrow**: Load the previous expression
- **Down arrow**: Load the next expression (or clear if at the end)

----

### REPL Special Variables

Like the standard Clojure REPL, Quake Mode provides special variables:
- `*1` - the result of the last evaluation
- `*2` - the result of the second-to-last evaluation
- `*3` - the result of the third-to-last evaluation
- `*e` - the last exception/error

----

### Clearing Results

Click the "clear all" button (appears to the right of the lambda button in Quake Mode) to clear the results area.

----

### Notes

- Results persist while Quake Mode is toggled off and on, but are cleared when the page is refreshed
- Any text in the normal NavBar input is transferred to the Quake editor when entering Quake Mode
- Both inputs are cleared after evaluating an expression
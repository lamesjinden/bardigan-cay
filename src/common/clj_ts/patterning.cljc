(ns clj-ts.patterning
  [:require
   ;; Patterning stuff
   [patterning.maths :as p-maths :refer [PI half-PI sin cos]]
   [patterning.sshapes
    :refer [->SShape to-triangles]
    :as sshapes]
   [patterning.groups :as groups :refer [group over-style scale translate translate-to
                                         h-reflect v-reflect stretch rotate wobble reframe]]
   [patterning.layouts :refer [framed clock-rotate stack grid-layout diamond-layout
                               four-mirror four-round nested-stack checked-layout
                               half-drop-grid-layout random-turn-groups h-mirror ring
                               sshape-as-layout one-col-layout nested-stack]
    :as layouts]
   [patterning.library.std :refer [poly star nangle spiral diamond rect
                                   horizontal-line square drunk-line bez-curve]
    :as std]
   [patterning.library.turtle :refer [basic-turtle] :as turtle]
   [patterning.library.l_systems :refer [l-system]]
   [patterning.color :refer [p-color remove-transparency rand-col darker-color] :as p-colors]])

(def patterning-ns
  {'clojure.core
   {;; maths
    'PI PI
    'sin sin
    'cos cos
    'half-PI half-PI
    ;; sshapes
    '->SShape ->SShape
    'to-triangles to-triangles
    'bez-curve bez-curve
    ;; groups
    'group group
    'over-style over-style
    'scale scale
    'translate translate
    'translate-to translate-to
    'h-reflect h-reflect
    'v-reflect v-reflect
    'stretch stretch
    'rotate rotate
    'wobble wobble
    'reframe reframe
    ;; layouts
    'framed framed
    'clock-rotate clock-rotate
    'stack stack
    'grid-layout grid-layout
    'diamond-layout diamond-layout
    'four-mirror four-mirror
    'four-round four-round
    'nested-stack nested-stack
    'checked-layout checked-layout
    'half-drop-grid-layout half-drop-grid-layout
    'random-turn-groups random-turn-groups
    'h-mirror h-mirror 'ring ring
    'sshape-as-layout sshape-as-layout
    'one-col-layout one-col-layout
    ;; std
    'poly poly
    'star star
    'nangle nangle
    'spiral spiral
    'diamond diamond
    'rect rect
    'horizontal-line horizontal-line
    'square square
    'drunk-line drunk-line
    ;; turtle
    'basic-turtle basic-turtle
    ;; L-Systems
    'l-system l-system
    ;; colors
    'p-color p-color
    'rand-col rand-col
    'darker-color darker-color
    'remove-transparency remove-transparency}})

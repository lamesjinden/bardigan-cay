#!/usr/bin/env bb
;; Subset the Material Symbols Sharp variable font to the icons bardigan-cay uses.
;;
;; Ligature-aware: icon glyphs are reached via GSUB 'liga' rules whose
;; components are letter glyphs. Text-based subsetting can't work here (every
;; icon name is spelled from the same letters, so glyph closure would retain
;; the whole icon set); instead this script parses the font's cmap and GSUB
;; tables directly, resolves each wanted icon name to its ligature glyph id,
;; and drives harfbuzz (via font_tool.mjs) with those explicit gids and layout
;; closure disabled. Node runs the mechanical woff2 decompress/subset machinery
;; using the harfbuzzjs and wawoff2 wasm packages from devDependencies.
;;
;; Usage: bb subset_icons.clj <in.woff2> <out.woff2> <icons.txt>

(ns subset-icons
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [clojure.string :as str]))

;; region big-endian readers over a byte array

(defn u8 [b i]
  (bit-and (aget b i) 0xff))

(defn u16 [b i]
  (bit-or (bit-shift-left (u8 b i) 8)
          (u8 b (inc i))))

(defn u32 [b i]
  (bit-or (bit-shift-left (u16 b i) 16)
          (u16 b (+ i 2))))

(defn tag [b i]
  (String. ^bytes b ^int i 4 "US-ASCII"))

;; endregion

;; region sfnt table directory

(defn table-directory
  "tag -> offset of each top-level font table"
  [b]
  (into {}
        (for [k (range (u16 b 4))
              :let [rec (+ 12 (* 16 k))]]
          [(tag b rec) (u32 b (+ rec 8))])))

;; endregion

;; region cmap (formats 4 and 12)

(defn gid-at-format4 [b off cp]
  (let [seg2 (u16 b (+ off 6))
        end-base (+ off 14)
        start-base (+ end-base seg2 2)
        delta-base (+ start-base seg2)
        range-base (+ delta-base seg2)]
    (loop [i 0]
      (when (< (* 2 i) seg2)
        (let [end (u16 b (+ end-base (* 2 i)))]
          (if (< end cp)
            (recur (inc i))
            (let [start (u16 b (+ start-base (* 2 i)))
                  ;; unsigned add mod 2^16 is equivalent to the spec's signed idDelta
                  delta (u16 b (+ delta-base (* 2 i)))
                  range-offset (u16 b (+ range-base (* 2 i)))]
              (when (<= start cp)
                (if (zero? range-offset)
                  (bit-and (+ cp delta) 0xffff)
                  (let [gid0 (u16 b (+ range-base (* 2 i) range-offset (* 2 (- cp start))))]
                    (when-not (zero? gid0)
                      (bit-and (+ gid0 delta) 0xffff))))))))))))

(defn gid-at-format12 [b off cp]
  (let [n-groups (u32 b (+ off 12))]
    (loop [i 0]
      (when (< i n-groups)
        (let [group (+ off 16 (* 12 i))
              start (u32 b group)
              end (u32 b (+ group 4))]
          (cond
            (< end cp) (recur (inc i))
            (<= start cp) (+ (u32 b (+ group 8)) (- cp start))
            :else nil))))))

(defn char->gid-fn
  "Lookup fn codepoint -> gid (or nil), trying format-12 subtables before format-4."
  [b cmap-off]
  (let [subtables (for [i (range (u16 b (+ cmap-off 2)))
                        :let [off (+ cmap-off (u32 b (+ cmap-off 8 (* 8 i))))
                              fmt (u16 b off)]
                        :when (#{4 12} fmt)]
                    [fmt off])
        ordered (concat (filter #(= 12 (first %)) subtables)
                        (filter #(= 4 (first %)) subtables))]
    (fn [cp]
      (some (fn [[fmt off]]
              (let [gid (case fmt
                          4 (gid-at-format4 b off cp)
                          12 (gid-at-format12 b off cp))]
                (when (and gid (pos? gid))
                  gid)))
            ordered))))

;; endregion

;; region GSUB ligature substitutions

(defn coverage-glyphs
  "Glyph ids covered by a coverage table, in coverage-index order."
  [b cov-off]
  (let [n (u16 b (+ cov-off 2))]
    (case (u16 b cov-off)
      1 (mapv #(u16 b (+ cov-off 4 (* 2 %))) (range n))
      2 (vec (mapcat (fn [i]
                       (let [rec (+ cov-off 4 (* 6 i))]
                         (range (u16 b rec) (inc (u16 b (+ rec 2))))))
                     (range n))))))

(defn ligature-entries
  "All GSUB ligature substitutions, as {:first-gid _ :component-gids [_] :ligature-gid _},
  unwrapping extension (type 7) lookups around ligature (type 4) subtables."
  [b gsub-off]
  (let [lookup-list (+ gsub-off (u16 b (+ gsub-off 8)))]
    (for [i (range (u16 b lookup-list))
          :let [lookup (+ lookup-list (u16 b (+ lookup-list 2 (* 2 i))))
                lookup-type (u16 b lookup)]
          j (range (u16 b (+ lookup 4)))
          :let [st0 (+ lookup (u16 b (+ lookup 6 (* 2 j))))
                [st-type st] (if (= 7 lookup-type)
                               [(u16 b (+ st0 2)) (+ st0 (u32 b (+ st0 4)))]
                               [lookup-type st0])]
          :when (= 4 st-type)
          :let [coverage (coverage-glyphs b (+ st (u16 b (+ st 2))))]
          k (range (u16 b (+ st 4)))
          :let [lig-set (+ st (u16 b (+ st 6 (* 2 k))))]
          m (range (u16 b lig-set))
          :let [lig (+ lig-set (u16 b (+ lig-set 2 (* 2 m))))
                component-count (u16 b (+ lig 2))]]
      {:first-gid (nth coverage k)
       :component-gids (mapv #(u16 b (+ lig 4 (* 2 %))) (range (dec component-count)))
       :ligature-gid (u16 b lig)})))

;; endregion

;; region icon-name resolution

(defn parse-font [ttf-path]
  (let [b (fs/read-all-bytes ttf-path)
        tables (table-directory b)]
    {:bytes b
     :glyph-count (u16 b (+ (tables "maxp") 4))
     :char->gid (char->gid-fn b (tables "cmap"))
     :ligatures (ligature-entries b (tables "GSUB"))}))

(defn icon->ligature-gid
  "icon-name -> ligature gid, for every ligature whose components spell out
  letters we can name; ligatures involving unknown glyphs are skipped."
  [{:keys [ligatures]} gid->char]
  (into {}
        (keep (fn [{:keys [first-gid component-gids ligature-gid]}]
                (when (and (gid->char first-gid)
                           (every? gid->char component-gids))
                  [(apply str (gid->char first-gid) (map gid->char component-gids))
                   ligature-gid]))
              ligatures)))

(defn resolve-icons
  "For a parsed font, {:letter-gids {char gid} :icon-gids {name gid} :missing [name]}."
  [font icons]
  (let [chars (distinct (mapcat seq icons))
        letter-gids (into {}
                          (map (fn [ch]
                                 (if-let [gid ((:char->gid font) (int ch))]
                                   [ch gid]
                                   (throw (ex-info (str "character " (pr-str ch) " missing from font cmap") {}))))
                               chars))
        gid->char (into {} (map (juxt val key)) letter-gids)
        by-name (icon->ligature-gid font gid->char)]
    {:letter-gids letter-gids
     :icon-gids (select-keys by-name icons)
     :all-names by-name
     :missing (remove #(contains? by-name %) icons)}))

;; endregion

(defn read-icons [icons-file]
  (->> (str/split-lines (slurp icons-file))
       (map str/trim)
       (remove #(or (str/blank? %) (str/starts-with? % "#")))
       (sort)
       (distinct)))

(def font-tool
  (str (fs/path (fs/parent (fs/canonicalize (System/getProperty "babashka.file")))
                "font_tool.mjs")))

(defn decompress-woff2 [woff2-path ttf-path]
  (shell "node" font-tool "decompress" (str woff2-path) (str ttf-path)))

(defn -main [src dst icons-file]
  (let [icons (read-icons icons-file)
        work-dir (fs/create-temp-dir {:prefix "font-subset"})
        src-ttf (fs/path work-dir "src.ttf")]
    (decompress-woff2 src src-ttf)
    (let [font (parse-font src-ttf)
          {:keys [letter-gids icon-gids missing]} (resolve-icons font icons)]
      (when (seq missing)
        (throw (ex-info (str "icons not found in font: " (vec missing)) {})))
      (shell "node" font-tool "subset" (str src-ttf) dst
             (str/join "," (sort (concat (vals letter-gids) (vals icon-gids))))
             (str/join "," (map int (keys letter-gids))))

      ;; verify: every wanted icon must still resolve to a ligature in the output
      (let [out-ttf (fs/path work-dir "out.ttf")
            _ (decompress-woff2 dst out-ttf)
            out-font (parse-font out-ttf)
            resolved (resolve-icons out-font icons)
            lost (concat (:missing resolved)
                         (remove #(contains? (:icon-gids resolved) %) icons))
            extra (sort (remove (set icons) (keys (:all-names resolved))))]
        (when (seq lost)
          (throw (ex-info (str "VERIFY FAILED, ligatures lost in subset: " (vec (distinct lost))) {})))
        (println (format "kept %d icons, %d glyphs total" (count icons) (:glyph-count out-font)))
        (when (seq extra)
          (println (format "note: %d extra ligature names also present: %s" (count extra) (vec extra))))
        (println "verify OK: all icon ligatures resolve in subset font")))
    (fs/delete-tree work-dir)))

(when (not= 3 (count *command-line-args*))
  (println "usage: bb subset_icons.clj <in.woff2> <out.woff2> <icons.txt>")
  (System/exit 1))

(apply -main *command-line-args*)

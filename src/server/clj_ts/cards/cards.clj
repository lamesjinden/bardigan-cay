(ns clj-ts.cards.cards
  {:clj-kondo/config '{:linters {:unresolved-symbol {:exclude [(better-cond.core/cond)]}}}}
  (:require [clojure.string :as string]
            [better-cond.core :as b]
            [clj-ts.cards.parsing :as parsing]))

(defn card->raw [{:keys [source_data]}]
  (str "\n\n" (string/trim source_data) "\n\n"))

(defn card-is-blank? [{:keys [source_data]}]
  (= "" (string/trim source_data)))

;; region Cards in card list

(defn card-match [card-map hash-or-id]
  (b/cond
    (= (.toString hash-or-id) (.toString (:hash card-map)))
    (assoc card-map :tx/locator :hash)

    :let [card-data (parsing/card-map->card-data card-map)
          card-id (:card/id card-data)]

    (= card-id hash-or-id)
    (assoc card-map :tx/locator :id)

    :else nil))

(defn card-matches [card-map hash-or-id]
  (let [match (card-match card-map hash-or-id)]
    (boolean match)))

(defn neh
  "Not equal hash"
  [card hash]
  (not (card-matches card hash)))

(defn find-card-by-hash
  "Take a list of cards and return the one that matches hash or id; else nil"
  [cards hash-or-id]
  (->> cards
       (keep (fn [card]
               (card-match card hash-or-id)))
       (first)))

(defn remove-card-by-hash
  "Take a list of cards and return the list without the card that matches hash"
  [cards hash-or-id]
  (remove #(card-matches % hash-or-id) cards))

(defn replace-card
  "Replace the first card that matches p with new-card. If no card matches, return cards unchanged"
  [cards p new-card]
  (let [un-p #(not (p %))
        before (take-while un-p cards)
        after (rest (drop-while un-p cards))]
    (if (= 0 (count (filter p cards)))
      cards
      (concat before [new-card] after))))

(defn move-card-up
  "Move a card (id by hash) one up"
  [cards hash]
  (let [c (find-card-by-hash cards hash)]
    (if (nil? c)
      cards
      (let [before (take-while #(neh % hash) cards)
            after (rest (drop-while #(neh % hash) cards))
            res (remove nil?
                        (concat
                         (butlast before)
                         [c]
                         [(last before)]
                         after))]
        res))))

(defn move-card-down
  "Move a card (id by hash) one down"
  [cards hash]
  (let [c (find-card-by-hash cards hash)]
    (if (nil? c)
      cards
      (let [before (take-while #(neh % hash) cards)
            after (rest (drop-while #(neh % hash) cards))
            res (remove nil?
                        (concat
                         before
                         [(first after)]
                         [c]
                         (rest after)))]
        res))))

(defn cards->raw [cards]
  (->> cards
       (map card->raw)
       (string/join "----")
       (string/trim)))

;; endregion
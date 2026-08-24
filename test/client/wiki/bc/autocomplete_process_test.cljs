(ns wiki.bc.autocomplete-process-test
  (:require [cljs.core.async :as a]
            [cljs.test :refer (deftest is async)]
            [wiki.bc.autocomplete.autocomplete-process :as autocomplete]))

(defn- create-fake-fetch
  "Returns [fetch-suggestions requests] where fetch-suggestions mimics
   http/http-get* and requests records each call in order as
   {:query .. :response$ .. :aborted? ..}. Like XhrIo, abort!
   synchronously resolves the response with an aborted response map."
  []
  (let [requests (atom [])
        fetch-suggestions (fn [query]
                            (let [response$ (a/promise-chan)
                                  aborted? (atom false)]
                              (swap! requests conj {:query     query
                                                    :response$ response$
                                                    :aborted?  aborted?})
                              {:response$ response$
                               :abort!    (fn []
                                            (reset! aborted? true)
                                            (a/put! response$ {:isSuccess false
                                                               :aborted?  true}))}))]
    [fetch-suggestions requests]))

(deftest query-yields-parsed-suggestions
  (async done
         (a/go
           (let [input$ (a/chan)
                 [fetch-suggestions requests] (create-fake-fetch)
                 result$ (autocomplete/<create-autocomplete-process input$ {:fetch-suggestions fetch-suggestions})]
             (a/>! input$ "Page")
             (a/<! (a/timeout 1))
             (a/put! (:response$ (first @requests)) {:isSuccess true
                                                     :body      "[\"PageA\",\"PageB\"]"})
             (let [{:keys [query suggestions result-error]} (a/<! result$)]
               (is (= "Page" query))
               (is (= ["PageA" "PageB"] suggestions))
               (is (nil? result-error)))
             (done)))))

(deftest unparseable-body-yields-a-result-error
  (async done
         (a/go
           (let [input$ (a/chan)
                 [fetch-suggestions requests] (create-fake-fetch)
                 result$ (autocomplete/<create-autocomplete-process input$ {:fetch-suggestions fetch-suggestions})]
             (a/>! input$ "Page")
             (a/<! (a/timeout 1))
             (a/put! (:response$ (first @requests)) {:isSuccess true
                                                     :body      "{not-json"})
             (let [{:keys [query suggestions result-error]} (a/<! result$)]
               (is (= "Page" query))
               (is (empty? suggestions))
               (is (some? result-error)))
             (done)))))

(deftest newer-query-supersedes-the-in-flight-request
  (async done
         (a/go
           (let [input$ (a/chan)
                 [fetch-suggestions requests] (create-fake-fetch)
                 result$ (autocomplete/<create-autocomplete-process input$ {:fetch-suggestions fetch-suggestions})]
             (a/>! input$ "Pag")
             (a/<! (a/timeout 1))
             (a/>! input$ "Page")
             (a/<! (a/timeout 1))
             (let [[request-a request-b] @requests]
               (is (true? @(:aborted? request-a)))
               (a/put! (:response$ request-b) {:isSuccess true
                                               :body      "[\"PageA\"]"})
               (let [{:keys [query suggestions]} (a/<! result$)]
                 (is (= "Page" query))
                 (is (= ["PageA"] suggestions)))
               ;; the superseded query's suggestions never surface
               (let [[value _] (a/alts! [result$ (a/timeout 5)])]
                 (is (nil? value))))
             (done)))))

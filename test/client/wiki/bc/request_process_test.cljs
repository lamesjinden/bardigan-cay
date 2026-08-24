(ns wiki.bc.request-process-test
  (:require [cljs.core.async :as a]
            [cljs.test :refer (deftest is async)]
            [wiki.bc.request-process :as request-process]))

(defn- create-fake-start-request
  "Returns [start-request requests] where start-request mimics
   http/http-post* and requests records each call in order as
   {:input .. :response$ .. :aborted? ..}. Like XhrIo, abort!
   synchronously resolves the response with an aborted response map."
  []
  (let [requests (atom [])
        start-request (fn [input]
                        (let [response$ (a/promise-chan)
                              aborted? (atom false)]
                          (swap! requests conj {:input     input
                                                :response$ response$
                                                :aborted?  aborted?})
                          {:response$ response$
                           :abort!    (fn []
                                        (reset! aborted? true)
                                        (a/put! response$ {:isSuccess false
                                                           :aborted?  true}))}))]
    [start-request requests]))

(deftest response-is-delivered-to-the-event-out-chan
  (async done
         (a/go
           (let [source$ (a/chan)
                 [start-request requests] (create-fake-start-request)
                 _ (request-process/create-request-process source$ start-request)
                 out-chan (a/promise-chan)]
             (a/>! source$ {:page-name "PageA"
                            :data      "content"
                            :out-chan  out-chan})
             (a/<! (a/timeout 1))
             (a/put! (:response$ (first @requests)) {:isSuccess true
                                                     :body      "saved"})
             (let [result (a/<! out-chan)]
               (is (= "saved" (:body result))))
             (done)))))

(deftest superseded-event-out-chan-is-closed
  (async done
         (a/go
           (let [source$ (a/chan)
                 [start-request requests] (create-fake-start-request)
                 _ (request-process/create-request-process source$ start-request)
                 out-chan-a (a/promise-chan)
                 out-chan-b (a/promise-chan)]
             (a/>! source$ {:page-name "PageA"
                            :data      "one"
                            :out-chan  out-chan-a})
             (a/<! (a/timeout 1))
             (a/>! source$ {:page-name "PageA"
                            :data      "two"
                            :out-chan  out-chan-b})
             (a/<! (a/timeout 1))
             (let [[request-a request-b] @requests]
               (is (true? @(:aborted? request-a)))
               ;; the superseded requester's continuation is skipped (take yields nil)
               (is (nil? (a/<! out-chan-a)))
               (a/put! (:response$ request-b) {:isSuccess true
                                               :body      "saved-two"})
               (let [result (a/<! out-chan-b)]
                 (is (= "saved-two" (:body result)))))
             (done)))))

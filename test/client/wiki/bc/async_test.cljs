(ns wiki.bc.async-test
  (:require [cljs.core.async :as a]
            [cljs.test :refer (deftest is async)]
            [wiki.bc.async :as bc-async]))

(defn- create-fake-start-request
  "Returns [start-request requests] where start-request mimics
   http/http-get* and requests records each call in order as
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

(deftest response-is-emitted-paired-with-its-input
  (async done
         (a/go
           (let [source$ (a/chan)
                 [start-request requests] (create-fake-start-request)
                 out$ (bc-async/create-switching-channel source$ start-request)]
             (a/>! source$ "a")
             (a/<! (a/timeout 1))
             (a/put! (:response$ (first @requests)) {:isSuccess true
                                                     :body      "a-body"})
             (let [{:keys [input response]} (a/<! out$)]
               (is (= "a" input))
               (is (= "a-body" (:body response))))
             (done)))))

(deftest newer-input-supersedes-the-in-flight-request
  (async done
         (a/go
           (let [source$ (a/chan)
                 superseded (atom [])
                 [start-request requests] (create-fake-start-request)
                 out$ (bc-async/create-switching-channel source$ start-request
                                                         :on-supersede! (fn [input] (swap! superseded conj input)))]
             (a/>! source$ "a")
             (a/<! (a/timeout 1))
             (a/>! source$ "b")
             (a/<! (a/timeout 1))
             (let [[request-a request-b] @requests]
               (is (= ["a"] @superseded))
               (is (true? @(:aborted? request-a)))
               (is (false? @(:aborted? request-b)))
               (a/put! (:response$ request-b) {:isSuccess true
                                               :body      "b-body"})
               (let [{:keys [input response]} (a/<! out$)]
                 (is (= "b" input))
                 (is (= "b-body" (:body response))))
               ;; the superseded request's response never surfaces
               (let [[value _] (a/alts! [out$ (a/timeout 5)])]
                 (is (nil? value))))
             (done)))))

(deftest non-abort-failure-responses-are-emitted
  (async done
         (a/go
           (let [source$ (a/chan)
                 [start-request requests] (create-fake-start-request)
                 out$ (bc-async/create-switching-channel source$ start-request)]
             (a/>! source$ "a")
             (a/<! (a/timeout 1))
             (a/put! (:response$ (first @requests)) {:isSuccess false
                                                     :status    500})
             (let [{:keys [input response]} (a/<! out$)]
               (is (= "a" input))
               (is (= 500 (:status response))))
             (done)))))

(deftest closing-the-source-aborts-in-flight-and-closes-the-output
  (async done
         (a/go
           (let [source$ (a/chan)
                 superseded (atom [])
                 [start-request requests] (create-fake-start-request)
                 out$ (bc-async/create-switching-channel source$ start-request
                                                         :on-supersede! (fn [input] (swap! superseded conj input)))]
             (a/>! source$ "a")
             (a/<! (a/timeout 1))
             (a/close! source$)
             (a/<! (a/timeout 1))
             (is (= ["a"] @superseded))
             (is (true? @(:aborted? (first @requests))))
             (is (nil? (a/<! out$)))
             (done)))))

(ns wiki.bc.navigation-process-test
  (:require [cljs.core.async :as a]
            [cljs.test :refer (deftest is async)]
            [wiki.bc.confirmation.navigation-process :as nav-process]))

(defn- create-fake-fetch
  "Returns [fetch-page requests] where fetch-page mimics
   http/http-get* and requests records each call in order as
   {:page-name .. :response$ .. :aborted? ..}."
  []
  (let [requests (atom [])
        fetch-page (fn [page-name rev]
                     (let [response$ (a/promise-chan)
                           aborted? (atom false)]
                       (swap! requests conj {:page-name page-name
                                             :rev       rev
                                             :response$ response$
                                             :aborted?  aborted?})
                       {:response$ response$
                        :abort!    (fn [] (reset! aborted? true))}))]
    [fetch-page requests]))

(defn- <navigate
  ([navigating$ page-name]
   (<navigate navigating$ page-name nil))
  ([navigating$ page-name rev]
   (let [out-chan (a/promise-chan)]
     (a/put! navigating$ {:page-name page-name
                          :rev       rev
                          :out-chan  out-chan})
     out-chan)))

(defn- <confirm-with [response]
  (fn []
    (doto (a/promise-chan)
      (a/put! response))))

(deftest successful-navigation-delivers-the-response
  (async done
         (a/go
           (let [navigating$ (a/chan)
                 editing$ (a/chan)
                 [fetch-page requests] (create-fake-fetch)
                 _ (nav-process/<create-nav-process navigating$ editing$
                                                    {:fetch-page fetch-page
                                                     :<confirm   (<confirm-with :ok)})
                 out-chan (<navigate navigating$ "PageA")
                 _ (a/<! (a/timeout 1))
                 request (first @requests)]
             (a/put! (:response$ request) {:isSuccess true
                                           :body      "page-a-body"})
             (let [result (a/<! out-chan)]
               (is (= "page-a-body" (:body result)))
               (is (false? @(:aborted? request))))
             (done)))))

(deftest navigation-at-a-revision-fetches-that-revision
  (async done
         (a/go
           (let [navigating$ (a/chan)
                 editing$ (a/chan)
                 [fetch-page requests] (create-fake-fetch)
                 _ (nav-process/<create-nav-process navigating$ editing$
                                                    {:fetch-page fetch-page
                                                     :<confirm   (<confirm-with :ok)})
                 out-chan (<navigate navigating$ "PageA" "abc1234")
                 _ (a/<! (a/timeout 1))
                 request (first @requests)]
             (is (= "PageA" (:page-name request)))
             (is (= "abc1234" (:rev request)))
             (a/put! (:response$ request) {:isSuccess true
                                           :body      "page-a-at-rev"})
             (is (= "page-a-at-rev" (:body (a/<! out-chan))))
             (done)))))

(deftest second-navigation-cancels-the-in-flight-request
  (async done
         (a/go
           (let [navigating$ (a/chan)
                 editing$ (a/chan)
                 [fetch-page requests] (create-fake-fetch)
                 _ (nav-process/<create-nav-process navigating$ editing$
                                                    {:fetch-page fetch-page
                                                     :<confirm   (<confirm-with :ok)})
                 out-chan-a (<navigate navigating$ "PageA")
                 out-chan-b (<navigate navigating$ "PageB")
                 result-a (a/<! out-chan-a)
                 [request-a request-b] @requests]
             (is (= :canceled result-a))
             (is (= "PageA" (:page-name request-a)))
             (is (true? @(:aborted? request-a)))
             (is (= "PageB" (:page-name request-b)))
             (a/put! (:response$ request-b) {:isSuccess true
                                             :body      "page-b-body"})
             (let [result-b (a/<! out-chan-b)]
               (is (= "page-b-body" (:body result-b))))
             (done)))))

(deftest failed-navigation-closes-the-out-chan
  (async done
         (a/go
           (let [navigating$ (a/chan)
                 editing$ (a/chan)
                 [fetch-page requests] (create-fake-fetch)
                 _ (nav-process/<create-nav-process navigating$ editing$
                                                    {:fetch-page fetch-page
                                                     :<confirm   (<confirm-with :ok)})
                 out-chan (<navigate navigating$ "PageA")
                 _ (a/<! (a/timeout 1))
                 request (first @requests)]
             (a/put! (:response$ request) {:isSuccess false
                                           :status    500})
             (let [result (a/<! out-chan)]
               (is (nil? result)))
             (done)))))

(deftest declined-confirmation-resolves-canceled-without-fetching
  (async done
         (a/go
           (let [navigating$ (a/chan)
                 editing$ (a/chan)
                 [fetch-page requests] (create-fake-fetch)
                 _ (nav-process/<create-nav-process navigating$ editing$
                                                    {:fetch-page fetch-page
                                                     :<confirm   (<confirm-with :cancel)})]
             (a/put! editing$ {:id "editor-1" :action :start})
             (a/<! (a/timeout 1))
             (let [out-chan (<navigate navigating$ "PageA")
                   result (a/<! out-chan)]
               (is (= :canceled result))
               (is (empty? @requests)))
             (done)))))

(deftest confirmed-navigation-during-editing-fetches-the-page
  (async done
         (a/go
           (let [navigating$ (a/chan)
                 editing$ (a/chan)
                 [fetch-page requests] (create-fake-fetch)
                 _ (nav-process/<create-nav-process navigating$ editing$
                                                    {:fetch-page fetch-page
                                                     :<confirm   (<confirm-with :ok)})]
             (a/put! editing$ {:id "editor-1" :action :start})
             (a/<! (a/timeout 1))
             (let [out-chan (<navigate navigating$ "PageA")
                   _ (a/<! (a/timeout 1))
                   request (first @requests)]
               (is (= "PageA" (:page-name request)))
               (a/put! (:response$ request) {:isSuccess true
                                             :body      "page-a-body"})
               (let [result (a/<! out-chan)]
                 (is (= "page-a-body" (:body result)))))
             (done)))))

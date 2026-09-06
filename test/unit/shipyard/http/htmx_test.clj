(ns shipyard.http.htmx-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shipyard.http.htmx :as htmx]))

(deftest events-are-namespaced
  (testing "shipyard:* so a viewport listener can never collide with htmx's own"
    (is (= "shipyard:load-mesh" (htmx/event-name :load-mesh)))
    (is (= "shipyard:clear" (htmx/event-name :clear)))
    (is (= "shipyard:status" (htmx/event-name :status)))))

(deftest trigger-is-json-outside-and-edn-inside
  (let [payload {:url "/mesh/abc.0.symesh"
                 :part-id "Human Navy Fleet Bundle/Cruiser/Hull"
                 :frame true}
        header  (htmx/trigger {:load-mesh payload})
        parsed  (json/read-str header)]
    (testing "the envelope is JSON because htmx parses this header itself"
      (is (= ["shipyard:load-mesh"] (keys parsed))))
    (testing "the payload is EDN, so keywords survive the trip unmapped"
      (is (= payload (edn/read-string (get parsed "shipyard:load-mesh")))))
    (testing "and slashes are left alone - data.json escapes them by default,
              which turns every mesh URL in a log into \\/mesh\\/"
      (is (str/includes? header "/mesh/abc.0.symesh"))
      (is (not (str/includes? header "\\/"))))
    (testing "a payload htmx would have flattened"
      (is (= {:state :failed :tiers #{0 1 2}}
             (-> (htmx/trigger {:status {:state :failed :tiers #{0 1 2}}})
                 (json/read-str)
                 (get "shipyard:status")
                 (edn/read-string)))))))

(deftest trigger-survives-a-header
  (testing "an HTTP header value is not reliably UTF-8, and this library has
            folder names that are not ASCII"
    (let [header (htmx/trigger {:load-mesh {:part-id "Caf\u00e9 Noir/Cruiser/Prow #2"}})]
      (is (every? #(< (int %) 128) header)
          (str "non-ASCII reached the header: " header))
      (testing "and it still reads back as what went in - data.json escapes
                non-ASCII by default, it does not drop it"
        (is (= "Caf\u00e9 Noir/Cruiser/Prow #2"
               (-> header json/read-str (get "shipyard:load-mesh")
                   edn/read-string :part-id)))))))

(deftest trigger-carries-several-events
  (let [parsed (json/read-str (htmx/trigger {:clear nil :status {:state :idle}}))]
    (is (= #{"shipyard:clear" "shipyard:status"} (set (keys parsed))))
    (is (nil? (edn/read-string (get parsed "shipyard:clear"))))))

(deftest fragments-are-never-cached
  (testing "a fragment is a view of live catalog state; a cached one is a lie"
    (let [{:keys [status headers body]} (htmx/fragment [:p "hi"])]
      (is (= 200 status))
      (is (= "no-store" (get headers "cache-control")))
      (is (= "text/html; charset=utf-8" (get headers "content-type")))
      (is (= "<p>hi</p>" body))
      (is (nil? (get headers "HX-Trigger"))))))

(deftest fragment-options
  (let [{:keys [status headers]} (htmx/fragment [:p "gone"]
                                                {:status 404 :events {:clear nil}})]
    (is (= 404 status))
    (is (str/includes? (get headers "HX-Trigger") "shipyard:clear"))))

(deftest fragments-escape-part-names
  (testing "designers put ampersands and angle brackets in folder names"
    (is (= "<span>Ram &amp; Lance &lt;A&gt;</span>"
           (htmx/html [:span "Ram & Lance <A>"])))))

(deftest page-is-a-document
  (let [{:keys [body headers]} (htmx/page [:html [:body "x"]])]
    (is (str/starts-with? body "<!DOCTYPE html>"))
    (is (= "no-store" (get headers "cache-control")))))

(deftest cache-control-constants
  (is (= "public, max-age=31536000, immutable" htmx/immutable-cache-control))
  (is (= "no-store" htmx/fragment-cache-control)))

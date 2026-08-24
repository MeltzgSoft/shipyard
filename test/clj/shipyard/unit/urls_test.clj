(ns shipyard.unit.urls-test
  "The `:id` round trip. Asserted through the real router rather than against a
  hand-written decoder, because what has to hold is that a URL this code
  produces arrives back as the same part id - not that two functions in the same
  namespace agree with each other."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reitit.core :as r]
            [shipyard.http.urls :as urls]))

(def ^:private router (r/router [["/part/*id" ::part] ["/mesh/:file" ::mesh]]))

(defn- round-trip [id]
  (get-in (r/match-by-path router (urls/part-url id)) [:path-params :id]))

(deftest ids-round-trip-through-the-router
  (doseq [id ["Human Navy Fleet Bundle/Cruiser/Classic Ram Prow"
              "Ork Fleet Bundle/Escort/Ram Ship"
              "Bundle/Cruiser/Prow #2"
              "Bundle/Cruiser/Lance & Torpedo"
              "Bundle/Cruiser/50% Scale"
              "Bundle/Cruiser/A+B Variant"
              "Bundle/Cruiser/Prow (mk II)"
              "Bundle/Cruiser/Caf\u00e9 Noir"]]
    (is (= id (round-trip id)) id)))

(deftest separators-stay-literal
  (testing "encoded separators are not an option: Jetty answers 400 for %2F
            before a handler ever sees the request"
    (let [url (urls/part-url "A B/C D/E F")]
      (is (= "/part/A%20B/C%20D/E%20F" url))
      (is (not (str/includes? url "%2F"))))))

(deftest spaces-are-percent-twenty-not-plus
  (testing "URLEncoder targets form bodies, where + means space; in a path it
            means a literal plus"
    (is (= "/part/Ram%20Prow" (urls/part-url "Ram Prow")))))

(deftest mesh-urls-are-content-addressed
  (let [key (apply str (repeat 64 "a"))]
    (is (= (str "/mesh/" key ".0.symesh") (urls/mesh-url key 0)))
    (is (= [key "2"] (rest (re-matches urls/mesh-file-re (str key ".2.symesh")))))))

(deftest mesh-pattern-is-the-access-control
  (testing "only a hex digest and a small integer ever reach the filesystem"
    (doseq [bad ["../../etc/passwd"
                 "../../../home/user/.ssh/id_rsa.0.symesh"
                 "not-a-hash.0.symesh"
                 (str (apply str (repeat 64 "a")) ".0.symesh.bak")
                 (str (apply str (repeat 63 "a")) ".0.symesh")
                 (str (apply str (repeat 64 "A")) ".0.symesh")]]
      (is (nil? (re-matches urls/mesh-file-re bad)) bad))))

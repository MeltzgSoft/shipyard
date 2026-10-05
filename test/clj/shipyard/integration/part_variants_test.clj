(ns shipyard.integration.part-variants-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [shipyard.assembly-fixture :as fixture]
            [shipyard.catalog.db :as catalog]
            [shipyard.part-variants-fixture :refer [a b c build!]]
            [shipyard.library.index :as index]
            [shipyard.part-browser.variants :as variants]
            [shipyard.store.db :as store]))

(deftest grouping-http-round-trip-and-compensation
  (let [started (fixture/start! false build! (fn [_])) sys (:system started) h (:handler started)
        cat (:shipyard.catalog/db sys) lib (:shipyard.library/index sys) workspace (:shipyard.workspace/db sys)
        deps {:catalog cat :library lib :jobs (:shipyard.http/jobs sys)}
        post #(h (mock/request :post %1 %2))
        select! #(post "/orient/selection" {"visible" (pr-str [a b c]) "selected" %})
        part #(catalog/part (catalog/listing! cat) %)
        file #(first (filter (fn [f] (= % (:owner f))) (variants/files! cat)))
        bytes #(seq (java.nio.file.Files/readAllBytes (.toPath (fs/file (index/root! lib) %))))]
    (try
      (catalog/save-part-orientation! cat a [0 1 0 0])
      (catalog/save-mounts! cat a [fixture/plug])
      (let [anchor (part a) source (file b) content (bytes (:path source))]
        (testing "unsupported-only default and explicit All variants filter"
          (is (not (.contains ^String (:body (h (mock/request :get "/orient/parts"))) "Battery Supported")))
          (is (.contains ^String (:body (h (mock/request :get "/orient/parts?variant=all"))) "Battery Supported")))
        (select! [a b])
        (is (= 200 (:status (post "/parts/variants/group" {"name" "Aligned Battery"}))))
        (is (= (:part/uid anchor) (:part/uid (part a))))
        (is (= (:part/orientation anchor) (:part/orientation (part a))))
        (is (= 1 (count (:part/mounts (:part (catalog/part-context! cat a))))))
        (is (= "Aligned Battery" (:part/name (part a))))
        (is (nil? (part b)))
        (is (= content (bytes (str a "/supported.stl"))))
        (is (= (:eid source) (:eid (first (filter #(= :supported (:variant %)) (variants/files! cat))))))
        (is (empty? (edn/read-string (get-in @(:state workspace) [:workspaces :browse :bulk-selection]))))
        (catalog/reingest! cat (index/parts! lib) (index/root! lib))
        (is (= #{:unsupported :supported} (set (:part/variants (part a)))))
        (testing "split restores the original row identity and keeps the anchor authored"
          (is (= 200 (:status (post "/parts/variants/split" {"group" a}))))
          (is (= content (bytes (:path source))))
          (is (= (:eid source) (:eid (file b))))
          (is (= (:part/orientation anchor) (:part/orientation (part a)))))
        (testing "a failed shared-store publication restores every source file"
          (let [before (catalog/listing! cat)]
            (is (thrown? Exception (with-redefs [catalog/open! (fn [& _] (throw (ex-info "Injected transaction failure" {})))]
                                     (variants/edit! deps :group {:ids [a b]}))))
            (is (= before (catalog/listing! cat)))
            (is (= content (bytes (:path source))))
            (is (not (fs/exists? (fs/path (index/root! lib) a "supported.stl"))))))
        (testing "conflicts and authored-source replacements leave the library unchanged"
          (select! [a c])
          (is (= 422 (:status (post "/parts/variants/group" {}))))
          (is (= 422 (:status (post "/parts/variants/variant" {"file" (:key (file a)) "variant" "supported"}))))
          (is (= (:part/uid anchor) (:part/uid (part a))))))
      (testing "unprotected per-file variant changes survive reopening the shared database"
        (let [source (file b)]
          (is (= 200 (:status (post "/parts/variants/variant" {"file" (:key source) "variant" "unsupported-pitted"}))))
          (is (fs/regular-file? (fs/path (index/root! lib) b "unsupported-pitted.stl")))
          (let [reopened (store/open! (:directory (:store cat)))]
            (try (is (= #{:unsupported-pitted} (set (:part/variants (catalog/part (catalog/snapshot! (assoc cat :store reopened)) b)))))
                 (finally (store/close! reopened))))))
      (finally (fixture/stop! started)))))

(deftest stale-anchor-and-missing-members-block-edits
  (let [started (fixture/start! false build! (fn [_])) sys (:system started) h (:handler started)
        cat (:shipyard.catalog/db sys) lib (:shipyard.library/index sys)
        deps {:catalog cat :library lib :jobs (:shipyard.http/jobs sys)}
        before (catalog/listing! cat) source (fs/file (index/root! lib) a "unsupported.stl")]
    (try
      (spit source "changed source")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"changed" (variants/edit! deps :group {:ids [a b]})))
      (is (= before (catalog/listing! cat)))
      (is (fs/regular-file? (fs/path (index/root! lib) b "supported.stl")))
      (is (= 422 (:status (h (mock/request :post "/parts/variants/variant" {"file" "missing" "variant" "supported"})))))
      (finally (fixture/stop! started)))))

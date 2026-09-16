(ns shipyard.http.contracts
  "Malli transport contracts for Shipyard's Ring boundary.

  The application posts ordinary HTML forms and returns either rendered HTML,
  a small health JSON document, or a mesh file. Domain validation still lives
  in pure functions; these schemas reject malformed transport shapes before a
  handler runs and verify the response shapes after it returns."
  (:require [shipyard.http.urls :as urls]
            [shipyard.mount.facet-input :as facet-input]))

(def html-responses
  {200 {:body string?}
   204 {:body string?}
   400 {:body string?}
   404 {:body string?}
   409 {:body string?}
   422 {:body string?}
   500 {:body string?}})

(def health-responses
  {200 {:body string?}})

(def mesh-responses
  {200 {:body [:fn #(instance? java.io.File %)]}
   404 {:body string?}})

(def library-query
  [:map {:closed false}
   [:bundle {:optional true} string?]
   [:class {:optional true} string?]
   [:role {:optional true} string?]
   [:q {:optional true} string?]])

(def orientation-query
  [:map {:closed false}
   [:bundle {:optional true} string?]
   [:class {:optional true} string?]
   [:role {:optional true} string?]
   [:orientation {:optional true} string?]
   [:q {:optional true} string?]])

(def bulk-render-form
  [:map {:closed false}
   [:part-ids string?]])

(def bulk-save-form
  [:map {:closed false}
   [:orientations string?]
   [:request {:optional true} [:and int? [:>= 0]]]])

(def settings-form
  [:map {:closed false}
   [:root string?]])

(def facet-form
  [:map {:closed false}
   [:part-id string?]
   [:mesh-key [:fn #(boolean (and (string? %)
                                  (re-matches #"[0-9a-f]{64}" %)))]]
   [:triangle-index [:fn #(boolean (and (string? %)
                                        (re-matches #"[0-9]+" %)))]]
   [:original-mount-id {:optional true} string?]])

(def part-form
  [:map {:closed false}
   [:part-id string?]])

(def mount-form
  [:map {:closed false}
   [:part-id string?]
   [:mount-id string?]
   [:kind string?]
   [:facet-indices {:optional true} [:fn facet-input/valid-input?]]])

(def mount-id-form
  [:map {:closed false}
   [:part-id string?]
   [:mount-id string?]])

(def role-form
  [:map {:closed false}
   [:part-id string?]
   [:part-role string?]])

(def orientation-form
  [:map {:closed false}
   [:part-id string?]
   [:action string?]
   [:part-yaw-deg {:optional true} string?]
   [:part-pitch-deg {:optional true} string?]
   [:part-roll-deg {:optional true} string?]])

(def part-path
  [:map [:id string?]])

(def mesh-path
  [:map [:file [:re urls/mesh-file-re]]])

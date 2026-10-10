(ns shipyard.part-browser.variant-preview
  "Read-only source-file previews in the Part Browser workspace."
  (:require [shipyard.http.htmx :as htmx]
            [shipyard.part-browser.preparation :as preparation]
            [shipyard.part-browser.variants :as variants]
            [shipyard.part-browser.views :as views]))

(defn current! [deps key]
  (try
    (if-let [{:keys [id entry]} (variants/preview-source! deps key)]
      (let [{:keys [state mesh-key mesh-url message]} (preparation/entry! deps {:part/id id})]
        (htmx/fragment
         (views/variant-detail key entry state message)
         (if (= :ready state)
           {:headers {"HX-Trigger-After-Swap"
                      (htmx/trigger {:load-mesh {:url mesh-url :part-id id :mesh-key mesh-key
                                                 :frame true :mounts []}})}}
           {:events {:clear nil}})))
      (htmx/fragment (views/variant-detail key nil :failed "This variant is no longer in the library.")
                     {:events {:clear nil}}))
    (catch clojure.lang.ExceptionInfo error
      (htmx/fragment (views/variant-detail key nil :failed (ex-message error))
                     {:events {:clear nil}}))))

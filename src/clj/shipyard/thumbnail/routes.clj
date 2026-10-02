(ns shipyard.thumbnail.routes
  (:require [shipyard.thumbnail.cache :as cache]))

(defn- image! [{:keys [thumbnails]} {:keys [path-params]}]
  (if-let [file (cache/file! thumbnails (:key path-params))]
    {:status 200 :headers {"Content-Type" "image/png" "Cache-Control" "public, max-age=31536000, immutable"} :body file}
    {:status 404 :headers {"Content-Type" "text/plain" "Cache-Control" "no-store"} :body "Thumbnail unavailable"}))

(defn routes [deps]
  [["/thumbnail-images/:key"
    {:get {:handler (partial image! deps)
           :parameters {:path [:map [:key [:re #"[0-9a-f]{64}"]]]}
           :responses {200 {:body [:fn #(instance? java.io.File %)]} 404 {:body string?}}}}]])

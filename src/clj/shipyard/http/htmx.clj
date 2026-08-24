(ns shipyard.http.htmx
  "The server half of the htmx contract (TECHNICAL.md §7.1).

  Two things leave this process and both are decided here: the `HX-Trigger`
  header that drives the viewport island, and the cache headers that separate a
  fragment of live state from a content-addressed mesh."
  (:require [clojure.data.json :as json]
            [hiccup2.core :as h]))

(def ^:const event-prefix
  "Namespaced so a viewport listener can never collide with one of htmx's own
  events, which are all `htmx:*`."
  "shipyard")

(defn event-name [event] (str event-prefix ":" (name event)))

(def ^:const fragment-cache-control
  "A fragment is a view of live catalog state. Caching one is a lie the moment a
  mount is saved."
  "no-store")

(def ^:const immutable-cache-control
  "Safe only because the URL contains the content hash: a changed part is a
  different URL, so this can never go stale."
  "public, max-age=31536000, immutable")

(defn trigger
  "An `HX-Trigger` header value, from a map of event key -> EDN payload.

  **JSON is htmx's envelope, not our payload format.** htmx parses this header
  itself and dispatches one event per key (`handleTriggerHeader`), so the outer
  object is not ours to choose. What rides inside it is: each value is EDN, so
  keywords, sets and vectors reach the viewport as themselves rather than as a
  JSON shape re-mapped by hand on the client.

  htmx wraps a non-object value as `{value: …}` before dispatching, so the
  client reads the EDN at `event.detail.value`."
  [events]
  (json/write-str (into {} (map (fn [[event payload]] [(event-name event) (pr-str payload)]))
                        events)))

(defn html
  "Render hiccup to a string. Hiccup 2 escapes by default, which is what makes
  a part name containing `&` safe to interpolate."
  [body]
  (str (h/html body)))

(defn fragment
  "An htmx fragment response.

  `:events` becomes the `HX-Trigger` header; `:status` and `:headers` override
  the defaults."
  ([body] (fragment body nil))
  ([body {:keys [status headers events]}]
   {:status  (or status 200)
    :headers (cond-> (merge {"content-type"  "text/html; charset=utf-8"
                             "cache-control" fragment-cache-control}
                            headers)
               (seq events) (assoc "HX-Trigger" (trigger events)))
    :body    (html body)}))

(defn page
  "A whole document rather than a fragment. Same caching rule: the shell embeds
  the filter form, which is built from the catalog."
  [body]
  (-> (fragment body)
      (update :body #(str "<!DOCTYPE html>\n" %))))

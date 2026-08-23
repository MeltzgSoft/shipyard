(ns shipyard.http.urls
  "Part ids and mesh keys as URL path components (TECHNICAL.md §7).

  A `:part/id` is a library-relative folder path - `Human Navy Fleet
  Bundle/Cruiser/Hull` - and most of this library has spaces in it.

  Only the encoding side lives here. reitit already url-decodes path parameters
  on the way in (`reitit.impl/url-decode-coll`), so a second decode in a handler
  would corrupt any name containing a literal percent sign."
  (:require [clojure.string :as str])
  (:import [java.net URLEncoder]
           [java.nio.charset StandardCharsets]))

(defn- encode-segment
  "Percent-encode one path segment.

  `URLEncoder` targets form bodies, not paths: it encodes a space as `+`, which
  in a path means a literal plus. Undo that one substitution and the rest of its
  escaping is correct for a path segment."
  [s]
  (-> (URLEncoder/encode (str s) StandardCharsets/UTF_8)
      (str/replace "+" "%20")))

(defn encode-id
  "A part id as a URL path. Separators stay literal `/`, everything else is
  percent-encoded.

  Encoding the separators too - `%2F` - is the obvious alternative and does not
  work: Jetty rejects an ambiguous path separator with 400 before the request
  ever reaches a handler. A catch-all route plus literal separators is the form
  that survives the server."
  [id]
  (str/join "/" (map encode-segment (str/split (str id) #"/"))))

(defn part-url [id] (str "/part/" (encode-id id)))

(defn mesh-url
  "Content-addressed, and therefore immutable: the key is the SHA-256 of the
  source STL, so a changed part is a different URL rather than a stale cache."
  [mesh-key tier]
  (format "/mesh/%s.%d.symesh" mesh-key tier))

(def mesh-file-re
  "Only a hex digest and a small integer are ever a legal mesh path. Anchored,
  so `..` cannot reach the filesystem through this route."
  #"([0-9a-f]{64})\.(\d{1,2})\.symesh")

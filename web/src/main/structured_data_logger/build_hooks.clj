(ns structured-data-logger.build-hooks
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

(defn version-index-resources
  {:shadow.build/stage :flush}
  [state]
  (let [ts (str (System/currentTimeMillis))
        index-src (slurp "src/html/index.html")
        sw-src (slurp "src/html/sw.js")]
    (io/make-parents "public/index.html")
    (spit "public/index.html" (str/replace index-src "{ts}" ts))
    (io/make-parents "public/sw.js")
    (spit "public/sw.js" (str/replace sw-src "{ts}" ts)))
  state)

(defn version-cljs
  {:shadow.build/stage :configure}
  [state]
  (let [ts (str (java.time.Instant/now))
        content (str "(ns structured-data-logger.version)\n\n"
                     "(def build-date \"" ts "\")\n")]
    (io/make-parents "src/main/structured_data_logger/version.cljs")
    (spit "src/main/structured_data_logger/version.cljs" content))
  state)

(defn proxy-api?
  "Predicate for shadow-cljs :dev-http :proxy-predicate.
   Proxies only requests starting with /journal/api."
  [request _config]
  (let [path (or (when (instance? shadow.http.server.HttpRequest request)
                   (.getRequestPath ^shadow.http.server.HttpRequest request))
                 (:uri request)
                 "")]
    (or (= path "/journal/api")
        (str/starts-with? path "/journal/api/"))))

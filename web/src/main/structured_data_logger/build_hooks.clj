(ns structured-data-logger.build-hooks
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

(defn version-index-resources
  {:shadow.build/stage :flush}
  [state]
  (let [ts (str (System/currentTimeMillis))
        src (slurp "src/html/index.html")]
    (io/make-parents "public/index.html")
    (spit "public/index.html" (str/replace src "{ts}" ts)))
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

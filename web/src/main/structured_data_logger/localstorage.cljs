(ns structured-data-logger.localstorage
  (:require [cljs.reader :as reader]))

(defn get-item
  [k]
  (try
    (when-let [v (.getItem js/localStorage (str k))]
      (reader/read-string v))
    (catch :default _ nil)))

(defn set-item!
  [k v]
  (try
    (.setItem js/localStorage (str k) (pr-str v))
    (catch :default _ nil)))

(defn remove-item!
  [k]
  (try
    (.removeItem js/localStorage (str k))
    (catch :default _ nil)))

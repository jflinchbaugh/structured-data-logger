(ns structured-data-logger.core
  (:require [clojure.string :as str]
            [tick.core :as t]
            [sci.core :as sci]))

(defn now-iso-str
  "Returns current ISO-8601 UTC timestamp string."
  []
  (str (t/instant)))

(defn to-local-datetime-input
  "Converts an ISO timestamp to YYYY-MM-DDTHH:mm for datetime-local input."
  [ts-str]
  (try
    (let [d (if (str/blank? ts-str) (js/Date.) (js/Date. ts-str))
          local-ms (- (.getTime d) (* (.getTimezoneOffset d) 60000))]
      (-> (js/Date. local-ms) (.toISOString) (.slice 0 16)))
    (catch :default _
      (-> (js/Date. (- (js/Date.now) (* (.getTimezoneOffset (js/Date.)) 60000)))
          (.toISOString)
          (.slice 0 16)))))

(defn from-local-datetime-input
  "Converts a datetime-local input value to an ISO-8601 UTC string."
  [local-dt-str]
  (try
    (if (str/blank? local-dt-str)
      (now-iso-str)
      (.toISOString (js/Date. local-dt-str)))
    (catch :default _
      (now-iso-str))))

(defn- generate-uuid []
  (str (random-uuid)))

(defn create-entry
  "Creates a structured entry map with id, timestamp, description, and data."
  [{:keys [id timestamp description data]}]
  {:id (or id (generate-uuid))
   :timestamp (or timestamp (now-iso-str))
   :description (or description "")
   :data (or data {})})

(defn recent-keys
  "Returns all keys used across entries, ordered by most recent use."
  [entries]
  (let [sorted (sort-by :timestamp #(compare %2 %1) entries)]
    (->> sorted
         (mapcat (fn [e] (keys (:data e))))
         distinct
         vec)))

(defn common-keys
  "Returns all keys used across entries, ordered by occurrence frequency."
  [entries]
  (let [freqs (frequencies (mapcat (comp keys :data) entries))]
    (->> freqs
         (sort-by (fn [[k cnt]] [(- cnt) (str k)]))
         (map first)
         vec)))

(defn blended-keys
  "Returns a blended list of recent and frequently used keys."
  [entries]
  (let [r (recent-keys entries)
        c (common-keys entries)
        n (min (count r) (count c))
        interleaved (interleave (take n r) (take n c))]
    (vec (distinct (concat interleaved r c)))))

(defn recent-values
  "Returns values for key k ordered by most recent occurrence."
  [entries k]
  (let [sorted (sort-by :timestamp #(compare %2 %1) entries)]
    (->> sorted
         (keep (fn [e] (get-in e [:data k])))
         distinct
         vec)))

(defn common-values
  "Returns values for key k ordered by frequency of occurrence."
  [entries k]
  (let [freqs (frequencies (keep (fn [e] (get-in e [:data k])) entries))]
    (->> freqs
         (sort-by (fn [[v cnt]] [(- cnt) (str v)]))
         (map first)
         vec)))

(defn all-known-keys
  "Returns all distinct string key names across entries, ordered by recency."
  [entries]
  (mapv name (recent-keys entries)))

(defn all-known-values
  "Returns all distinct string representations of values for a key."
  [entries k]
  (let [kw (if (keyword? k) k (keyword (str/trim (str k))))]
    (mapv str (recent-values entries kw))))

(defn average
  "Computes average of a collection of numbers."
  [coll]
  (when (seq coll)
    (double (/ (reduce + coll) (count coll)))))

(defn stddev
  "Computes population standard deviation of a collection of numbers."
  [coll]
  (when (seq coll)
    (let [n (count coll)
          mean (average coll)
          variance (/ (reduce + (map #(Math/pow (- % mean) 2) coll)) n)]
      (Math/sqrt variance))))

(defn intervals
  "Computes duration in seconds between consecutive timestamped entries."
  [entries]
  (let [sorted (sort-by :timestamp entries)]
    (->> (map vector sorted (rest sorted))
         (map (fn [[a b]]
                (try
                  (let [t1 (t/instant (:timestamp a))
                        t2 (t/instant (:timestamp b))]
                    (t/seconds (t/between t1 t2)))
                  (catch :default _ 0))))
         vec)))

(defn make-sci-ctx
  "Builds a SCI context populated with data analysis helper functions."
  [context-map]
  (let [sym-bindings (into {}
                           (map (fn [[k v]] [(symbol (name k)) v]))
                           context-map)]
    (sci/init
     {:bindings (merge {'average average
                        'stddev stddev
                        'intervals intervals
                        'recent-keys recent-keys
                        'common-keys common-keys
                        'blended-keys blended-keys
                        'recent-values recent-values
                        'common-values common-values
                        'all-known-keys all-known-keys
                        'all-known-values all-known-values}
                       sym-bindings)})))

(defn eval-sci
  "Evaluates Clojure code in SCI within the given context bindings."
  [code-str context-map]
  (try
    (let [ctx (make-sci-ctx context-map)
          res (sci/eval-string* ctx code-str)]
      {:result res
       :error nil})
    (catch :default e
      {:result nil
       :error (.-message e)})))

(defn create-put-op
  "Creates a client put operation for an entry."
  [entry]
  {:client-tx-id (generate-uuid)
   :op "put"
   :entry (assoc entry :updated-at (or (:updated-at entry) (now-iso-str)))})

(defn create-delete-op
  "Creates a client delete operation for an entry id."
  [id]
  {:client-tx-id (generate-uuid)
   :op "delete"
   :id id
   :deleted-at (now-iso-str)})

(defn valid-entry?
  "Returns true if e is a valid entry map with non-blank id and timestamp."
  [e]
  (boolean
   (and (map? e)
        (string? (:id e))
        (not (str/blank? (:id e)))
        (string? (:timestamp e))
        (not (str/blank? (:timestamp e))))))

(defn apply-transaction
  "Applies a single transaction or operation to an entries vector."
  [entries tx]
  (let [clean-entries (filterv valid-entry? (or entries []))
        op-name (name (or (:op tx) "put"))]
    (case op-name
      "delete"
      (let [target-id (or (:id tx) (get-in tx [:entry :id]))]
        (if (and (string? target-id) (not (str/blank? target-id)))
          (vec (remove (fn [e] (= (:id e) target-id)) clean-entries))
          clean-entries))

      "put"
      (let [entry (:entry tx)]
        (if (valid-entry? entry)
          (let [eid (:id entry)
                existing-idx (first (keep-indexed
                                     #(when (= (:id %2) eid) %1)
                                     clean-entries))]
            (if existing-idx
              (assoc clean-entries existing-idx entry)
              (conj clean-entries entry)))
          clean-entries))

      clean-entries)))

(defn apply-transactions
  "Applies an ordered collection of transactions to an entries vector,
   and ensures the result is sorted chronologically by timestamp."
  [entries txs]
  (let [clean (filterv valid-entry? (or entries []))
        updated (reduce apply-transaction clean (or txs []))]
    (vec (sort-by :timestamp updated))))

(defn reconcile-client-state
  "Reconciles client state after receiving transactions from the server.
   Preserves pending operations added concurrently during sync."
  [{:keys [entries pending-ops in-flight-ops received-txs]}]
  (let [acked-ids (into #{} (keep :client-tx-id in-flight-ops))
        remaining-pending (vec (remove #(and (:client-tx-id %)
                                             (acked-ids (:client-tx-id %)))
                                       (or pending-ops [])))
        with-remote (apply-transactions (filterv valid-entry? (or entries []))
                                        received-txs)
        final-entries (apply-transactions with-remote remaining-pending)]
    {:entries (filterv valid-entry? final-entries)
     :pending-ops remaining-pending}))

(defn clean-server-url
  "Trims whitespace and strips trailing slashes from server URL."
  [u]
  (if (str/blank? u)
    ""
    (str/replace (str/trim (str u)) #"/+$" "")))

(def email-regex #"^[^\s@]+@[^\s@]+\.[^\s@]+$")

(defn valid-email?
  "Checks whether string s is a valid email address."
  [s]
  (boolean (and (string? s) (re-matches email-regex (str/trim s)))))


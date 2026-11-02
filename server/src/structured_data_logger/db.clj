(ns structured-data-logger.db
  (:require [datahike.api :as d]
            [buddy.hashers :as hashers]
            [clojure.edn :as edn]
            [taoensso.telemere :as tel]
            [tick.core :as t]))

(def ^:const current-schema-version 2)
(def ^:const datahike-store-id #uuid "550e8400-e29b-41d4-a716-446655440001")

(defonce conn (atom nil))

(defn datahike-config
  [path]
  (if (= path ":memory")
    {:store {:backend :memory
             :id datahike-store-id}
     :schema-flexibility :read
     :value-caps :default
     :keep-history? false}
    {:store {:backend :file
             :path (or path "/var/lib/datahike/journal")
             :id datahike-store-id}
     :schema-flexibility :read
     :value-caps :default
     :keep-history? false}))

(defn valid-entry?
  "Returns true if e is a valid entry map with non-blank id and timestamp."
  [e]
  (boolean
   (and (map? e)
        (string? (:id e))
        (not (clojure.string/blank? (:id e)))
        (string? (:timestamp e))
        (not (clojure.string/blank? (:timestamp e))))))

(defn valid-op?
  [op]
  (and (map? op)
       (let [t (name (or (:op op) "put"))]
         (case t
           "put" (valid-entry? (:entry op))
           "delete" (let [tid (or (:id op) (get-in op [:entry :id]))]
                      (and (string? tid) (not (clojure.string/blank? tid))))
           false))))

(defn entry->datahike-entity
  [entry]
  (let [base {:entry/id (:id entry)
              :entry/timestamp (:timestamp entry)
              :entry/description (or (:description entry) "")}
        data-eav (reduce-kv (fn [m k v]
                              (let [attr (keyword "entry.data" (name k))]
                                (assoc m attr v)))
                            {}
                            (:data entry))]
    (merge base data-eav)))

(defn datahike-entity->entry
  [entity]
  (let [base {:id (:entry/id entity)
              :timestamp (:entry/timestamp entity)
              :description (:entry/description entity)}
        data-map (reduce-kv (fn [m k v]
                              (if (= "entry.data" (namespace k))
                                (assoc m (keyword (name k)) v)
                                m))
                            {}
                            entity)]
    (if (seq data-map)
      (assoc base :data data-map)
      base)))

(defn- migrate-legacy-journals!
  "Migrates journals storing legacy EDN string :journal/entries to
   schema version 2 (:db.type/ref component entities)."
  [connection]
  (let [db @connection
        legacy-entries (d/q '[:find ?jid ?entries-str
                              :where [?e :journal/id ?jid]
                                     [?e :journal/entries ?entries-str]
                                     [(string? ?entries-str)]]
                            db)]
    (when (seq legacy-entries)
      (tel/log! :info
                (format "Migrating %d legacy journals to schema v2"
                        (count legacy-entries)))
      (doseq [[jid s] legacy-entries]
        (let [entries (try
                        (edn/read-string s)
                        (catch Exception _ []))
              clean (filterv valid-entry? entries)
              retract-tx [[:db/retract [:journal/id jid] :journal/entries s]]]
          (d/transact connection retract-tx)
          (d/transact connection [{:db/ident :journal/entries
                                   :db/valueType :db.type/ref
                                   :db/cardinality :db.cardinality/many
                                   :db/isComponent true}
                                  {:db/ident :entry/id
                                   :db/valueType :db.type/string
                                   :db/cardinality :db.cardinality/one
                                   :db/unique :db.unique/identity}])
          (d/transact connection
                      [{:journal/id jid
                        :journal/schema-version current-schema-version
                        :journal/entries (mapv entry->datahike-entity
                                               clean)}]))))))

(defn init-schema!
  [connection]
  (d/transact connection
              [{:db/ident :journal/id
                :db/valueType :db.type/string
                :db/cardinality :db.cardinality/one
                :db/unique :db.unique/identity}])
  (migrate-legacy-journals! connection)
  (d/transact connection
              [{:db/ident :journal/entries
                :db/valueType :db.type/ref
                :db/cardinality :db.cardinality/many
                :db/isComponent true}
               {:db/ident :entry/id
                :db/valueType :db.type/string
                :db/cardinality :db.cardinality/one
                :db/unique :db.unique/identity}]))

(defn disconnect!
  []
  (when-let [c @conn]
    (try
      (d/release c)
      (catch Exception _ nil))
    (reset! conn nil)))

(defn connect!
  ([]
   (connect! nil))
  ([path]
   (disconnect!)
   (let [cfg (datahike-config path)]
     (when-not (d/database-exists? cfg)
       (d/create-database cfg))
     (let [c (d/connect cfg)]
       (reset! conn c)
       (init-schema! c)
       c))))

(defn journal-exists?
  [id]
  (when-let [c @conn]
    (boolean (d/q '[:find ?e .
                    :in $ ?jid
                    :where [?e :journal/id ?jid]]
                  @c id))))

(defn get-journal-auth
  "Returns [login password-hash] for journal id, or nil."
  [id]
  (when-let [c @conn]
    (d/q '[:find [?login ?pw]
           :in $ ?jid
           :where [?e :journal/id ?jid]
                  [?e :journal/login ?login]
                  [?e :journal/password ?pw]]
         @c id)))

(defn register-journal!
  [id login password]
  (when-let [c @conn]
    (d/transact c [{:journal/id id
                    :journal/login login
                    :journal/password (hashers/derive password)
                    :journal/schema-version current-schema-version
                    :journal/last-tx-id 0
                    :journal/tx-log (pr-str [])
                    :journal/entries []}])
    (tel/log! :info (format "Registered '%s' for '%s'" id login))
    true))

(defn unregister-journal!
  [id]
  (when-let [c @conn]
    (d/transact c [[:db.fn/retractEntity [:journal/id id]]])
    (tel/log! :info (format "Unregistered '%s'" id))
    true))

(defn owner?
  [id login]
  (when-let [c @conn]
    (let [stored-login (d/q '[:find ?login .
                              :in $ ?jid
                              :where [?e :journal/id ?jid]
                                     [?e :journal/login ?login]]
                            @c id)]
      (= stored-login login))))

(defn get-journal-download
  "Returns {:entries [...] :last-tx-id n} for download handler."
  [id]
  (when-let [c @conn]
    (let [entity (d/pull @c
                         '[:journal/last-tx-id
                           {:journal/entries [*]}]
                         [:journal/id id])
          entries (mapv datahike-entity->entry
                        (or (:journal/entries entity) []))
          sorted-entries (vec (sort-by :timestamp entries))]
      {:entries sorted-entries
       :last-tx-id (or (:journal/last-tx-id entity) 0)})))

(defn get-journal
  "Returns full in-memory-like representation of a journal for id."
  [id]
  (when-let [c @conn]
    (let [entity (d/pull @c
                         '[:journal/id
                           :journal/login
                           :journal/password
                           :journal/schema-version
                           :journal/last-tx-id
                           :journal/tx-log
                           {:journal/entries [*]}]
                         [:journal/id id])]
      (when (:journal/id entity)
        (let [entries (mapv datahike-entity->entry
                            (or (:journal/entries entity) []))
              tx-log-raw (:journal/tx-log entity)
              tx-log (when tx-log-raw
                       (try
                         (edn/read-string tx-log-raw)
                         (catch Exception _ [])))]
          {:login (:journal/login entity)
           :password (:journal/password entity)
           :schema-version (or (:journal/schema-version entity) current-schema-version)
           :last-tx-id (or (:journal/last-tx-id entity) 0)
           :tx-log (or tx-log [])
           :entries (vec (sort-by :timestamp (filterv valid-entry? entries)))})))))

(defn- apply-op-to-entries
  [entries op]
  (let [clean-entries (filterv valid-entry? (or entries []))
        op-type (name (or (:op op) "put"))]
    (case op-type
      "delete"
      (let [target-id (or (:id op) (get-in op [:entry :id]))]
        (if (and (string? target-id) (not (clojure.string/blank? target-id)))
          (vec (remove (fn [e] (= (:id e) target-id)) clean-entries))
          clean-entries))

      "put"
      (let [entry (:entry op)]
        (if (valid-entry? entry)
          (let [eid (:id entry)
                existing-idx (first (keep-indexed
                                     (fn [idx item]
                                       (when (= (:id item) eid) idx))
                                     clean-entries))]
            (if existing-idx
              (assoc clean-entries existing-idx entry)
              (conj clean-entries entry)))
          clean-entries))

      clean-entries)))

(defn record-transactions!
  "Records new sync operations for journal-id and updates entries/tx-log."
  [journal-id new-ops]
  (when-let [c @conn]
    (let [now-str (str (t/instant))
          entity (d/pull @c
                         '[:journal/last-tx-id
                           :journal/tx-log
                           {:journal/entries [*]}]
                         [:journal/id journal-id])
          tx-log-raw (:journal/tx-log entity)
          tx-log (if tx-log-raw
                   (try
                     (edn/read-string tx-log-raw)
                     (catch Exception _ []))
                   [])
          existing-client-txs (into #{} (keep :client-tx-id tx-log))
          unseen-ops (->> (or new-ops [])
                          (filter valid-op?)
                          (remove (fn [op]
                                    (and (:client-tx-id op)
                                         (existing-client-txs
                                          (:client-tx-id op))))))
          start-tx-id (or (:journal/last-tx-id entity) 0)
          indexed-txs (map-indexed
                       (fn [idx op]
                         (let [tid (+ start-tx-id (inc idx))
                               op-type (name (or (:op op) "put"))]
                           (merge op
                                  {:tx-id tid
                                   :tx-time now-str
                                   :op op-type})))
                       unseen-ops)
          new-last-tx-id (+ start-tx-id (count unseen-ops))
          updated-log (into tx-log indexed-txs)
          current-entries (mapv datahike-entity->entry
                                (or (:journal/entries entity) []))
          updated-entries (reduce apply-op-to-entries
                                  current-entries
                                  indexed-txs)
          sorted-entries (vec (sort-by :timestamp (filterv valid-entry?
                                                           updated-entries)))]
      (d/transact c
                  [{:journal/id journal-id
                    :journal/last-tx-id new-last-tx-id
                    :journal/tx-log (pr-str updated-log)
                    :journal/entries (mapv entry->datahike-entity
                                           sorted-entries)}])
      {:last-tx-id new-last-tx-id
       :tx-log updated-log
       :entries sorted-entries})))

(defn storage-summary
  "Returns a summary map of current database state: journal count,
   entry count, and last tx IDs."
  []
  (if-let [c @conn]
    (let [db @c
          j-count (count (d/q '[:find ?j :where [?j :journal/id]] db))
          entries-count (count (d/q '[:find ?e :where [?e :entry/id]] db))]
      {:journals j-count
       :entries entries-count
       :transactions 0})
    {:journals 0 :entries 0 :transactions 0}))

(defn all-journals
  "Returns a map of {id journal-data} for all journals in database."
  []
  (if-let [c @conn]
    (let [db @c
          jids (d/q '[:find [?id ...] :where [?e :journal/id ?id]] db)]
      (into {} (map (fn [id] [id (get-journal id)]) jids)))
    {}))

(defn dump-db!
  "Dump all journal data currently in database to an EDN file or OutputStream.
   Defaults to stdout if no target is provided."
  ([]
   (dump-db! *out*))
  ([target]
   (let [content (pr-str (all-journals))]
     (if (string? target)
       (spit target content)
       (binding [*out* target]
         (println content))))))

(defn load-db!
  "Load journal data from an EDN file, string, or InputStream into database."
  [source]
  (let [raw (if (string? source)
              (let [f (java.io.File. source)]
                (if (.exists f)
                  (slurp f)
                  source))
              (slurp source))
        parsed (edn/read-string raw)]
    (when (map? parsed)
      (doseq [[jid data] parsed]
        (let [entries (filterv valid-entry? (or (:entries data) []))]
          (when-let [c @conn]
            (d/transact c
                        [{:journal/id jid
                          :journal/login (:login data)
                          :journal/password (:password data)
                          :journal/schema-version (or (:schema-version data)
                                                      current-schema-version)
                          :journal/last-tx-id (or (:last-tx-id data) 0)
                          :journal/tx-log (pr-str (or (:tx-log data) []))
                          :journal/entries (mapv entry->datahike-entity
                                                 entries)}])))))))

(comment

  (let [db (d/db @conn)
        results (d/q '[:find ?e :where [?e :journal/id]] db)
        loaded-1 (map (fn [[id]] (d/pull db '[:journal/id {:journal/entries [*]}] id)) results)
        loaded-2 (map (fn [[id]] (d/pull db '[:journal/id :journal/entries] id)) results)]
    [loaded-1 loaded-2])

  (storage-summary)



.)

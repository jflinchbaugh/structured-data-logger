(ns structured-data-logger.server
  (:gen-class)
  (:require [org.httpkit.server :as hks]
            [reitit.ring :as ring]
            [ring.middleware.defaults :as rmd]
            [ring.middleware.cors :as rmc]
            [ring.util.request :as rur]
            [buddy.auth.middleware :as buddy]
            [buddy.auth.backends :as backends]
            [buddy.hashers :as hashers]
            [datahike.api :as d]
            [taoensso.telemere :as tel]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [tick.core :as t]))

;; Configure Telemere to output logs to stderr so stdout remains clean for EDN
(tel/add-handler! :default/console (tel/handler:console {:stream :err}))

(def ^:const realm "structured-data-journal")
(def ^:const base-url "/journal")
(def email-regex #"^[^\s@]+@[^\s@]+\.[^\s@]+$")

(defn valid-email?
  [s]
  (boolean (and (string? s) (re-matches email-regex (str/trim s)))))

(defonce storage (atom {}))
(defonce server (atom nil))
(defonce db-conn (atom nil))

(defn api-response
  ([code body]
   (api-response code body "text/plain"))
  ([code body content-type]
   {:status code
    :headers {"Content-Type" content-type}
    :body body}))

(defn json-response
  [code data]
  (api-response code (json/write-str data) "application/json"))

(defn not-found
  [& _]
  (api-response 404 "Not Found"))

(defn unauthorized
  [& _]
  {:status 401
   :headers {"Content-Type" "text/plain"
             "WWW-Authenticate" (format "Basic realm=%s" realm)}
   :body "Unauthorized"})

(defn ping-handler
  [_]
  (api-response 200 "pong"))

(defn get-journal-id
  [req]
  (get-in req [:path-params :id]))

(defn register-journal!
  [id login password]
  (swap!
   storage
   assoc
   id
   {:login login
    :password (hashers/derive password)
    :last-tx-id 0
    :tx-log []
    :entries []
    :document nil})
  (tel/log! :info (format "Registered '%s' for '%s'" id login)))

(defn unregister-journal!
  [id]
  (swap! storage dissoc id)
  (tel/log! :info (format "Unregistered '%s'" id)))

(defn owner?
  [login journal]
  (= (:login journal) login))

(defn parse-json-body
  [req]
  (try
    (let [s (rur/body-string req)]
      (when-not (empty? s)
        (json/read-str s :key-fn keyword)))
    (catch Exception _ nil)))

(defn register-handler
  [req]
  (let [body (or (parse-json-body req) (:params req))
        id (:id body)
        login (:login body)
        password (:password body)
        resource (format "%s/api/document/%s" base-url id)]
    (cond
      (not (valid-email? login))
      (api-response 400 "Username must be a valid email address.")

      (get @storage id)
      (api-response 200 (format "'%s' already exists" id))

      :else
      (do
        (register-journal! id login password)
        (api-response
         200
         (format "'%s' created. Access it as '%s'." id resource))))))

(defn storage-summary
  "Returns a summary map of current storage state: journal count,
   entry count, and last tx IDs."
  [store]
  (let [j-count (count store)
        total-entries (reduce + 0 (map (fn [[_ data]]
                                         (count (:entries data)))
                                       store))
        total-txs (reduce + 0 (map (fn [[_ data]]
                                     (count (:tx-log data)))
                                   store))]
    {:journals j-count
     :entries total-entries
     :transactions total-txs}))

(defn- log-storage-summary!
  [context store]
  (let [summary (storage-summary store)]
    (tel/log! :info
              (format "%s: %d journals, %d entries, %d transactions"
                      context
                      (:journals summary)
                      (:entries summary)
                      (:transactions summary)))))

(defn download-handler
  [req]
  (if-not (:identity req)
    (not-found)
    (let [id (get-journal-id req)
          login (:identity req)
          record (get @storage id)]
      (if-let [doc (:document record)]
        (do
          (tel/log! :info
                    (format "Download document for '%s' by '%s' (%d bytes)"
                            id login (count doc)))
          (api-response 200 doc "application/json"))
        (let [entries (or (:entries record) [])
              last-tx (or (:last-tx-id record) 0)]
          (tel/log! :info
                    (format "Download journal '%s' by '%s' (%d entries, last-tx-id %d)"
                            id login (count entries) last-tx))
          (json-response 200 {:entries entries
                              :last-tx-id last-tx}))))))

(defn upload-handler
  [req]
  (if-not (:identity req)
    (not-found)
    (let [id (get-journal-id req)
          login (:identity req)
          body-str (rur/body-string req)]
      (swap! storage assoc-in [id :document] body-str)
      (tel/log! :info
                (format "Upload document for '%s' by '%s' (%d bytes)"
                        id login (count (or body-str ""))))
      (api-response 200 body-str "application/json"))))

(defn valid-entry?
  "Returns true if e is a valid entry map with non-blank id and timestamp."
  [e]
  (boolean
   (and (map? e)
        (string? (:id e))
        (not (str/blank? (:id e)))
        (string? (:timestamp e))
        (not (str/blank? (:timestamp e))))))

(defn valid-op?
  [op]
  (and (map? op)
       (let [t (name (or (:op op) "put"))]
         (case t
           "put" (valid-entry? (:entry op))
           "delete" (let [tid (or (:id op) (get-in op [:entry :id]))]
                      (and (string? tid) (not (str/blank? tid))))
           false))))

(defn- apply-op-to-entries
  [entries op]
  (let [clean-entries (filterv valid-entry? (or entries []))
        op-type (name (or (:op op) "put"))]
    (case op-type
      "delete"
      (let [target-id (or (:id op) (get-in op [:entry :id]))]
        (if (and (string? target-id) (not (str/blank? target-id)))
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

(defn- record-transactions!
  [journal-id new-ops]
  (let [now-str (str (t/instant))]
    (swap!
     storage
     (fn [store]
       (let [journal (get store journal-id)
             tx-log (or (:tx-log journal) [])
             existing-client-txs (into #{} (keep :client-tx-id tx-log))
             unseen-ops (->> (or new-ops [])
                             (filter valid-op?)
                             (remove (fn [op]
                                       (and (:client-tx-id op)
                                            (existing-client-txs
                                             (:client-tx-id op))))))
             start-tx-id (or (:last-tx-id journal) 0)
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
             clean-base (filterv valid-entry? (or (:entries journal) []))
             updated-entries (reduce apply-op-to-entries
                                     clean-base
                                     indexed-txs)
             sorted-entries (vec (sort-by :timestamp updated-entries))]
         (assoc store journal-id
                (assoc journal
                       :last-tx-id new-last-tx-id
                       :tx-log updated-log
                       :entries sorted-entries)))))
    nil))

(defn- normalize-ops
  [payload]
  (cond
    (contains? payload :operations)
    (or (:operations payload) [])

    (contains? payload :changes)
    (mapv (fn [c]
            (if (:deleted? c)
              {:op "delete" :id (:id c)}
              {:op "put" :entry c}))
          (:changes payload))

    :else []))

(defn sync-handler
  [req]
  (if-not (:identity req)
    (not-found)
    (let [id (get-journal-id req)
          payload (parse-json-body req)
          since-tx-id (or (:since-tx-id payload) 0)
          ops (normalize-ops payload)
          _ (record-transactions! id ops)
          journal (get @storage id)
          all-txs (or (:tx-log journal) [])
          filtered-txs (filterv (fn [tx]
                                 (and (> (:tx-id tx) since-tx-id)
                                      (valid-op? tx)))
                                all-txs)
          now-str (str (t/instant))]
      (json-response
       200
       {:last-tx-id (or (:last-tx-id journal) 0)
        :transactions filtered-txs
        :entries (filterv valid-entry? (or (:entries journal) []))
        :server-time now-str}))))

(defn unregister-handler
  [req]
  (let [id (get-journal-id req)
        journal (get @storage id)
        login (:identity req)]
    (if-not (and journal (owner? login journal))
      (not-found)
      (do
        (unregister-journal! id)
        (api-response 200 (format "'%s' deleted" id))))))

(defn identity-required-wrapper
  [handler]
  (fn [req]
    (if (nil? (:identity req))
      (unauthorized)
      (handler req))))

(defn authfn
  [req authdata]
  (let [login (:username authdata)
        password (:password authdata)
        id (get-journal-id req)
        existing (get @storage id)]
    (when (and existing
               (= (:login existing) login)
               (:valid (hashers/verify password (:password existing))))
      login)))

(def backend (backends/basic {:realm realm :authfn authfn}))

(defn authenticated-for-journal
  [handler]
  (buddy/wrap-authentication handler backend))

(def app
  (-> [base-url
       ["/api"
        ["/ping" ping-handler]
        ["/register" {:post register-handler}]
        ["/document/:id" {:middleware
                          [authenticated-for-journal
                           identity-required-wrapper]
                          :get download-handler
                          :post upload-handler
                          :delete unregister-handler}]
        ["/sync/:id" {:middleware
                      [authenticated-for-journal
                       identity-required-wrapper]
                      :post sync-handler}]]]
      (ring/router)
      (ring/ring-handler
       (ring/routes
        (ring/create-resource-handler {:path base-url})
        not-found))
      (rmc/wrap-cors
       :access-control-allow-origin [#".*"]
       :access-control-allow-methods [:get :put :post :delete])
      (rmd/wrap-defaults
       (assoc rmd/api-defaults :proxy true))))

(def ^:const datahike-store-id #uuid "550e8400-e29b-41d4-a716-446655440001")

(defn datahike-config
  [path]
  {:store {:backend :file
           :path (or path "/var/lib/datahike/journal")
           :id datahike-store-id}
   :schema-flexibility :read
   :value-caps :default
   :keep-history? false})

(defn disconnect-db!
  "Disconnect storage atom from database watches and releases connections."
  []
  (remove-watch storage :to-datahike)
  (when-let [conn @db-conn]
    (try
      (d/release conn)
      (catch Exception _ nil))
    (reset! db-conn nil)))

(defn- datahike-doc->journal
  [entity]
  (let [entries-raw (:journal/entries entity)
        parsed-entries (when entries-raw
                         (try
                           (edn/read-string entries-raw)
                           (catch Exception _ [])))
        tx-log-raw (:journal/tx-log entity)
        parsed-tx-log (when tx-log-raw
                        (try
                          (edn/read-string tx-log-raw)
                          (catch Exception _ [])))
        doc-raw (:journal/document entity)
        parsed-doc (when doc-raw
                     (try
                       (edn/read-string doc-raw)
                       (catch Exception _ doc-raw)))
        base {:login (:journal/login entity)
              :password (:journal/password entity)
              :last-tx-id (or (:journal/last-tx-id entity) 0)
              :tx-log (or parsed-tx-log [])
              :entries (filterv valid-entry? (or parsed-entries []))}]
    (if (some? parsed-doc)
      (assoc base :document parsed-doc)
      base)))

(defn- journal->datahike-doc
  [id data]
  (let [base {:journal/id id
              :journal/login (:login data)
              :journal/password (:password data)
              :journal/last-tx-id (or (:last-tx-id data) 0)
              :journal/tx-log (pr-str (or (:tx-log data) []))
              :journal/entries (pr-str (filterv valid-entry?
                                               (or (:entries data) [])))}]
    (if (some? (:document data))
      (assoc base :journal/document (pr-str (:document data)))
      base)))

(defn connect-datahike!
  "Connect storage atom to Datahike file-based database at path."
  ([]
   (connect-datahike! nil))
  ([path]
   (disconnect-db!)
   (let [cfg (datahike-config path)]
     (when-not (d/database-exists? cfg)
       (d/create-database cfg))
     (let [conn (d/connect cfg)]
       (reset! db-conn conn)
       (d/transact conn [{:db/ident :journal/id
                          :db/valueType :db.type/string
                          :db/cardinality :db.cardinality/one
                          :db/unique :db.unique/identity}])
       (let [db @conn
             results (d/q '[:find ?e :where [?e :journal/id]] db)
             loaded (reduce
                     (fn [store [eid]]
                       (let [entity (into {} (d/entity db eid))
                             jid (:journal/id entity)]
                         (if jid
                           (assoc store jid (datahike-doc->journal entity))
                           store)))
                     {}
                     results)]
         (reset! storage loaded)
         (log-storage-summary!
          (format "Loaded Datahike data (%s)" (or path "default"))
          @storage))))
   (add-watch
    storage
    :to-datahike
    (fn [_name _atom old-val new-val]
      (when-let [conn @db-conn]
        (let [old-keys (keys old-val)
              new-keys (keys new-val)
              removed (remove (set new-keys) old-keys)
              retractions (mapv (fn [id]
                                  [:db.fn/retractEntity [:journal/id id]])
                                removed)
              upserts (mapv (fn [[id data]]
                              (journal->datahike-doc id data))
                            new-val)
              doc-clears (into []
                               (comp
                                (filter (fn [[id data]]
                                          (and (nil? (:document data))
                                               (get-in old-val
                                                       [id :document]))))
                                (map (fn [[id _]]
                                       [:db/retract [:journal/id id]
                                        :journal/document])))
                               new-val)
              tx-ops (vec (concat retractions doc-clears upserts))]
          (when (seq tx-ops)
            (d/transact conn tx-ops))))))))

(defn dump-db!
  "Dump all journal data currently in storage atom to an EDN file or OutputStream.
   Defaults to stdout if no target is provided."
  ([]
   (dump-db! *out*))
  ([target]
   (let [content (pr-str @storage)]
     (if (string? target)
       (spit target content)
       (binding [*out* target]
         (println content))))))

(defn load-db!
  "Load journal data from an EDN file, string, or InputStream into storage.
   Replaces the contents of storage, triggering active backend watches."
  [source]
  (let [raw (if (string? source)
              (let [f (java.io.File. source)]
                (if (.exists f)
                  (slurp f)
                  source))
              (slurp source))
        parsed (edn/read-string raw)]
    (when (map? parsed)
      (reset! storage parsed))))

(defn connect-db
  "Connect storage atom to Datahike persistence at target path."
  [target]
  (connect-datahike! target))

(defn stop-server!
  []
  (disconnect-db!)
  (when-let [s @server]
    (s :timeout 100)
    (reset! server nil)))

(defn start-server!
  ([port]
   (start-server! port nil))
  ([port db-path]
   (if (nil? @server)
     (do
       (cond
         db-path
         (connect-db db-path)

         (System/getenv "DATAHIKE_PATH")
         (connect-datahike! (System/getenv "DATAHIKE_PATH"))

         (System/getenv "target")
         (connect-datahike! (System/getenv "target")))
       (reset! server (hks/run-server #'app {:port port}))
       (tel/log! :info (format "Server started on port %d" port))
       (log-storage-summary! "Server startup storage summary" @storage))
     "server already running")))

(defn -main
  [& [cmd-or-port arg1]]
  (case cmd-or-port
    "dump"
    (let [target (or arg1 (System/getenv "target")
                     (System/getenv "DATAHIKE_PATH") "/var/lib/datahike")]
      (connect-db target)
      (dump-db!)
      (disconnect-db!)
      (System/exit 0))

    "load"
    (let [target (or arg1 (System/getenv "target")
                     (System/getenv "DATAHIKE_PATH") "/var/lib/datahike")]
      (connect-db target)
      (load-db! *in*)
      (disconnect-db!)
      (System/exit 0))

    (let [p (if cmd-or-port (Integer/parseInt cmd-or-port) 6000)]
      (start-server! p arg1))))

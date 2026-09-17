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
            [xtdb.api :as xt]
            [taoensso.telemere :as tel]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [tick.core :as t]))

(def ^:const realm "structured-data-journal")
(def ^:const base-url "/journal")
(def email-regex #"^[^\s@]+@[^\s@]+\.[^\s@]+$")

(defn valid-email?
  [s]
  (boolean (and (string? s) (re-matches email-regex (str/trim s)))))

(defonce storage (atom {}))
(defonce server (atom nil))

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

(defn download-handler
  [req]
  (if-not (:identity req)
    (not-found)
    (let [id (get-journal-id req)
          record (get @storage id)]
      (if-let [doc (:document record)]
        (api-response 200 doc "application/json")
        (json-response 200 {:entries (or (:entries record) [])
                            :last-tx-id (or (:last-tx-id record) 0)})))))

(defn upload-handler
  [req]
  (if-not (:identity req)
    (not-found)
    (let [id (get-journal-id req)
          body-str (rur/body-string req)]
      (swap! storage assoc-in [id :document] body-str)
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
                                     #(when (= (:id %2) eid) %1)
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
                             (remove #(and (:client-tx-id %)
                                           (existing-client-txs
                                            (:client-tx-id %)))))
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
          filtered-txs (filterv #(and (> (:tx-id %) since-tx-id)
                                      (valid-op? %))
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

(defn connect-db
  "Connect storage atom to XTDB."
  [db-host]
  (remove-watch storage :to-xtdb)
  (let [node (xt/client {:host db-host})]
    (->>
     (xt/q node
           '(from :journals
                  [_id entries tx-log last-tx-id document login password]))
     (reduce
      (fn [store doc]
        (let [jid (or (:xt/id doc) (:_id doc))]
          (if jid
            (assoc store jid
                   (assoc (dissoc doc :xt/id :_id)
                          :entries (filterv valid-entry?
                                            (or (:entries doc) []))))
            store)))
      {})
     (reset! storage)))
  (add-watch
   storage
   :to-xtdb
   (fn [_name _atom old-val new-val]
     (let [old-keys (keys old-val)
           new-keys (keys new-val)
           removed (remove (set new-keys) old-keys)
           node (xt/client {:host db-host})]
       (xt/submit-tx
        node
        (concat
         (for [id removed]
           [:delete-docs :journals id])
         (for [doc new-val]
           [:put-docs :journals
            (merge {:xt/id (first doc)} (second doc))])))))))

(defn stop-server!
  []
  (when-let [s @server]
    (s :timeout 100)
    (reset! server nil)))

(defn start-server!
  ([port]
   (start-server! port nil))
  ([port db-host]
   (if (nil? @server)
     (do
       (when db-host (connect-db db-host))
       (reset! server (hks/run-server #'app {:port port}))
       (tel/log! :info (format "Server started on port %d" port)))
     "server already running")))

(defn -main
  [& [port db-host]]
  (let [p (if port (Integer/parseInt port) 8000)]
    (start-server! p db-host)))

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
            [tick.core :as t]))

(def ^:const realm "structured-data-logger")
(def ^:const base-url "/storage")

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

(defn get-logger-id
  [req]
  (get-in req [:path-params :id]))

(defn register-logger!
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

(defn unregister-logger!
  [id]
  (swap! storage dissoc id)
  (tel/log! :info (format "Unregistered '%s'" id)))

(defn owner?
  [login logger]
  (= (:login logger) login))

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
    (if (get @storage id)
      (api-response 200 (format "'%s' already exists" id))
      (do
        (register-logger! id login password)
        (api-response
         200
         (format "'%s' created. Access it as '%s'." id resource))))))

(defn download-handler
  [req]
  (if-not (:identity req)
    (not-found)
    (let [id (get-logger-id req)
          record (get @storage id)]
      (if-let [doc (:document record)]
        (api-response 200 doc "application/json")
        (json-response 200 {:entries (or (:entries record) [])
                            :last-tx-id (or (:last-tx-id record) 0)})))))

(defn upload-handler
  [req]
  (if-not (:identity req)
    (not-found)
    (let [id (get-logger-id req)
          body-str (rur/body-string req)]
      (swap! storage assoc-in [id :document] body-str)
      (api-response 200 body-str "application/json"))))

(defn- apply-op-to-entries
  [entries op]
  (let [op-type (name (or (:op op) "put"))]
    (case op-type
      "delete"
      (let [target-id (or (:id op) (get-in op [:entry :id]))]
        (vec (remove (fn [e] (= (:id e) target-id)) entries)))
      "put"
      (let [entry (:entry op)
            eid (:id entry)
            existing-idx (first (keep-indexed
                                 #(when (= (:id %2) eid) %1)
                                 entries))]
        (if existing-idx
          (assoc entries existing-idx entry)
          (conj entries entry)))
      entries)))

(defn- record-transactions!
  [logger-id new-ops]
  (let [now-str (str (t/instant))]
    (swap!
     storage
     (fn [store]
       (let [logger (get store logger-id)
             tx-log (or (:tx-log logger) [])
             existing-client-txs (into #{} (keep :client-tx-id tx-log))
             unseen-ops (remove #(and (:client-tx-id %)
                                      (existing-client-txs (:client-tx-id %)))
                                new-ops)
             start-tx-id (or (:last-tx-id logger) 0)
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
             updated-entries (reduce apply-op-to-entries
                                     (or (:entries logger) [])
                                     indexed-txs)
             sorted-entries (vec (sort-by :timestamp updated-entries))]
         (assoc store logger-id
                (assoc logger
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
    (let [id (get-logger-id req)
          payload (parse-json-body req)
          since-tx-id (or (:since-tx-id payload) 0)
          ops (normalize-ops payload)
          _ (record-transactions! id ops)
          logger (get @storage id)
          all-txs (or (:tx-log logger) [])
          filtered-txs (filterv #(> (:tx-id %) since-tx-id) all-txs)
          now-str (str (t/instant))]
      (json-response
       200
       {:last-tx-id (or (:last-tx-id logger) 0)
        :transactions filtered-txs
        :entries (or (:entries logger) [])
        :server-time now-str}))))

(defn unregister-handler
  [req]
  (let [id (get-logger-id req)
        logger (get @storage id)
        login (:identity req)]
    (if-not (and logger (owner? login logger))
      (not-found)
      (do
        (unregister-logger! id)
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
        id (get-logger-id req)
        existing (get @storage id)]
    (when (and existing
               (= (:login existing) login)
               (:valid (hashers/verify password (:password existing))))
      login)))

(def backend (backends/basic {:realm realm :authfn authfn}))

(defn authenticated-for-logger
  [handler]
  (buddy/wrap-authentication handler backend))

(def app
  (-> [base-url
       ["/api"
        ["/ping" ping-handler]
        ["/register" {:post register-handler}]
        ["/document/:id" {:middleware
                          [authenticated-for-logger
                           identity-required-wrapper]
                          :get download-handler
                          :post upload-handler
                          :delete unregister-handler}]
        ["/sync/:id" {:middleware
                      [authenticated-for-logger
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
           '(from :loggers
                  [_id entries tx-log last-tx-id document login password]))
     (reduce
      (fn [store doc]
        (assoc store (:xt/id doc) (dissoc doc :xt/id)))
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
           [:delete-docs :loggers id])
         (for [doc new-val]
           [:put-docs :loggers
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
       (when (empty? @storage)
         (register-logger! "demo" "demo" "demo"))
       (reset! server (hks/run-server #'app {:port port}))
       (tel/log! :info (format "Server started on port %d" port)))
     "server already running")))

(defn -main
  [& [port db-host]]
  (let [p (if port (Integer/parseInt port) 8000)]
    (start-server! p db-host)))

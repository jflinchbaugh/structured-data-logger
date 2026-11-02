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
            [taoensso.telemere :as tel]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [tick.core :as t]
            [structured-data-logger.db :as db]))

;; Configure Telemere to output logs to stderr so stdout remains clean for EDN
(tel/add-handler! :default/console (tel/handler:console {:stream :err}))

(def ^:const realm "structured-data-journal")
(def ^:const base-url "/journal")
(def email-regex #"^[^\s@]+@[^\s@]+\.[^\s@]+$")

(defn valid-email?
  [s]
  (boolean (and (string? s) (re-matches email-regex (str/trim s)))))

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

      (db/journal-exists? id)
      (api-response 200 (format "'%s' already exists" id))

      :else
      (do
        (db/register-journal! id login password)
        (api-response
         200
         (format "'%s' created. Access it as '%s'." id resource))))))

(defn- log-storage-summary!
  [context]
  (let [summary (db/storage-summary)]
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
          {:keys [entries last-tx-id]} (db/get-journal-download id)]
      (tel/log! :info
                (format "Download journal '%s' by '%s' (%d entries, last-tx-id %d)"
                        id login (count entries) (or last-tx-id 0)))
      (json-response 200 {:entries (or entries [])
                          :last-tx-id (or last-tx-id 0)}))))

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
          result (db/record-transactions! id ops)
          all-txs (or (:tx-log result) [])
          filtered-txs (filterv (fn [tx]
                                  (and (> (:tx-id tx) since-tx-id)
                                       (db/valid-op? tx)))
                                all-txs)
          now-str (str (t/instant))]
      (json-response
       200
       {:last-tx-id (or (:last-tx-id result) 0)
        :transactions filtered-txs
        :entries (or (:entries result) [])
        :server-time now-str}))))

(defn unregister-handler
  [req]
  (let [id (get-journal-id req)
        login (:identity req)]
    (if-not (and (db/journal-exists? id) (db/owner? id login))
      (not-found)
      (do
        (db/unregister-journal! id)
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
        id (get-journal-id req)]
    (when-let [[stored-login stored-pw] (db/get-journal-auth id)]
      (when (and (= stored-login login)
                 (:valid (hashers/verify password stored-pw)))
        login))))

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

(defn connect-db!
  "Connect to Datahike persistence at target path."
  [target]
  (db/connect! target))

(defn disconnect-db!
  "Disconnect from database and release connections."
  []
  (db/disconnect!))

(defn dump-db!
  "Dump all journal data currently in database to an EDN file or OutputStream.
   Defaults to stdout if no target is provided."
  ([]
   (db/dump-db! *out*))
  ([target]
   (db/dump-db! target)))

(defn load-db!
  "Load journal data from an EDN file, string, or InputStream into database."
  [source]
  (db/load-db! source))

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
         (connect-db! db-path)

         (System/getenv "DATAHIKE_PATH")
         (connect-db! (System/getenv "DATAHIKE_PATH")))
       (reset! server (hks/run-server #'app {:port port}))
       (tel/log! :info (format "Server started on port %d" port))
       (log-storage-summary! "Server startup storage summary"))
     "server already running")))

(defn -main
  [& [cmd-or-port arg1]]
  (case cmd-or-port
    "dump"
    (let [target (or arg1 (System/getenv "DATAHIKE_PATH") "/var/lib/datahike")]
      (connect-db! target)
      (dump-db!)
      (disconnect-db!)
      (System/exit 0))

    "load"
    (let [target (or arg1 (System/getenv "DATAHIKE_PATH") "/var/lib/datahike")]
      (connect-db! target)
      (load-db! *in*)
      (disconnect-db!)
      (System/exit 0))

    (let [p (if cmd-or-port (Integer/parseInt cmd-or-port) 6000)]
      (start-server! p arg1))))

(comment

  (start-server! 6000 "journal")

  (stop-server!)

  .)

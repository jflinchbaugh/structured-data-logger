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
        (json-response 200 {:entries (or (:entries record) [])})))))

(defn upload-handler
  [req]
  (if-not (:identity req)
    (not-found)
    (let [id (get-logger-id req)
          body-str (rur/body-string req)]
      (swap! storage assoc-in [id :document] body-str)
      (api-response 200 body-str "application/json"))))

(defn- merge-entries
  [existing-entries incoming-changes]
  (let [by-id (into {} (map (fn [e] [(:id e) e]) existing-entries))
        merged (reduce
                (fn [acc change]
                  (if (:deleted? change)
                    (dissoc acc (:id change))
                    (assoc acc (:id change) change)))
                by-id
                incoming-changes)]
    (vec (sort-by :timestamp (vals merged)))))

(defn sync-handler
  [req]
  (if-not (:identity req)
    (not-found)
    (let [id (get-logger-id req)
          payload (parse-json-body req)
          changes (or (:changes payload) [])
          curr-entries (get-in @storage [id :entries] [])
          updated-entries (merge-entries curr-entries changes)
          now-str (str (t/instant))]
      (swap! storage assoc-in [id :entries] updated-entries)
      (json-response
       200
       {:entries updated-entries
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
     (xt/q node '(from :loggers [_id entries document login password]))
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
       (reset! server (hks/run-server #'app {:port port}))
       (tel/log! :info (format "Server started on port %d" port)))
     "server already running")))

(defn -main
  [& [port db-host]]
  (let [p (if port (Integer/parseInt port) 8000)]
    (start-server! p db-host)))

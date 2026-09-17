(ns structured-data-logger.app
  (:require [helix.core :refer [$]]
            [taoensso.telemere :as tel]
            ["react-dom/client" :as rdom]
            ["react" :as react]
            [structured-data-logger.ui :refer [AppRoot]]))

(defonce root (rdom/createRoot (js/document.getElementById "root")))

(defn render []
  (tel/log! :info "rendering structured-data-logger")
  (.render root ($ react/StrictMode ($ AppRoot))))

(defn register-service-worker! []
  (when (exists? js/navigator.serviceWorker)
    (-> (js/navigator.serviceWorker.register "sw.js")
        (.then (fn [reg]
                 (tel/log! :info (str "ServiceWorker registered with scope: "
                                      (.-scope reg)))))
        (.catch (fn [err]
                  (tel/log! :warn (str "ServiceWorker registration failed: "
                                       err)))))))

(defn ^:export init []
  (render)
  (register-service-worker!))

(defn ^:dev/after-load reload! []
  (render))

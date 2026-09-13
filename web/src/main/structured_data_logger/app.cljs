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

(defn ^:export init []
  (render))

(defn ^:dev/after-load reload! []
  (render))

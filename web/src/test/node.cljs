(ns node
  (:require [cljs.test :as test]
            [structured-data-logger.core-test]))

(defn main []
  (test/run-all-tests #".*-test$"))

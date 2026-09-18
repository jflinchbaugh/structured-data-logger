(ns node
  (:require [cljs.test :as test]
            [structured-data-logger.core-test]
            [structured-data-logger.ui-test]))

(defn main []
  (test/run-all-tests #".*-test$"))

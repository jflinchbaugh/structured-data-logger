(ns structured-data-logger.ui-test
  (:require [cljs.test :refer [deftest is testing]]
            [structured-data-logger.ui :as ui]))

(deftest parse-val-test
  (testing "parse-val correctly handles integer, float, and string values"
    (is (= 42 (ui/parse-val "42")))
    (is (= -7 (ui/parse-val "-7")))
    (is (= 0 (ui/parse-val "0")))
    (is (= 3.14 (ui/parse-val "3.14")))
    (is (= -0.5 (ui/parse-val "-0.5")))
    (is (= "normal" (ui/parse-val "normal")))
    (is (= "120/80" (ui/parse-val "120/80")))
    (is (= "hello world" (ui/parse-val "  hello world  ")))))

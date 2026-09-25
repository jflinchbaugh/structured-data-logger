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

(deftest confirm-delete-test
  (testing "confirm-delete? prompts using window.confirm and returns its boolean result"
    (let [prompts (atom [])]
      (with-redefs [ui/confirm-dialog (fn [msg]
                                        (swap! prompts conj msg)
                                        true)]
        (is (true? (ui/confirm-delete? {:description "Blood pressure reading"})))
        (is (= ["Are you sure you want to delete this entry?"] @prompts)))
      (with-redefs [ui/confirm-dialog (fn [_] false)]
        (is (false? (ui/confirm-delete? {:description "Blood pressure reading"})))))))

(deftest format-kv-test
  (testing "format-kv displays key and value with colon when value is present"
    (is (= "food: oatmeal" (ui/format-kv :food "oatmeal")))
    (is (= "count: 42" (ui/format-kv :count 42)))
    (is (= "temp: 98.6" (ui/format-kv :temp 98.6)))
    (is (= "active: false" (ui/format-kv :active false))))
  (testing "format-kv displays only key without colon when value is empty"
    (is (= "fasting" (ui/format-kv :fasting nil)))
    (is (= "fasting" (ui/format-kv :fasting "")))
    (is (= "fasting" (ui/format-kv :fasting "   ")))
    (is (= "tag" (ui/format-kv "tag" "")))))

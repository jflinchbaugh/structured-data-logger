(ns structured-data-logger.ui-test
  (:require [cljs.test :refer [deftest is testing]]
            [structured-data-logger.core :as core]
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

(deftest starter-scripts-validation-test
  (testing "t namespace is available in SCI evaluation"
    (let [res (core/eval-sci "(t/now)" {})]
      (is (nil? (:error res)))
      (is (some? (:result res))))
    (let [res (core/eval-sci "(t/new-duration 7 :days)" {})]
      (is (nil? (:error res)))
      (is (some? (:result res))))
    (let [res (core/eval-sci "(t/- (t/now) (t/new-duration 7 :days))" {})]
      (is (nil? (:error res)))
      (is (some? (:result res))))
    (let [sample [{:timestamp (core/now-iso-str)}]]
      (let [res (core/eval-sci
                 (str "(let [inst (t/instant (:timestamp (first entries)))]"
                      "  (t/> inst (t/- (t/now) (t/new-duration 7 :days))))")
                 {:entries sample})]
        (is (nil? (:error res)))
        (is (= true (:result res))))))
  (let [sample-entries [{:id "1"
                         :timestamp (core/now-iso-str)
                         :description "Breakfast"
                         :data {:food "oatmeal"
                                :mileage 15
                                :pills "aspirin"}}
                        {:id "2"
                         :timestamp (core/now-iso-str)
                         :description "Lunch"
                         :data {:food "salad"
                                :mileage 25
                                :pills "vitamin"}}]]
    (doseq [[script-name script] ui/starter-scripts]
      (testing (str "starter script: " script-name)
        (let [res (core/eval-sci script {:entries sample-entries})]
          (is (nil? (:error res))
              (str script-name " failed with error: " (:error res)))
          (is (some? (:result res))
              (str script-name " returned nil result"))
          (when (= script-name "Recent Window (Last 7 Days)")
            (is (= 2 (count (:result res)))
                "Recent Window should match current entries")))))))

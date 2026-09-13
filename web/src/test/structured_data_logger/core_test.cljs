(ns structured-data-logger.core-test
  (:require [cljs.test :refer [deftest is testing]]
            [structured-data-logger.core :as sut]))

(deftest entry-creation-test
  (testing "create-entry produces structured entry with defaults"
    (let [e (sut/create-entry {:description "Took aspirin"
                               :data {:pills "aspirin" :qty 2}})]
      (is (string? (:id e)))
      (is (string? (:timestamp e)))
      (is (= "Took aspirin" (:description e)))
      (is (= "aspirin" (get-in e [:data :pills])))
      (is (= 2 (get-in e [:data :qty]))))))

(deftest kv-analytics-test
  (let [sample-entries
        [{:id "1"
          :timestamp "2026-09-10T08:00:00Z"
          :description "Breakfast"
          :data {:food "oatmeal" :calories 250 :mood "good"}}
         {:id "2"
          :timestamp "2026-09-11T08:00:00Z"
          :description "Lunch"
          :data {:food "salad" :calories 350}}
         {:id "3"
          :timestamp "2026-09-12T08:00:00Z"
          :description "Dinner"
          :data {:food "oatmeal" :pills "aspirin"}}]]

    (testing "recent-keys returns keys ordered by most recent use"
      (is (= [:food :pills :calories :mood]
             (sut/recent-keys sample-entries))))

    (testing "common-keys returns keys ordered by occurrence frequency"
      (is (= [:food :calories :mood :pills]
             (sut/common-keys sample-entries))))

    (testing "recent-values returns values ordered by recency for a key"
      (is (= ["oatmeal" "salad"]
             (sut/recent-values sample-entries :food))))

    (testing "common-values returns values ordered by frequency for a key"
      (is (= ["oatmeal" "salad"]
             (sut/common-values sample-entries :food))))

    (testing "all-known-keys returns string names of all recorded keys"
      (is (= ["food" "pills" "calories" "mood"]
             (sut/all-known-keys sample-entries))))

    (testing "all-known-values returns string values for key"
      (is (= ["oatmeal" "salad"]
             (sut/all-known-values sample-entries :food))))))

(deftest stats-and-sci-test
  (let [numbers [10 20 30 40 50]]
    (testing "average calculation"
      (is (= 30.0 (sut/average numbers))))

    (testing "stddev calculation"
      (let [sd (sut/stddev numbers)]
        (is (> sd 14.1))
        (is (< sd 14.2)))))

  (testing "SCI evaluation with entries binding"
    (let [entries [{:id "1" :timestamp "2026-09-10T10:00:00Z"
                    :description "A" :data {:miles 10}}
                   {:id "2" :timestamp "2026-09-10T11:00:00Z"
                    :description "B" :data {:miles 20}}]
          res (sut/eval-sci "(reduce + (map #(get-in % [:data :miles]) entries))"
                            {:entries entries})]
      (is (= 30 (:result res)))
      (is (nil? (:error res))))))

(deftest datetime-conversion-test
  (testing "to-local-datetime-input formats for HTML5 datetime-local"
    (let [formatted (sut/to-local-datetime-input "2026-09-13T12:00:00Z")]
      (is (string? formatted))
      (is (re-matches #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$" formatted))))

  (testing "from-local-datetime-input parses back to ISO UTC string"
    (let [iso-str (sut/from-local-datetime-input "2026-09-13T12:00")]
      (is (string? iso-str))
      (is (re-matches #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}.*" iso-str)))))


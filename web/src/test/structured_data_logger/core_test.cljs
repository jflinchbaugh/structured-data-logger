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
             (sut/all-known-values sample-entries :food))))

    (testing "blended-keys blends recent and frequently used keys"
      (let [blended (sut/blended-keys sample-entries)]
        (is (vector? blended))
        (is (= (set [:food :pills :calories :mood]) (set blended)))
        (is (= :food (first blended)))))))

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

(deftest sync-reconciliation-test
  (testing "apply-transactions handles puts and deletes"
    (let [e1 {:id "1" :timestamp "2026-09-10T10:00:00Z" :description "One"}
          e2 {:id "2" :timestamp "2026-09-10T11:00:00Z" :description "Two"}
          tx-put-1 {:op "put" :entry e1 :tx-id 1}
          tx-put-2 {:op "put" :entry e2 :tx-id 2}
          tx-del-1 {:op "delete" :id "1" :tx-id 3}
          res1 (sut/apply-transactions [] [tx-put-1 tx-put-2])
          res2 (sut/apply-transactions res1 [tx-del-1])]
      (is (= 2 (count res1)))
      (is (= ["1" "2"] (mapv :id res1)))
      (is (= 1 (count res2)))
      (is (= "2" (:id (first res2))))))

  (testing "reconcile-client-state retains concurrent in-flight edits"
    (let [base-entry {:id "e1" :timestamp "2026-09-10T10:00:00Z"
                      :description "Base"}
          ;; Client sent op1 for e1
          op1 {:client-tx-id "c-1" :op "put" :entry base-entry}
          ;; While sync in flight, client edited e2 locally
          e2-edit {:id "e2" :timestamp "2026-09-10T12:00:00Z"
                   :description "Created during sync"}
          op2 {:client-tx-id "c-2" :op "put" :entry e2-edit}
          ;; Server returns transaction for op1 plus a remote tx for e3
          e3-remote {:id "e3" :timestamp "2026-09-10T11:00:00Z"
                     :description "From peer device"}
          server-txs [{:tx-id 1 :client-tx-id "c-1" :op "put"
                       :entry base-entry}
                      {:tx-id 2 :client-tx-id "peer-1" :op "put"
                       :entry e3-remote}]
          reconciled (sut/reconcile-client-state
                      {:entries [base-entry e2-edit]
                       :pending-ops [op1 op2]
                       :in-flight-ops [op1]
                       :received-txs server-txs})]
      ;; op1 acknowledged, op2 still pending
      (is (= 1 (count (:pending-ops reconciled))))
      (is (= "c-2" (:client-tx-id (first (:pending-ops reconciled)))))
      ;; Entries contains base-entry, e3-remote from server, and e2-edit
      (is (= 3 (count (:entries reconciled))))
      (is (= #{"e1" "e2" "e3"}
             (set (map :id (:entries reconciled))))))))

(deftest server-url-cleaning-test
  (testing "clean-server-url normalizes trailing slashes and blank strings"
    (is (= "http://localhost:6000"
           (sut/clean-server-url "http://localhost:6000/")))
    (is (= "http://localhost:6000"
           (sut/clean-server-url "http://localhost:6000///")))
    (is (= "http://localhost:6000"
           (sut/clean-server-url "  http://localhost:6000  ")))
    (is (= "" (sut/clean-server-url "")))
    (is (= "" (sut/clean-server-url nil)))
    (is (= "" (sut/clean-server-url "   ")))))

(deftest email-validation-test
  (testing "valid-email? identifies valid and invalid email addresses"
    (is (true? (sut/valid-email? "user@example.com")))
    (is (true? (sut/valid-email? "alice.smith+tag@sub.domain.org")))
    (is (false? (sut/valid-email? "plainuser")))
    (is (false? (sut/valid-email? "user@")))
    (is (false? (sut/valid-email? "@example.com")))
    (is (false? (sut/valid-email? "user@example")))
    (is (false? (sut/valid-email? "")))
    (is (false? (sut/valid-email? nil)))))

(deftest blank-and-invalid-entry-test
  (testing "valid-entry? validates required fields"
    (is (false? (sut/valid-entry? nil)))
    (is (false? (sut/valid-entry? {})))
    (is (false? (sut/valid-entry? {:id "1"})))
    (is (false? (sut/valid-entry? {:id "1" :timestamp ""})))
    (is (false? (sut/valid-entry? {:id "" :timestamp "2026-09-17T00:00:00Z"})))
    (is (true? (sut/valid-entry? {:id "1" :timestamp "2026-09-17T00:00:00Z"}))))

  (testing "apply-transaction discards puts with nil or invalid entries"
    (is (= [] (sut/apply-transaction [] {:op "put" :entry nil})))
    (is (= [] (sut/apply-transaction [] {:op "put" :entry {}})))
    (is (= [] (sut/apply-transaction [nil {}] {:op "delete" :id nil}))))

  (testing "reconcile-client-state purges blank entries and prevents revival"
    (let [res (sut/reconcile-client-state
               {:entries [nil {}]
                :pending-ops []
                :in-flight-ops []
                :received-txs [{:op "put" :entry nil}]})]
      (is (= [] (:entries res))))))

(deftest pwa-offline-assets-test
  (testing "pwa-cache-urls generates essential asset paths for offline caching"
    (let [urls (sut/pwa-cache-urls "12345")]
      (is (vector? urls))
      (is (some #{"/"} urls))
      (is (some #{"/index.html"} urls))
      (is (some #{"/style.css?ts=12345"} urls))
      (is (some #{"/js/main.js?ts=12345"} urls))
      (is (some #{"/manifest.json"} urls))
      (is (some #{"/icon-192.png"} urls))
      (is (some #{"/icon-512.png"} urls)))))

(deftest sync-settings-dirty-test
  (testing "sync-settings-dirty? detects differences between form state and saved config"
    (let [saved {:user-id "dev-1" :username "alice@example.com" :password "secret"}]
      (is (false? (sut/sync-settings-dirty? saved saved)))
      (is (false? (sut/sync-settings-dirty? saved {:user-id "dev-1"
                                                   :username "alice@example.com"
                                                   :password "secret"})))
      (is (true? (sut/sync-settings-dirty? saved {:user-id "dev-2"
                                                  :username "alice@example.com"
                                                  :password "secret"})))
      (is (true? (sut/sync-settings-dirty? saved {:user-id "dev-1"
                                                  :username "bob@example.com"
                                                  :password "secret"})))
      (is (true? (sut/sync-settings-dirty? saved {:user-id "dev-1"
                                                  :username "alice@example.com"
                                                  :password "newsecret"})))
      (is (false? (sut/sync-settings-dirty? nil {:user-id ""
                                                 :username ""
                                                 :password ""}))))))





(ns structured-data-logger.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [structured-data-logger.db :as db]))

(deftest datahike-storage-test
  (testing "db operations persist journals and entries in Datahike"
    (let [tmp-dir (str "/tmp/datahike-test-" (java.util.UUID/randomUUID))]
      (try
        (db/connect! tmp-dir)
        (is (= {} (db/all-journals)))
        (db/register-journal! "user-dh" "dh@example.com" "password123")
        (is (db/journal-exists? "user-dh"))
        ;; Add a journal entry with structured data
        (let [sample-entry {:id "e-test-1"
                            :timestamp "2026-10-31T12:00:00Z"
                            :description "Medication"
                            :data {:pills 2 :med "aspirin"}}]
          (db/record-transactions! "user-dh" [{:op "put" :entry sample-entry}])
          ;; Disconnect and reconnect to verify persistence and structured EAV
          (db/disconnect!)
          (db/connect! tmp-dir)
          (is (db/journal-exists? "user-dh"))
          (let [j (db/get-journal "user-dh")]
            (is (= "dh@example.com" (:login j)))
            (is (= 2 (:schema-version j)))
            (is (= [sample-entry] (:entries j))))
          ;; Query Datahike natively via Datalog using structured attributes
          (let [conn @db/conn
                query-result (d/q '[:find ?desc ?pills ?med
                                    :where
                                    [?j :journal/id "user-dh"]
                                    [?j :journal/entries ?e]
                                    [?e :entry/description ?desc]
                                    [?e :entry.data/pills ?pills]
                                    [?e :entry.data/med ?med]]
                                  @conn)]
            (is (= #{["Medication" 2 "aspirin"]} query-result))))
        (db/unregister-journal! "user-dh")
        (is (not (db/journal-exists? "user-dh")))
        (db/disconnect!)
        (db/connect! tmp-dir)
        (is (not (db/journal-exists? "user-dh")))
        (finally
          (db/disconnect!)
          (try
            (let [f (java.io.File. tmp-dir)]
              (run! #(clojure.java.io/delete-file % true)
                    (reverse (file-seq f))))
            (catch Exception _ nil)))))))

(deftest legacy-migration-test
  (testing "automatically migrate legacy schema v1 journals with string entries to v2"
    (let [tmp-dir (str "/tmp/datahike-mig-" (java.util.UUID/randomUUID))]
      (try
        ;; Simulate legacy schema v1 with entries serialized as EDN string
        (let [cfg (db/datahike-config tmp-dir)]
          (d/create-database cfg)
          (let [c (d/connect cfg)]
            (d/transact c [{:db/ident :journal/id
                            :db/valueType :db.type/string
                            :db/cardinality :db.cardinality/one
                            :db/unique :db.unique/identity}])
            (d/transact c [{:journal/id "legacy-user"
                            :journal/login "legacy@example.com"
                            :journal/password "hashed123"
                            :journal/schema-version 1
                            :journal/last-tx-id 5
                            :journal/entries (pr-str [{:id "e-leg-1"
                                                       :timestamp "2026-10-31T09:00:00Z"
                                                       :description "Legacy run"
                                                       :data {:distance 5.5
                                                              :shoes "Saucony"}}])}])
            (d/release c)))
        ;; Connect database: should trigger automatic migration
        (db/connect! tmp-dir)
        (let [j (db/get-journal "legacy-user")]
          (is (= 2 (:schema-version j)))
          (is (= 1 (count (:entries j))))
          (is (= "Legacy run" (-> j :entries first :description)))
          (is (= 5.5 (-> j :entries first :data :distance)))
          (is (= "Saucony" (-> j :entries first :data :shoes))))
        ;; Verify Datahike directly supports Datalog query on migrated entity
        (let [conn @db/conn
              query-result (d/q '[:find ?desc ?dist ?shoes
                                  :where
                                  [?j :journal/id "legacy-user"]
                                  [?j :journal/entries ?e]
                                  [?e :entry/description ?desc]
                                  [?e :entry.data/distance ?dist]
                                  [?e :entry.data/shoes ?shoes]]
                                @conn)]
          (is (= #{["Legacy run" 5.5 "Saucony"]} query-result)))
        (finally
          (db/disconnect!)
          (try
            (let [f (java.io.File. tmp-dir)]
              (run! #(clojure.java.io/delete-file % true)
                    (reverse (file-seq f))))
            (catch Exception _ nil)))))))

(deftest dump-and-load-test
  (testing "dump-db and load-db roundtrip journals"
    (let [dh-dir-1 (str "/tmp/dh-dump-1-" (java.util.UUID/randomUUID))
          dh-dir-2 (str "/tmp/dh-dump-2-" (java.util.UUID/randomUUID))
          backup-file (java.io.File/createTempFile "journal-dump" ".edn")]
      (try
        ;; Setup data in DB 1
        (db/connect! dh-dir-1)
        (db/register-journal! "user-dump" "dump@example.com" "pw123")
        (db/dump-db! (.getAbsolutePath backup-file))
        (db/disconnect!)

        ;; Load data into DB 2
        (db/connect! dh-dir-2)
        (is (= {} (db/all-journals)))
        (db/load-db! (.getAbsolutePath backup-file))
        (is (db/journal-exists? "user-dump"))
        (is (= "dump@example.com" (:login (db/get-journal "user-dump"))))

        (db/disconnect!)
        ;; Verify persistence in DB 2
        (db/connect! dh-dir-2)
        (is (db/journal-exists? "user-dump"))
        (finally
          (db/disconnect!)
          (when (.exists backup-file) (.delete backup-file))
          (run! (fn [d]
                  (try
                    (let [f (java.io.File. d)]
                      (run! #(clojure.java.io/delete-file % true)
                            (reverse (file-seq f))))
                    (catch Exception _ nil)))
                [dh-dir-1 dh-dir-2]))))))

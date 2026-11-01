(ns structured-data-logger.storage-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [structured-data-logger.server :as sut]))

(deftest datahike-storage-test
  (testing "connect-datahike populates storage and syncs changes"
    (let [tmp-dir (str "/tmp/datahike-test-" (java.util.UUID/randomUUID))]
      (try
        (sut/connect-datahike! tmp-dir)
        (is (= {} @sut/storage))
        (sut/register-journal! "user-dh" "dh@example.com" "password123")
        (is (contains? @sut/storage "user-dh"))
        ;; Add a journal entry with structured data
        (let [sample-entry {:id "e-test-1"
                            :timestamp "2026-10-31T12:00:00Z"
                            :description "Medication"
                            :data {:pills 2 :med "aspirin"}}]
          (swap! sut/storage update-in ["user-dh" :entries] conj sample-entry)
          ;; Disconnect and reconnect to verify persistence and structured EAV
          (sut/disconnect-db!)
          (reset! sut/storage {})
          (sut/connect-datahike! tmp-dir)
          (is (contains? @sut/storage "user-dh"))
          (is (= "dh@example.com" (get-in @sut/storage ["user-dh" :login])))
          (is (= 2 (get-in @sut/storage ["user-dh" :schema-version])))
          (is (= [sample-entry] (get-in @sut/storage ["user-dh" :entries])))
          ;; Query Datahike natively via Datalog using structured attributes
          (let [db @@sut/db-conn
                query-result (d/q '[:find ?desc ?pills ?med
                                    :where
                                    [?j :journal/id "user-dh"]
                                    [?j :journal/entries ?e]
                                    [?e :entry/description ?desc]
                                    [?e :entry.data/pills ?pills]
                                    [?e :entry.data/med ?med]]
                                  db)]
            (is (= #{["Medication" 2 "aspirin"]} query-result))))
        (sut/unregister-journal! "user-dh")
        (is (not (contains? @sut/storage "user-dh")))
        (sut/disconnect-db!)
        (reset! sut/storage {})
        (sut/connect-datahike! tmp-dir)
        (is (not (contains? @sut/storage "user-dh")))
        (finally
          (sut/disconnect-db!)
          (reset! sut/storage {})
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
        (let [cfg (sut/datahike-config tmp-dir)]
          (d/create-database cfg)
          (let [conn (d/connect cfg)]
            (d/transact conn [{:db/ident :journal/id
                               :db/valueType :db.type/string
                               :db/cardinality :db.cardinality/one
                               :db/unique :db.unique/identity}])
            (d/transact conn [{:journal/id "legacy-user"
                               :journal/login "legacy@example.com"
                               :journal/password "hashed123"
                               :journal/schema-version 1
                               :journal/last-tx-id 5
                               :journal/entries (pr-str [{:id "e-leg-1"
                                                          :timestamp "2026-10-31T09:00:00Z"
                                                          :description "Legacy run"
                                                          :data {:distance 5.5
                                                                 :shoes "Saucony"}}])}])
            (d/release conn)))
        ;; Connect server: should trigger automatic migration
        (sut/connect-datahike! tmp-dir)
        (let [j (get @sut/storage "legacy-user")]
          (is (= 2 (:schema-version j)))
          (is (= 1 (count (:entries j))))
          (is (= "Legacy run" (-> j :entries first :description)))
          (is (= 5.5 (-> j :entries first :data :distance)))
          (is (= "Saucony" (-> j :entries first :data :shoes))))
        ;; Verify Datahike directly supports Datalog query on migrated entity
        (let [db @@sut/db-conn
              query-result (d/q '[:find ?desc ?dist ?shoes
                                  :where
                                  [?j :journal/id "legacy-user"]
                                  [?j :journal/entries ?e]
                                  [?e :entry/description ?desc]
                                  [?e :entry.data/distance ?dist]
                                  [?e :entry.data/shoes ?shoes]]
                                db)]
          (is (= #{["Legacy run" 5.5 "Saucony"]} query-result)))
        (finally
          (sut/disconnect-db!)
          (reset! sut/storage {})
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
        (sut/connect-datahike! dh-dir-1)
        (sut/register-journal! "user-dump" "dump@example.com" "pw123")
        (sut/dump-db! (.getAbsolutePath backup-file))
        (sut/disconnect-db!)

        ;; Load data into DB 2
        (reset! sut/storage {})
        (sut/connect-datahike! dh-dir-2)
        (is (= {} @sut/storage))
        (sut/load-db! (.getAbsolutePath backup-file))
        (is (contains? @sut/storage "user-dump"))
        (is (= "dump@example.com" (get-in @sut/storage ["user-dump" :login])))

        (sut/disconnect-db!)
        ;; Verify persistence in DB 2
        (reset! sut/storage {})
        (sut/connect-datahike! dh-dir-2)
        (is (contains? @sut/storage "user-dump"))
        (finally
          (sut/disconnect-db!)
          (reset! sut/storage {})
          (when (.exists backup-file) (.delete backup-file))
          (run! (fn [d]
                  (try
                    (let [f (java.io.File. d)]
                      (run! #(clojure.java.io/delete-file % true)
                            (reverse (file-seq f))))
                    (catch Exception _ nil)))
                [dh-dir-1 dh-dir-2]))))))

(ns structured-data-logger.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
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
          (is (= {:journals 1 :entries 1 :transactions 1}
                 (db/storage-summary)))
          ;; Disconnect and reconnect to verify persistence and structured EAV
          (db/disconnect!)
          (is (.exists (java.io.File. tmp-dir)))
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
          (doseq [suffix ["" "-wal" "-shm" "-journal"]]
            (try
              (io/delete-file (java.io.File. (str tmp-dir suffix)) true)
              (catch Exception _ nil))))))))

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
          (doseq [d [dh-dir-1 dh-dir-2]
                  suffix ["" "-wal" "-shm" "-journal"]]
            (try
              (io/delete-file (java.io.File. (str d suffix)) true)
              (catch Exception _ nil))))))))

(deftest datahike-config-test
  (testing "datahike-config handles nil, blank, and :memory paths with sqlite"
    (is (= :memory (get-in (db/datahike-config ":memory") [:store :backend])))
    (is (= :jdbc (get-in (db/datahike-config nil) [:store :backend])))
    (is (= "sqlite" (get-in (db/datahike-config nil) [:store :dbtype])))
    (is (= "/var/lib/datahike/journal"
           (get-in (db/datahike-config nil) [:store :dbname])))
    (is (= "/var/lib/datahike/journal"
           (get-in (db/datahike-config "") [:store :dbname])))
    (is (= "/custom/path"
           (get-in (db/datahike-config "/custom/path") [:store :dbname])))))

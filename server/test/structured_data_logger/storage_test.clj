(ns structured-data-logger.storage-test
  (:require [clojure.test :refer [deftest is testing]]
            [structured-data-logger.server :as sut]))

(deftest datahike-storage-test
  (testing "connect-datahike populates storage and syncs changes"
    (let [tmp-dir (str "/tmp/datahike-test-" (java.util.UUID/randomUUID))]
      (try
        (sut/connect-datahike! tmp-dir)
        (is (= {} @sut/storage))
        (sut/register-journal! "user-dh" "dh@example.com" "password123")
        (is (contains? @sut/storage "user-dh"))
        ;; Disconnect and reconnect to verify persistence
        (sut/disconnect-db!)
        (reset! sut/storage {})
        (sut/connect-datahike! tmp-dir)
        (is (contains? @sut/storage "user-dh"))
        (is (= "dh@example.com" (get-in @sut/storage ["user-dh" :login])))
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

(deftest parallel-storage-test
  (testing "datahike storage persist and reload document"
    (let [tmp-dir (str "/tmp/datahike-dual-" (java.util.UUID/randomUUID))]
      (try
        (sut/connect-datahike! tmp-dir)
        (sut/register-journal! "user-parallel" "par@example.com" "pw123")
        (swap! sut/storage assoc-in ["user-parallel" :document] {:theme "dark"})
        (sut/disconnect-db!)
        ;; Reconnect to verify document updated and stored
        (reset! sut/storage {})
        (sut/connect-datahike! tmp-dir)
        (is (= {:theme "dark"} (get-in @sut/storage ["user-parallel" :document])))
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
        (swap! sut/storage assoc-in ["user-dump" :document] {:key "value"})
        (sut/dump-db! (.getAbsolutePath backup-file))
        (sut/disconnect-db!)

        ;; Load data into DB 2
        (reset! sut/storage {})
        (sut/connect-datahike! dh-dir-2)
        (is (= {} @sut/storage))
        (sut/load-db! (.getAbsolutePath backup-file))
        (is (contains? @sut/storage "user-dump"))
        (is (= "dump@example.com" (get-in @sut/storage ["user-dump" :login])))
        (is (= {:key "value"} (get-in @sut/storage ["user-dump" :document])))

        (sut/disconnect-db!)
        ;; Verify persistence in DB 2
        (reset! sut/storage {})
        (sut/connect-datahike! dh-dir-2)
        (is (contains? @sut/storage "user-dump"))
        (is (= {:key "value"} (get-in @sut/storage ["user-dump" :document])))
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

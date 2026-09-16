(ns structured-data-logger.server-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [ring.mock.request :as mock]
            [structured-data-logger.server :as sut]))

(defn- reset-storage-fixture [f]
  (reset! sut/storage {})
  (f))

(use-fixtures :each reset-storage-fixture)

(deftest ping-endpoint-test
  (testing "Ping endpoint returns 200 pong"
    (let [req (mock/request :get "/storage/api/ping")
          resp (sut/app req)]
      (is (= 200 (:status resp)))
      (is (= "pong" (:body resp))))))

(deftest auth-and-registration-test
  (testing "User registration and authentication"
    (let [reg-req (-> (mock/request :post "/storage/api/register")
                      (mock/json-body {:id "alice"
                                       :login "alice"
                                       :password "secret123"}))
          reg-resp (sut/app reg-req)]
      (is (= 200 (:status reg-resp)))
      (is (re-find #"created" (:body reg-resp))))

    (testing "Unauthorized access without credentials"
      (let [req (mock/request :get "/storage/api/document/alice")
            resp (sut/app req)]
        (is (= 401 (:status resp)))))

    (testing "Unauthorized access with bad credentials"
      (let [req (-> (mock/request :get "/storage/api/document/alice")
                    (mock/header "authorization" "Basic YWxpY2U6d3Jvbmc="))
            resp (sut/app req)]
        (is (= 401 (:status resp)))))

    (testing "Authorized access with valid credentials"
      ;; Basic alice:secret123 -> YWxpY2U6c2VjcmV0MTIz
      (let [req (-> (mock/request :get "/storage/api/document/alice")
                    (mock/header "authorization" "Basic YWxpY2U6c2VjcmV0MTIz"))
            resp (sut/app req)]
        (is (= 200 (:status resp)))))))

(deftest journal-sync-endpoint-test
  (testing "Sync journal entries with structured key-value data"
    (let [_ (sut/register-logger! "bob" "bob" "password123")
          auth-header "Basic Ym9iOnBhc3N3b3JkMTIz"
          entry-1 {:id "e-1"
                   :timestamp "2026-09-13T10:00:00Z"
                   :description "Morning medication and breakfast"
                   :data {:pills "aspirin"
                          :quantity 1
                          :meal "oatmeal"}}
          entry-2 {:id "e-2"
                   :timestamp "2026-09-13T11:00:00Z"
                   :description "Trip to market"
                   :data {:mileage 14.2
                          :vehicle "truck"}}
          sync-req (-> (mock/request :post "/storage/api/sync/bob")
                       (mock/header "authorization" auth-header)
                       (mock/json-body {:changes [entry-1 entry-2]}))
          sync-resp (sut/app sync-req)
          body (json/read-str (:body sync-resp) :key-fn keyword)]
      (is (= 200 (:status sync-resp)))
      (is (= 2 (count (:entries body))))
      (is (= "Morning medication and breakfast"
             (-> body :entries first :description)))
      (is (= "aspirin"
             (-> body :entries first :data :pills))))))

(deftest robust-multi-client-sync-test
  (testing "Multi-client sync via append-only transaction log"
    (let [_ (sut/register-logger! "carol" "carol" "pass456")
          auth "Basic Y2Fyb2w6cGFzczQ1Ng=="
          entry-1 {:id "e-1"
                   :timestamp "2026-09-14T08:00:00Z"
                   :description "Morning walk"
                   :data {:distance 3.2}}
          ;; Client 1 submits put operation
          c1-req (-> (mock/request :post "/storage/api/sync/carol")
                     (mock/header "authorization" auth)
                     (mock/json-body {:since-tx-id 0
                                      :operations [{:client-tx-id "c1-op1"
                                                    :op "put"
                                                    :entry entry-1}]}))
          c1-resp (sut/app c1-req)
          c1-body (json/read-str (:body c1-resp) :key-fn keyword)]
      (is (= 200 (:status c1-resp)))
      (is (= 1 (:last-tx-id c1-body)))
      (is (= 1 (count (:transactions c1-body))))
      (is (= "put" (:op (first (:transactions c1-body)))))
      (is (= 1 (:tx-id (first (:transactions c1-body)))))

      ;; Test Idempotency: re-submitting c1-op1 does not duplicate
      (let [retry-req (-> (mock/request :post "/storage/api/sync/carol")
                          (mock/header "authorization" auth)
                          (mock/json-body {:since-tx-id 1
                                           :operations [{:client-tx-id "c1-op1"
                                                         :op "put"
                                                         :entry entry-1}]}))
            retry-body (json/read-str (:body (sut/app retry-req)) :key-fn keyword)]
        (is (= 1 (:last-tx-id retry-body)))
        (is (empty? (:transactions retry-body))))

      ;; Client 2 pulls changes since tx 0
      (let [c2-pull-req (-> (mock/request :post "/storage/api/sync/carol")
                            (mock/header "authorization" auth)
                            (mock/json-body {:since-tx-id 0
                                             :operations []}))
            c2-pull-body (json/read-str (:body (sut/app c2-pull-req))
                                        :key-fn keyword)]
        (is (= 1 (:last-tx-id c2-pull-body)))
        (is (= 1 (count (:transactions c2-pull-body))))
        (is (= "e-1" (-> c2-pull-body :transactions first :entry :id))))

      ;; Client 2 sends a delete operation
      (let [c2-del-req (-> (mock/request :post "/storage/api/sync/carol")
                           (mock/header "authorization" auth)
                           (mock/json-body {:since-tx-id 1
                                            :operations [{:client-tx-id "c2-op1"
                                                          :op "delete"
                                                          :id "e-1"}]}))
            c2-del-body (json/read-str (:body (sut/app c2-del-req))
                                       :key-fn keyword)]
        (is (= 2 (:last-tx-id c2-del-body)))
        (is (= 1 (count (:transactions c2-del-body))))
        (is (= "delete" (:op (first (:transactions c2-del-body)))))
        (is (= 2 (:tx-id (first (:transactions c2-del-body))))))

      ;; Client 1 syncs from tx 1, receives the delete transaction
      (let [c1-catchup (-> (mock/request :post "/storage/api/sync/carol")
                           (mock/header "authorization" auth)
                           (mock/json-body {:since-tx-id 1
                                            :operations []}))
            c1-catchup-body (json/read-str (:body (sut/app c1-catchup))
                                           :key-fn keyword)]
        (is (= 2 (:last-tx-id c1-catchup-body)))
        (is (= 1 (count (:transactions c1-catchup-body))))
        (is (= "delete" (:op (first (:transactions c1-catchup-body)))))
        (is (= "e-1" (:id (first (:transactions c1-catchup-body)))))))))


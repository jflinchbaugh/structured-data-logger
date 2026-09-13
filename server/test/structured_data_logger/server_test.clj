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

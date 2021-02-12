(ns test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [exchange.database :as database :refer [migratus-config]]
            [migratus.core :as migratus]
            [ring.mock.request :as mock]
            [exchange.order :as order]
            [exchange.server :as server]
            [cheshire.core :as cheshire]))

(defn clear-db [tests]
  (migratus/reset (database/migratus-config))
  (tests))

(defn init-db [tests]
  (binding [database/db {:dbtype   "postgresql"
                         :dbname   "exchange_test"
                         :user     "test"
                         :password "password"}]
    (migratus/migrate (database/migratus-config))
    (tests)))

(use-fixtures :each clear-db)
(use-fixtures :once init-db)


(deftest register
  (testing "correct registration"
    (is (= 200
           (:status (server/app (-> (mock/request :post "/api/users")
                                    (mock/json-body {:user_name "A"})))))))
  (testing "existing user name"
    (is (= 500
           (:status (server/app (-> (mock/request :post "/api/users")
                                    (mock/json-body {:user_name "A"}))))))))

(defn add-user [user-name]
  (-> (mock/request :post "/api/users")
      (mock/json-body {:user_name user-name})
      server/app
      :body slurp
      (cheshire/parse-string true)
      :token
      (->> (str "Bearer "))))

(defn get-balance [token]
  (-> (mock/request :get "/api/balance")
      (mock/header "Authorization" token)
      (mock/body {})
      server/app
      :body slurp
      (cheshire/parse-string true)
      (select-keys [:BTC :USD])))

(defn adjust-balance [token amount currency]
  (-> (mock/request :post "/api/balance")
      (mock/header "Authorization" token)
      (mock/json-body {:amount amount :currency currency})
      server/app
      :body slurp
      (cheshire/parse-string true)
      :success))

(defn add-standing-order [token quantity type limit_price webhook_url]
  (-> (mock/request :post "/api/standing_order")
      (mock/header "Authorization" token)
      (mock/json-body {:quantity quantity :type type :limit_price limit_price :webhook_url webhook_url})
      server/app
      :body slurp
      (cheshire/parse-string true)
      :order_id))

(defn get-standing-order [token order-id]
  (-> (mock/request :get (str "/api/standing_order/" order-id))
      (mock/header "Authorization" token)
      (mock/body {})
      server/app
      :body slurp
      (cheshire/parse-string true)))

(defn delete-standing-order [token order-id]
  (-> (mock/request :delete (str "/api/standing_order/" order-id))
      (mock/header "Authorization" token)
      (mock/body {})
      server/app
      :status))

(deftest balance
  (let [token (add-user "A")]
    (testing "initial balance is 0"
      (is (= {:BTC 0 :USD 0} (get-balance token))))
    (testing "valid increase"
      (is (true? (adjust-balance token 1000 "USD"))))
    (testing "valid decrease"
      (is (true? (adjust-balance token -800 "USD"))))
    (testing "invalid decrease"
      (is (false? (adjust-balance token -800 "USD"))))
    (testing "balance changed"
      (is (= {:BTC 0 :USD 200} (get-balance token))))))

(deftest standing-order-CRUD
  (let [token (add-user "A")]
    (adjust-balance token 15 "BTC")
    (let [order-1 (add-standing-order token 10 "SELL" 10000 "asd")]
      (testing "valid SELL order"
        (is (int? order-1))
        (is (= "LIVE" (::order/state (get-standing-order token order-1)))))
      (testing "not enough BTC to SELL"
        (let [order-2 (add-standing-order token 10 "SELL" 10000 "asd")]
          (is (= "CANCELLED" (::order/state (get-standing-order token order-2))))))
      (testing "delete the first order"
        (is (= 200 (delete-standing-order token order-1)))
        (is (= "CANCELLED" (::order/state (get-standing-order token order-1)))))
      (testing "can't delete twice"
        (is (= 400 (delete-standing-order token order-1))))
      (testing "valid SELL again"
        (let [order-3 (add-standing-order token 10 "SELL" 10000 "asd")]
          (is (= "LIVE" (::order/state (get-standing-order token order-3)))))))
    (adjust-balance token 10000 "USD")
    (testing "valid BUY order"
      (let [order-4 (add-standing-order token 10 "BUY" 1000 "asd")]
        (is (= "LIVE" (::order/state (get-standing-order token order-4))))))
    (testing "not enough USD to buy"
      (let [order-5 (add-standing-order token 1 "BUY" 1 "asd")]
        (is (= "CANCELLED" (::order/state (get-standing-order token order-5))))))))

(deftest standing-order-matching
  (let [token-a (add-user "A")
        token-b (add-user "B")]
    (adjust-balance token-a 7 "BTC")
    (adjust-balance token-b 120001 "USD")
    (let [order-1 (add-standing-order token-a 3 "SELL" 10000 "asd")
          order-2 (add-standing-order token-b 1 "BUY" 1 "asd")
          order-3 (add-standing-order token-b 6 "BUY" 20000 "asd")]
      (testing "order states after first match"
        (let [{::order/keys [state amount usd-amount avg_price]} (get-standing-order token-a order-1)]
          (is (= "FULFILLED" state))
          (is (= 0 amount))
          (is (= 30000 usd-amount))
          (is (= 10000 avg_price)))
        (is (= 0 (::order/usd-amount (get-standing-order token-b order-2))))
        (let [{::order/keys [state amount usd-amount avg_price]} (get-standing-order token-b order-3)]
          (is (= "LIVE" state))
          (is (= 3 amount))
          (is (= 30000 usd-amount))
          (is (= 10000 avg_price))))
      (testing "balances after first match"
        (is (= {:BTC 4 :USD 30000} (get-balance token-a)))
        (is (= {:BTC 3 :USD 90001} (get-balance token-b))))
      (let [order-4 (add-standing-order token-a 4 "SELL" 15000 "asd")]
        (testing "order states after second match"
          (let [{::order/keys [state amount usd-amount avg_price]} (get-standing-order token-b order-3)]
            (is (= "FULFILLED" state))
            (is (= 0 amount))
            (is (= 90000 usd-amount))
            (is (= 15000 avg_price)))
          (let [{::order/keys [state amount usd-amount avg_price]} (get-standing-order token-a order-4)]
            (is (= "LIVE" state))
            (is (= 1 amount))
            (is (= 60000 usd-amount))
            (is (= 20000 avg_price))))
        (testing "balances after second match"
          (is (= {:BTC 1 :USD 90000} (get-balance token-a)))
          (is (= {:BTC 6 :USD 30001} (get-balance token-b))))))))

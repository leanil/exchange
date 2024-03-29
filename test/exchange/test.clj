(ns exchange.test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [exchange.database :as database :refer [migratus-config]]
            [migratus.core :as migratus]
            [ring.mock.request :as mock]
            [exchange.order :as order]
            [exchange.server :as server]
            [exchange.helper :refer :all]))

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
    (let [order-1 (add-standing-order token 10 "SELL" 10000 webhook)]
      (testing "valid SELL order"
        (is (int? order-1))
        (is (= "LIVE" (::order/state (get-standing-order token order-1)))))
      (testing "not enough BTC to SELL"
        (let [order-2 (add-standing-order token 10 "SELL" 10000 webhook)]
          (is (= "CANCELLED" (::order/state (get-standing-order token order-2))))))
      (testing "delete the first order"
        (is (= 200 (delete-standing-order token order-1)))
        (is (= "CANCELLED" (::order/state (get-standing-order token order-1)))))
      (testing "can't delete twice"
        (is (= 400 (delete-standing-order token order-1))))
      (testing "valid SELL again"
        (let [order-3 (add-standing-order token 10 "SELL" 10000 webhook)]
          (is (= "LIVE" (::order/state (get-standing-order token order-3)))))))
    (adjust-balance token 10000 "USD")
    (testing "valid BUY order"
      (let [order-4 (add-standing-order token 10 "BUY" 1000 webhook)]
        (is (= "LIVE" (::order/state (get-standing-order token order-4))))))
    (testing "not enough USD to buy"
      (let [order-5 (add-standing-order token 1 "BUY" 1 webhook)]
        (is (= "CANCELLED" (::order/state (get-standing-order token order-5))))))))

(deftest standing-order-matching
  (let [token-a (add-user "A")
        token-b (add-user "B")]
    (adjust-balance token-a 7 "BTC")
    (adjust-balance token-b 120001 "USD")
    (let [order-1 (add-standing-order token-a 3 "SELL" 10000 webhook)
          order-2 (add-standing-order token-b 1 "BUY" 1 webhook)
          order-3 (add-standing-order token-b 6 "BUY" 20000 webhook)]
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
      (let [order-4 (add-standing-order token-a 4 "SELL" 15000 webhook)]
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

(deftest market-order-matching
  (let [token-a (add-user "A")
        token-b (add-user "B")]
    (adjust-balance token-a 6 "BTC")
    (adjust-balance token-a 3000 "USD")
    (adjust-balance token-b 90000 "USD")
    (let [order-1 (add-standing-order token-a 4 "SELL" 20000 webhook)
          order-2 (add-standing-order token-a 2 "SELL" 10000 webhook)
          order-3 (add-standing-order token-a 3 "BUY" 1000 webhook)]
      (testing "market order limited on amount"
        (is (= {:quantity 1 :avg_price 10000} (add-market-order token-b 1 "BUY")))
        (let [{::order/keys [state amount usd-amount]} (get-standing-order token-a order-2)]
          (is (= "LIVE" state))
          (is (= 1 amount))
          (is (= 10000 usd-amount)))
        (is (= {:BTC 5 :USD 13000} (get-balance token-a)))
        (is (= {:BTC 1 :USD 80000} (get-balance token-b))))
      (testing "market order limited on balance"
        (is (= {:quantity 4 :avg_price 17500} (add-market-order token-b 5 "BUY")))
        (let [{::order/keys [state usd-amount]} (get-standing-order token-a order-2)]
          (is (= "FULFILLED" state))
          (is (= 20000 usd-amount)))
        (let [{::order/keys [state amount usd-amount]} (get-standing-order token-a order-1)]
          (is (= "LIVE" state))
          (is (= 1 amount))
          (is (= 60000 usd-amount)))
        (is (= {:BTC 1 :USD 83000} (get-balance token-a)))
        (is (= {:BTC 5 :USD 10000} (get-balance token-b))))
      (testing "market order limited on market"
        (is (= {:quantity 3 :avg_price 1000} (add-market-order token-b 10 "SELL")))
        (let [{::order/keys [state usd-amount]} (get-standing-order token-a order-3)]
          (is (= "FULFILLED" state))
          (is (= 3000 usd-amount)))
        (is (= {:BTC 4 :USD 80000} (get-balance token-a)))
        (is (= {:BTC 2 :USD 13000} (get-balance token-b)))))))

(deftest example-scenario
  (let [token-a (add-user "A")
        token-b (add-user "B")
        token-c (add-user "C")
        token-d (add-user "D")]
    (adjust-balance token-a 1 "BTC")
    (adjust-balance token-b 10 "BTC")
    (adjust-balance token-c 250000 "USD")
    (adjust-balance token-d 300000 "USD")
    (let [order-1 (add-standing-order token-a 10 "SELL" 10000 webhook)]
      (testing "order 1 CANCELLED"
        (is (= "CANCELLED" (::order/state (get-standing-order token-a order-1))))))
    (adjust-balance token-a 9 "BTC")
    (let [order-2 (add-standing-order token-a 10 "SELL" 10000 webhook)
          order-3 (add-standing-order token-b 10 "SELL" 20000 webhook)]
      (testing "market order C"
        (is (= {:quantity 15 :avg_price 13333} (add-market-order token-c 15 "BUY"))))
      (testing "C balance"
        (is (= {:BTC 15 :USD 50000} (get-balance token-c))))
      (testing "order 2 state"
        (let [{::order/keys [state amount original-amount avg_price]} (get-standing-order token-a order-2)]
          (is (= "FULFILLED" state))
          (is (= 10000 avg_price))
          (is (= 10 (- original-amount amount)))
          (is (= 0 amount))))
      (testing "order 3 state"
        (let [{::order/keys [state amount original-amount avg_price]} (get-standing-order token-b order-3)]
          (is (= "LIVE" state))
          (is (= 20000 avg_price))
          (is (= 5 (- original-amount amount)))
          (is (= 5 amount))))
      (let [order-4 (add-standing-order token-d 20 "BUY" 10000 webhook)]
        (testing "order 4 LIVE"
          (is (= "LIVE" (::order/state (get-standing-order token-d order-4)))))
        (let [order-5 (add-standing-order token-d 10 "BUY" 25000 webhook)]
          (testing "order 5 CANCELLED"
            (is (= "CANCELLED" (::order/state (get-standing-order token-d order-5))))))
        (testing "order 4 CANCELLED"
          (is (= 200 (delete-standing-order token-d order-4)))
          (is (= "CANCELLED" (::order/state (get-standing-order token-d order-4))))))
      (let [order-6 (add-standing-order token-d 10 "BUY" 25000 webhook)]
        (testing "order 3 state"
          (let [{::order/keys [state avg_price]} (get-standing-order token-b order-3)]
            (is (= "FULFILLED" state))
            (is (= 20000 avg_price))))
        (testing "order 6 state"
          (let [{::order/keys [state amount avg_price]} (get-standing-order token-d order-6)]
            (is (= "LIVE" state))
            (is (= 20000 avg_price))
            (is (= 5 amount))))))))

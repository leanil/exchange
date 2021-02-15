(ns exchange.performance-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [migratus.core :as migratus]
            [exchange.database :as database]
            [exchange.helper :refer :all]))

(defn init-db [tests]
  (binding [database/db {:dbtype   "postgresql"
                         :dbname   "exchange_test"
                         :user     "test"
                         :password "password"}]
    (migratus/migrate (database/migratus-config))
    (tests)))

(defn clear-db [tests]
  (migratus/reset (database/migratus-config))
  (tests))

(use-fixtures :once init-db)
(use-fixtures :each clear-db)

(defn execute-random-trades [n]
  (let [token-a (add-user "A")
        token-b (add-user "B")]
    (adjust-balance token-a 1000000 "BTC")
    (adjust-balance token-a 10000000000 "USD")
    (adjust-balance token-b 1000000 "BTC")
    (adjust-balance token-b 10000000000 "USD")
    (time
      (dotimes [_ n]
        (add-standing-order (rand-nth [token-a token-b])
                            (+ (rand-int 10) 1)
                            (rand-nth ["SELL" "BUY"])
                            (+ (rand-int 10001) 5000)
                            webhook)))
    (println "# of open orders:" (database/get-live-order-count))))

(deftest random-trades-100
  (testing "100 random trades"
    (execute-random-trades 100)))

(deftest random-trades-500
  (testing "500 random trades"
    (execute-random-trades 500)))

(deftest random-trades-1000
  (testing "1000 random trades"
    (execute-random-trades 1000)))

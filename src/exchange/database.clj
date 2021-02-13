(ns exchange.database
  (:require
    [cheshire.core :as cheshire]
    [clj-http.client :as client]
    [clojure.set :as set]
    [next.jdbc :as jdbc]
    [next.jdbc.sql :as sql]
    [next.jdbc.types :refer [as-other]]
    [clojure.string :as str])
  (:import (java.util UUID)))

(def ^:dynamic db {:dbtype   "postgresql"
                   :dbname   "exchange"
                   :user     "test"
                   :password "password"})

(defn ds [] (jdbc/get-datasource db))

(defn migratus-config [] {:store                :database
                          :migration-dir        "migrations/"
                          :migration-table-name "migrations"
                          :db                   {:datasource (ds)}})

;TODO: Return 400 with meaningful message on duplicate user name.
(defn add-user [user-name]
  (let [token (.toString (UUID/randomUUID))]
    (sql/insert! (ds) "exchange_user" {:user_name user-name :token token})
    token))

(defn get-user-by-token [token]
  (let [token-value (second (re-matches #"Bearer (\S+)" token))]
    (:exchange_user/id (jdbc/execute-one! (ds) ["SELECT id FROM exchange_user WHERE token = ?" token-value]))))

(defn list-user []
  (with-open [conn (jdbc/get-connection (ds))]
    (-> (jdbc/prepare conn ["SELECT * FROM exchange_user"])
        (jdbc/execute!))))

(defn get-balance
  ([user] (get-balance (ds) user))
  ([connection user]
   (-> (jdbc/execute-one! connection ["SELECT usd, btc FROM exchange_user WHERE id = ?" user])
       (set/rename-keys {:exchange_user/usd :USD, :exchange_user/btc :BTC}))))

(defn set-balance [conn user balance]
  (jdbc/execute-one! conn ["UPDATE exchange_user SET usd = ?, btc = ? WHERE id = ?" (:USD balance) (:BTC balance) user]))

(defn adjust-balance [connection user {btc :BTC usd :USD}]
  ;(println "adjust-balance" user btc usd)
  (let [balance (-> (get-balance connection user)
                    (update :BTC + btc)
                    (update :USD + usd))]
    (if (every? nat-int? (vals balance))
      (do (set-balance connection user balance) true)
      false)))

(defn topup-user [user amount currency]
  (with-open [connection (jdbc/get-connection (ds))]
    (adjust-balance connection user (update {:BTC 0 :USD 0} currency + amount))))

(defn get-btc-rate []
  (-> (client/get "https://pro-api.coinmarketcap.com/v1/cryptocurrency/quotes/latest"
                  {:headers      {:X-CMC_PRO_API_KEY (System/getenv "COINMARKETCAP_API_KEY")}
                   :accept       :json
                   :query-params {:symbol "BTC", :convert "USD"}})
      :body
      (cheshire/parse-string true)
      (get-in [:data :BTC :quote :USD :price])))

(defn get-extended-balance [user]
  (let [{:keys [BTC USD] :as balance} (get-balance user)]
    (assoc balance :USD_equivalent (+ USD (* (get-btc-rate) BTC)))))

(defn order->sql [order]
  (->> (reduce #(update %1 %2 (comp as-other name)) order [:exchange.order/state :exchange.order/type])
       (into {} (map #(update-in % [0] (fn [key] (-> key name (str/replace \- \_) keyword)))))))

(defn sql->order [sql]
  (->> (reduce #(update %1 %2 keyword) sql [:exchange_order/state :exchange_order/type])
       (into {} (map #(update-in % [0] (fn [key] (keyword "exchange.order" (-> key name (str/replace \_ \-)))))))))

(defn create-order [order connection]
  ;(println "create-order" order)
  (:exchange_order/id (sql/insert! connection "exchange_order" (order->sql order))))

(defn get-order [order-id user]
  (-> (jdbc/execute-one! (ds) ["SELECT * FROM exchange_order WHERE id = ? AND user_id = ?" order-id user])
      sql->order))

(defn delete-order [order-id user]
  (-> (jdbc/execute-one!
        (ds)
        ["UPDATE exchange_order SET state = 'CANCELLED' WHERE id = ? AND user_id = ? AND state = 'LIVE'" order-id user])
      ::jdbc/update-count (= 1)))

(defn update-order [order connection]
  ;(println "update-order" order)
  (let [transformed (order->sql order)]
    (sql/update! connection "exchange_order" transformed (select-keys transformed [:id]))))

(defn get-live-orders-of-user [connection user order-type]
  ;(println "get-live-orders-of-user" user order-type)
  (->>
    ["SELECT * FROM exchange_order WHERE user_id = ? AND state = 'LIVE' AND type = ?::order_type"
     user (name order-type)]
    (jdbc/execute! connection)
    (map sql->order)))

(defn get-orders-query [order-type is-price-limited]
  (format "SELECT * FROM exchange_order
           WHERE user_id <> ? AND state = 'LIVE' AND type <> ?::order_type %s ORDER BY price %s"
          (if is-price-limited (format "AND price %s ? " (order-type {:SELL ">=" :BUY "<="})) "")
          (order-type {:SELL "DESC" :BUY "ASC"})))

(defn get-live-orders-of-others
  ([connection user order-type]
   (->> [(get-orders-query order-type false) user (name order-type)]
        (jdbc/execute! connection)
        (map sql->order)))
  ([connection user order-type price]
   (->> [(get-orders-query order-type true) user (name order-type) price]
        (jdbc/execute! connection)
        (map sql->order))))
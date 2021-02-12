(ns exchange.database
  (:require
    [cheshire.core :as cheshire]
    [clj-http.client :as client]
    [clojure.set :as set]
    [next.jdbc :as jdbc]
    [next.jdbc.sql :as sql]
    [next.jdbc.types :refer [as-other]])
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

(defn adjust-balance [connection user btc usd]
  ;(println "adjust-balance" user btc usd)
  (let [balance (-> (get-balance connection user)
                    (update :BTC + btc)
                    (update :USD + usd))]
    (if (every? nat-int? (vals balance))
      (do (set-balance connection user balance) true)
      false)))

(defn topup-user [user amount currency]
  (with-open [connection (jdbc/get-connection (ds))]
    (case currency
      :BTC (adjust-balance connection user amount 0)
      :USD (adjust-balance connection user 0 amount))))

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

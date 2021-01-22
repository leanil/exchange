(ns exchange.database
  (:require
    [clojure.set :as set]
    [next.jdbc :as jdbc])
  (:import (java.util UUID)))

(def datasource
  (jdbc/get-datasource {:dbtype   "postgresql"
                        :dbname   "exchange"
                        :user     "test"
                        :password "password"}))
(def migratus-config {:store                :database
                      :migration-dir        "migrations/"
                      :migration-table-name "migrations"
                      :db                   {:datasource datasource}})

(defn add-user [user-name]
  (jdbc/execute-one! datasource ["INSERT INTO users (user_name, token) VALUES (?,?)"
                                 user-name
                                 (.toString (UUID/randomUUID))]))

(defn get-user-by-token [token]
  (let [token-value (second (re-matches #"Bearer (\S+)" token))]
    (:users/id (jdbc/execute-one! datasource ["SELECT id FROM users WHERE token = ?" token-value]))))

(defn list-user []
  (with-open [conn (jdbc/get-connection datasource)]
    (-> (jdbc/prepare conn ["SELECT * FROM users"])
        (jdbc/execute!))))

(def cents {:USD 100, :BTC 100000000})

; Cannot disambiguate overloads of java.lang.Math.round: (Math/round ^float a) (Math/round ^double a)
; TODO Should I type hint? How?
(defn round-to-digits [value digits] (let [shift (Math/pow 10.0 digits)] (-> value (* shift) Math/round (/ shift))))

(defn ->cents [amount currency] (-> cents currency (* amount) Math/round))

(defn <-cents [amount currency] (-> cents currency / (* amount) (round-to-digits 2)))

(defn get-balance-cents
  ([user] (get-balance-cents datasource user))
  ([connection user]
   (-> (jdbc/execute-one! connection ["SELECT usd, btc FROM users WHERE id = ?" user])
       (set/rename-keys {:users/usd :USD, :users/btc :BTC}))))

(defn set-balance-cents [conn user balance]
  (jdbc/execute-one! conn ["UPDATE users SET usd = ?, btc = ? WHERE id = ?" (:USD balance) (:BTC balance) user]))

(defn topup-user [user amount currency]
  (with-open [conn (jdbc/get-connection datasource)]

    (let [cent-amount (->cents amount currency)
          balance (-> (get-balance-cents conn user)
                      (update currency + cent-amount))]
      (if (every? nat-int? (vals balance))
        (do (set-balance-cents conn user balance) true)
        false))))
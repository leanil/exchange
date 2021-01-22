(ns exchange.database
  (:require
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

(ns exchange.database
  (:require
    [next.jdbc :as jdbc]
    [migratus.core :as migratus]
    [next.jdbc.prepare :as p]))

(def datasource
  (jdbc/get-datasource {:dbtype   "postgresql"
                        :dbname   "exchange"
                        :user     "exchange_user"
                        :password "exchange_password"}))
(def migratus-config {:store                :database
                      :migration-dir        "migrations/"
                      :init-script          "init.sql"
                      ;:init-in-transaction? false
                      :migration-table-name "migrations"
                      :db                   {:datasource datasource}})

(defn init-db []
  (migratus/init migratus-config)
  (migratus/migrate migratus-config))

(defn add-user [email password]
  (with-open [conn (jdbc/get-connection datasource)]
    (-> (jdbc/prepare conn ["INSERT INTO users (email, password) VALUES (?,?)"])
        (p/set-parameters [email (str "__hash__TODO__" password)])
        (jdbc/execute!))))

(defn list-user []
  (with-open [conn (jdbc/get-connection datasource)]
    (-> (jdbc/prepare conn ["SELECT * FROM users"])
        (jdbc/execute!))))

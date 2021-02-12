(ns exchange.core
  (:require
    [taoensso.timbre :as log]
    [exchange.database :as database]
    [exchange.server :as server]
    [migratus.core :as migratus]))

(defn -main []
  (log/info "starting")
  (log/info "preparing database")
  (migratus/migrate (database/migratus-config))
  (log/info "starting webserver")
  (server/start))

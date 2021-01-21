(ns exchange.core
  (:require
    [taoensso.timbre :as log]
    [exchange.database :as database]
    [exchange.server :as server]))

(defn -main []
  (log/info "starting")
  (log/info "preparing database")
  (database/init-db)
  (log/info "starting webserver")
  (server/start))

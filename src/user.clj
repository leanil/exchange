(ns user
  (:require
    [exchange.core]
    [migratus.core :as migratus]
    [exchange.database :as database]))

;Helper functions for REPL development

(defn create-new-migration [name]
  ; call from REPL, it creates named migration up and down script in resources/migrations, write your SQL there
  (migratus/create database/migratus-config name))

(defn start-app []
  (exchange.core/-main))
(ns exchange.server
  (:require [reitit.ring :as ring]
            [reitit.coercion.spec]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [reitit.ring.coercion :as coercion]
            [reitit.dev.pretty :as pretty]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.multipart :as multipart]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.reload :refer [wrap-reload]]
            [muuntaja.core :as m]
            [taoensso.timbre :as log]
            [ring.util.response :refer [response]]
            [exchange.database :as database]
            [ring.logger.timbre :refer [wrap-with-logger wrap-with-body-logger]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace-log]])
  )

; based on https://github.com/metosin/reitit/blob/master/examples/ring-swagger/src/example/server.clj

(def app
  (ring/ring-handler
    (ring/router
      [["/swagger.json"
        {:get {:no-doc  true
               :swagger {:info {:title       "clojure playground exchange"
                                :description "with reitit-ring"}}
               :handler (swagger/create-swagger-handler)}}]
       ["/api"
        {:swagger {:tags ["api"]}}
        ["/users"
         {:get  {:summary    "list all users"
                 :parameters {}
                 :handler    (fn [_]
                               (let [users (database/list-user)]
                                 (response {:users users})))}
          :post {:summary    "create user"
                 :parameters {:body {:email    string?
                                     :password string?}}
                 :handler    (fn [{{{:keys [email password]} :body} :parameters}]
                               (database/add-user email password)
                               (response nil))}}]]]
      {;;:reitit.middleware/transform dev/print-request-diffs ;; pretty diffs
       ;;:validate spec/validate ;; enable spec validation for route data
       ;;:reitit.spec/wrap spell/closed ;; strict top-level validation
       :exception pretty/exception
       :data      {:coercion   reitit.coercion.spec/coercion
                   :muuntaja   m/instance
                   :middleware [#(wrap-with-logger % {:exceptions false})
                                wrap-with-body-logger
                                swagger/swagger-feature
                                parameters/parameters-middleware
                                muuntaja/format-negotiate-middleware
                                muuntaja/format-response-middleware
                                exception/exception-middleware
                                muuntaja/format-request-middleware
                                coercion/coerce-response-middleware
                                coercion/coerce-request-middleware
                                multipart/multipart-middleware
                                wrap-stacktrace-log]}})
    (ring/routes
      (swagger-ui/create-swagger-ui-handler
        {:path   "/"
         :config {:validatorUrl     nil
                  :operationsSorter "alpha"}})
      (ring/create-default-handler))))

(defn start []
  (jetty/run-jetty
    (wrap-reload #'app)
    {:port 3000, :join? false})
  (log/info "server running in port 3000"))
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
            [ring.util.response :refer [response status]]
            [exchange.database :as database]
            [ring.logger.timbre :refer [wrap-with-logger wrap-with-body-logger]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace-log]]
            [exchange.order :as order])
  )

; based on https://github.com/metosin/reitit/blob/master/examples/ring-swagger/src/example/server.clj

(defn add-user [handler]
  (fn [request]
    (if-let [token (get-in request [:headers "authorization"])]
      (if-let [user (database/get-user-by-token token)]
        (handler (update request :user (constantly user)))
        (-> (response "Invalid token") (status 401)))
      (-> (response "Missing token") (status 401)))))

; TODO figure out how to coerce to keyword (with this, the parameter is still string)
(defn currency? [currency] (#{:USD :BTC} (keyword currency)))

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
         {:get  {:middleware [add-user]
                 :summary    "list all users"
                 :parameters {}
                 :handler    (fn [_]
                               (let [users (database/list-user)]
                                 (response {:users users})))}
          :post {:summary    "create user"
                 :parameters {:body {:user_name string?}}
                 :responses  {200 {:body {:token string?}}}
                 :handler    (fn [{{{:keys [user_name]} :body} :parameters}]
                               (response {:token (database/add-user user_name)}))}}]
        ["/balance"
         {:middleware [add-user]
          :get        {:handler (fn [{user :user}] (response (database/get-extended-balance user)))}
          :post       {:parameters {:body {:amount int? :currency currency?}}
                       :handler    (fn [{{{:keys [amount currency]} :body} :parameters user :user}]
                                     (response {:success (database/topup-user user amount (keyword currency))}))}}]
        ["/standing_order"
         {:middleware [add-user]
          :post       {:parameters {:body {:quantity int? :type keyword? :limit_price int? :webhook_url string?}}
                       :handler    (fn [{{{:keys [quantity type limit_price webhook_url]} :body} :parameters user :user}]
                                     (response {:order_id (order/add-standing-order user quantity (keyword type) limit_price webhook_url)}))}}]
        ["/standing_order/:id"
         {:middleware [add-user]
          :get        {:parameters {:path {:id int?}}       ;TODO: Shouldn't this spec mean an automatic cast to int? It works with body params...
                       :handler    (fn [{{order-id :id} :path-params user :user}]
                                     (response (order/get-standing-order user (Integer/parseInt order-id))))}
          :delete     {:parameters {:path {:id int?}}
                       :handler    (fn [{{order-id :id} :path-params user :user}]
                                     (-> (database/delete-order (Integer/parseInt order-id) user)
                                         (if 200 400)
                                         status))}}]]]
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
                                coercion/coerce-exceptions-middleware
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
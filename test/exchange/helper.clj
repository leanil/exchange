(ns exchange.helper
  (:require [ring.mock.request :as mock]
            [exchange.server :as server]
            [cheshire.core :as cheshire]))

(defn add-user [user-name]
  (-> (mock/request :post "/api/users")
      (mock/json-body {:user_name user-name})
      server/app
      :body slurp
      (cheshire/parse-string true)
      :token
      (->> (str "Bearer "))))

(defn get-balance [token]
  (-> (mock/request :get "/api/balance")
      (mock/header "Authorization" token)
      (mock/body {})
      server/app
      :body slurp
      (cheshire/parse-string true)
      (select-keys [:BTC :USD])))

(defn adjust-balance [token amount currency]
  (-> (mock/request :post "/api/balance")
      (mock/header "Authorization" token)
      (mock/json-body {:amount amount :currency currency})
      server/app
      :body slurp
      (cheshire/parse-string true)
      :success))

(defn add-standing-order [token quantity type limit_price webhook_url]
  (-> (mock/request :post "/api/standing_order")
      (mock/header "Authorization" token)
      (mock/json-body {:quantity quantity :type type :limit_price limit_price :webhook_url webhook_url})
      server/app
      :body slurp
      (cheshire/parse-string true)
      :order_id))

(defn get-standing-order [token order-id]
  (-> (mock/request :get (str "/api/standing_order/" order-id))
      (mock/header "Authorization" token)
      (mock/body {})
      server/app
      :body slurp
      (cheshire/parse-string true)))

(defn delete-standing-order [token order-id]
  (-> (mock/request :delete (str "/api/standing_order/" order-id))
      (mock/header "Authorization" token)
      (mock/body {})
      server/app
      :status))

(defn add-market-order [token quantity type]
  (-> (mock/request :post "/api/market_order")
      (mock/header "Authorization" token)
      (mock/json-body {:quantity quantity :type type})
      server/app
      :body slurp
      (cheshire/parse-string true)))

(def webhook "http://localhost:3000/webhook")

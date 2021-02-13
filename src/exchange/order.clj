(ns exchange.order
  (:require [clj-http.client :as http]
            [clojure.spec.alpha :as s]
            [next.jdbc :as jdbc]
            [exchange.database :as database]))

(def type? #{:BUY :SELL})
(def state? #{:LIVE :FULFILLED :CANCELLED})

(s/def ::id int?)
(s/def ::user-id int?)
(s/def ::state state?)
(s/def ::amount int?)
(s/def ::type type?)
(s/def ::price int?)
(s/def ::url string?)
(s/def ::original-amount int?)
(s/def ::usd-amount int?)
(s/def ::order (s/keys :req [::user-id ::state ::amount ::type ::price ::url] :opt [::id]))

(defn update-order [{::keys [id url] :as order} connection]
  (http/post url {:form-params {:order_id id} :content-type :json})
  (database/update-order order connection))

(defn delete-order [user order-id]
  (http/post (::url (database/get-order order-id user)) {:form-params {:order_id order-id} :content-type :json})
  (database/delete-order order-id user))

(defn get-balance-change [amount price]
  {:SELL {:BTC (- amount) :USD (* amount price)}
   :BUY  {:BTC amount :USD (- (* amount price))}})

(defn subtract-amount [order amount price]
  ;(println "subtract-amount" order amount)
  (-> order
      (update ::amount - amount)
      (update ::state #(if (< amount (::amount order)) % :FULFILLED))
      (update ::usd-amount + (* amount price))))

(defn match-market-order! [connection {:keys [market-order, balance]} {price ::price :as standing-order}]
  ;(println "match-market-order!" market-order balance standing-order)
  (let [balance-limit (case (:type market-order) :SELL (:BTC balance)
                                                 :BUY (quot (:USD balance) price))
        trade-amount (min (:amount market-order) (::amount standing-order) balance-limit)
        balance-change (get-balance-change trade-amount price)]
    (update-order (subtract-amount standing-order trade-amount price) connection)
    (database/adjust-balance connection (::user-id standing-order) (-> standing-order ::type balance-change))
    {:market-order (-> market-order
                       (update :amount - trade-amount)
                       (update :USD + (* trade-amount price)))
     :balance      (reduce #(update %1 %2 + (get-in balance-change [(:type market-order) %2])) balance [:BTC :USD])}))

(defn add-market-order [user amount type]
  (jdbc/with-transaction
    [transaction (database/ds)]
    (let [{{final-amount :amount usd :USD} :market-order balance :balance}
          (reduce
            (partial match-market-order! transaction)
            {:market-order {:amount amount :USD 0 :type type} :balance (database/get-balance transaction user)}
            (database/get-live-orders-of-others transaction user type))
          quantity (- amount final-amount)]
      (database/set-balance transaction user balance)
      {:quantity quantity :avg_price (-> usd (/ quantity) double Math/round)})))

(defn get-reserved-balance [connection user order-type]
  {:amount   (->> (database/get-live-orders-of-user connection user order-type)
                  (map (case order-type :SELL #(::amount %) :BUY #(* (::amount %) (::price %))))
                  (reduce +))
   :currency (order-type {:SELL :BTC, :BUY :USD})})

(defn validate-balance [order connection]
  ;(println "validate-balance" order)
  (let [{reserved-amount :amount currency :currency} (get-reserved-balance connection (::user-id order) (::type order))
        balance (database/get-balance connection (::user-id order))
        order-amount (* (::amount order) (case (::type order) :SELL 1 :BUY (::price order)))
        is-valid (>= (currency balance) (+ reserved-amount order-amount))]
    (update order ::state #(if is-valid % :CANCELLED))))

(defn match-pair! [connection main-order candidate-order]
  ;(println "match-pair!" main-order candidate-order)
  (if (not= (::state main-order) :LIVE)
    (reduced main-order)
    (let [amount (min (::amount main-order) (::amount candidate-order))
          price (::price candidate-order)
          balance-change (get-balance-change amount price)]
      (do (update-order (subtract-amount candidate-order amount price) connection)
          (doseq [order [main-order candidate-order]]
            (database/adjust-balance connection (::user-id order) (-> order ::type balance-change)))
          (subtract-amount main-order amount price)))))

(defn match-standing-order! [{:exchange.order/keys [user-id state type price] :as order} connection]
  ;(println "match!" order)
  (if (not= state :LIVE)
    order
    (reduce (partial match-pair! connection) order
            (database/get-live-orders-of-others connection user-id type price))))

(defn add-standing-order [user amount type price url]
  (jdbc/with-transaction
    [transaction (database/ds)]
    ;namespaced keywords suggested by: https://gist.github.com/levand/c97dd272bfd2f88fe5089eb81f85f98f
    (-> {::user-id user ::state :LIVE ::amount amount ::type type ::price price
         ::url     url ::original-amount amount ::usd-amount 0}
        (validate-balance transaction)
        (match-standing-order! transaction)
        (database/create-order transaction))))

(defn get-standing-order [user order-id]
  (let [{:exchange.order/keys [amount original-amount usd-amount] :as order} (database/get-order order-id user)]
    (assoc order ::avg_price (if (= amount original-amount) nil (/ usd-amount (- original-amount amount))))))
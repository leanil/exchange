(ns exchange.order
  (:require [clojure.spec.alpha :as s]
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

(defn subtract-amount [order amount price]
  ;(println "subtract-amount" order amount)
  (-> order
      (update ::amount - amount)
      (update ::state #(if (< amount (::amount order)) % :FULFILLED))
      (update ::usd-amount + (* amount price))))

(defn match-pair! [connection main-order candidate-order]
  ;(println "match-pair!" main-order candidate-order)
  (if (not= (::state main-order) :LIVE)
    (reduced main-order)
    (let [amount (min (::amount main-order) (::amount candidate-order))
          price (::price candidate-order)
          [buyer seller] (->> (case (::type main-order) :BUY [main-order candidate-order]
                                                        :SELL [candidate-order main-order])
                              (map ::user-id))]
      (do (database/update-order (subtract-amount candidate-order amount price) connection)
          (database/adjust-balance connection buyer amount (- (* amount price)))
          (database/adjust-balance connection seller (- amount) (* amount price))
          (subtract-amount main-order amount price)))))

(defn match! [{:exchange.order/keys [user-id state type price] :as order} connection]
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
        (match! transaction)
        (database/create-order transaction))))

(defn get-standing-order [user order-id]
  (let [{:exchange.order/keys [amount original-amount usd-amount] :as order} (database/get-order order-id user)]
    (assoc order ::avg_price (if (= amount original-amount) nil (/ usd-amount (- original-amount amount))))))
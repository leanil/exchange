(ns sanity-check-test
  (:require
    [clojure.test :as t]
    [sanity-check :as sanity]))

(t/deftest basic-tests
  (t/testing "it says Good Job!"
    (t/is (= (with-out-str (sanity/-main)) "Good job!\n"))))

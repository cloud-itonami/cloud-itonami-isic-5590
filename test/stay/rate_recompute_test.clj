(ns stay.rate-recompute-test
  "The rate-recompute gate: a booking is where money attaches to a stay,
  and until this gate existed a booking could be placed carrying no
  price at all, or any price the advisor felt like stating.

  What is under test is a ground-truth RECOMPUTE, not a restatement.
  Every test therefore takes the same shape: make the proposal claim
  something false, and assert the governor catches it by recomputing
  from the property's own filed rate plan and the booking's own dates."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [kotoba.reservation :as res]
            [stay.llm :as llm]
            [stay.policy :as policy]
            [stay.store :as store]
            [stay.operation :as op]))

(def ^:private coordinator {:actor-id "co-1" :actor-role :booking-coordinator :phase 3})

(defn- clean-booking
  "A booking that does not trip any OTHER gate — sourced, inside
  capacity, on an active licence — so the rate gate is what these tests
  are actually reading."
  [& {:as overrides}]
  (merge {:id "bk-new" :property-id "prop-100" :guest-id "g-1"
          :check-in "2026-09-01" :check-out "2026-09-05" :guests-count 2
          :source {:class :direct-booking-desk :ref "desk:demo-new"}}
         overrides))

(defn- verdict
  ([req] (verdict req identity))
  ([req f]
   (let [db (store/seed-db)
         p (f (llm/infer db (assoc req :op :booking/place)))]
     [db (policy/check {:op :booking/place} coordinator p db)])))

(defn- rules [v] (set (map :rule (:violations v))))

;; ---------------------------------------------------------------------------
;; The happy path prices itself
;; ---------------------------------------------------------------------------

(deftest a-booking-now-carries-a-price-at-all
  (let [db (store/seed-db)
        p (llm/infer db (assoc (clean-booking) :op :booking/place))]
    (is (some? (:quoted-total (:value p)))
        "before this gate a booking could be placed with no price whatsoever")
    (is (= "JPY" (:currency (:value p))))))

(deftest a-correctly-priced-booking-passes-the-recompute
  (let [[_ v] (verdict (clean-booking))]
    (is (empty? (rules v)))
    (is (not (:hard? v)))))

(deftest the-advisor-and-the-governor-compute-the-same-number
  (testing "the governor is not reimplementing the arithmetic, it is re-running it"
    (let [db (store/seed-db)
          prop (store/property db "prop-100")
          nights (res/nights-between "2026-09-01" "2026-09-05")
          expected (res/quote-total (res/quote-for (:rate-plan prop) {:dates nights :qty 2}))
          p (llm/infer db (assoc (clean-booking) :op :booking/place))]
      (is (= expected (:quoted-total (:value p))))
      ;; 4 nights x 2 guests at 4200: Sep 1(Tue) 2(Wed) 3(Thu) are plain,
      ;; Sep 4 is a Friday (+15%). 8400*3 + 9660 + 1500 cleaning = 36360,
      ;; +10% tax = 39996.
      (is (= 39996 expected)))))

;; ---------------------------------------------------------------------------
;; The gate itself
;; ---------------------------------------------------------------------------

(deftest a-total-the-advisor-did-not-compute-is-hard
  (let [[_ v] (verdict (assoc (clean-booking) :mispriced? true))]
    (is (contains? (rules v) :rate-mismatch-gate))
    (is (:hard? v) "a wrong price is never merely an escalation")
    (is (not (:escalate? v))
        "a human is never offered the choice to approve a price that does not recompute")))

(deftest a-booking-with-no-claimed-total-is-hard
  (let [[_ v] (verdict (clean-booking) #(update % :value dissoc :quoted-total))]
    (is (contains? (rules v) :rate-recompute-gate))
    (is (:hard? v))))

(deftest a-property-with-no-filed-rate-plan-is-hard
  (let [db (store/seed-db)
        _ (store/with-properties db (update-in (:properties (store/demo-data))
                                               ["prop-100"] dissoc :rate-plan))
        p (llm/infer db (assoc (clean-booking) :op :booking/place))
        v (policy/check {:op :booking/place} coordinator p db)]
    (is (contains? (rules v) :rate-recompute-gate)
        "un-recomputable is a violation, not a pass")))

(deftest a-zero-night-or-reversed-stay-is-hard
  (doseq [[label ci co] [["same day" "2026-09-01" "2026-09-01"]
                         ["reversed" "2026-09-05" "2026-09-01"]]]
    (testing label
      (let [[_ v] (verdict (clean-booking :check-in ci :check-out co))]
        (is (contains? (rules v) :rate-recompute-gate))))))

(deftest shortening-the-night-list-cannot-make-a-wrong-total-pass
  (testing "the nights are derived from the booking's OWN check-in/check-out,
            so a proposal cannot smuggle in a cheaper night list"
    (let [db (store/seed-db)
          prop (store/property db "prop-100")
          ;; the total for ONE night, claimed against a FOUR night stay
          one-night (res/quote-total
                     (res/quote-for (:rate-plan prop)
                                    {:dates (res/nights-between "2026-09-01" "2026-09-02") :qty 2}))
          p (assoc-in (llm/infer db (assoc (clean-booking) :op :booking/place))
                      [:value :quoted-total] one-night)
          v (policy/check {:op :booking/place} coordinator p db)]
      (is (contains? (rules v) :rate-mismatch-gate)))))

(deftest the-other-ops-are-not-subjected-to-the-rate-gate
  (doseq [o [:property/register :dispute/request :report/query]]
    (let [db (store/seed-db)
          p (llm/infer db {:op o :id "prop-900" :name "demo" :property-type :hostel
                           :jurisdiction :jpn :capacity-total 4 :license-status :active
                           :property-id "prop-100" :disputed-field :total :claim "x"
                           :source {:class :direct-booking-desk :ref "desk:x"}})
          v (policy/check {:op o} (assoc coordinator :actor-role
                                         (case o
                                           :property/register :property-onboarding-officer
                                           :dispute/request :guest-relations-officer
                                           :partner-user))
                          p db)]
      (is (not (contains? (rules v) :rate-recompute-gate)) (str o))
      (is (not (contains? (rules v) :rate-mismatch-gate)) (str o)))))

;; ---------------------------------------------------------------------------
;; End to end through the actor graph
;; ---------------------------------------------------------------------------

(deftest a-mispriced-booking-never-reaches-a-human-and-writes-nothing
  (let [db (store/seed-db)
        actor (op/build db)
        before (count (store/bookings-of-property db "prop-100"))
        r (g/run* actor {:request (assoc (clean-booking) :op :booking/place :mispriced? true)
                         :context coordinator}
                  {:thread-id "t-misprice"})]
    (is (= :hold (get-in r [:state :disposition])))
    (is (not= :interrupted (:status r)))
    (is (= before (count (store/bookings-of-property db "prop-100"))) "no booking written")
    (is (some #{:rate-mismatch-gate} (-> (store/ledger db) last :basis)))))

(deftest a-correctly-priced-booking-commits-with-its-price
  (let [db (store/seed-db)
        actor (op/build db)
        r (g/run* actor {:request (assoc (clean-booking) :op :booking/place)
                         :context coordinator}
                  {:thread-id "t-clean"})]
    (is (= :commit (get-in r [:state :disposition])))
    (let [bk (store/booking db "bk-new")]
      (is (= 39996 (:quoted-total bk)) "the verified price is persisted, not discarded")
      (is (= "JPY" (:currency bk))))))

(deftest both-store-backends-persist-the-verified-price
  (testing "the DatomicStore field list is explicit, so a new field silently
            vanishes on that backend unless it is added there too"
    (doseq [[label db] {"MemStore" (store/seed-db) "DatomicStore" (store/datomic-seed-db)}]
      (testing label
        (let [actor (op/build db)]
          (g/run* actor {:request (assoc (clean-booking) :op :booking/place)
                         :context coordinator}
                  {:thread-id (str "t-" label)})
          (let [bk (store/booking db "bk-new")]
            (is (= 39996 (:quoted-total bk)))
            (is (= "JPY" (:currency bk)))))))))

(deftest both-store-backends-round-trip-the-rate-plan
  (doseq [[label db] {"MemStore" (store/seed-db) "DatomicStore" (store/datomic-seed-db)}]
    (testing label
      (let [plan (:rate-plan (store/property db "prop-100"))]
        (is (= 4200 (:rate/base-amount plan)))
        (is (= "JPY" (:rate/currency plan)))
        (is (= {5 11500 6 11500} (:rate/weekday-bp plan))
            "an integer-keyed map must not be stringified by the blob codec")))))

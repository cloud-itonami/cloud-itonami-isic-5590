(ns stay.phase-test
  "Phase 0→3 staged rollout through the OperationActor. The phase can only
  make the actor MORE conservative than the governor: hold writes that
  aren't enabled yet, force human approval before auto-commit is
  unlocked."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [stay.store :as store]
            [stay.operation :as op]))

(def onboarding  {:actor-id "on-1" :actor-role :property-onboarding-officer})
(def coordinator {:actor-id "co-1" :actor-role :booking-coordinator})
(def relations   {:actor-id "re-1" :actor-role :guest-relations-officer})

(def clean-register
  {:op :property/register :subject "prop-400" :id "prop-400"
   :name "デモキャビン" :property-type :camping-cabin :jurisdiction :jpn
   :capacity-total 6 :license-status :active :safety-cert-expiry "2027-01-01"
   :source {:class :jpn-ryokangyo-license :ref "ryokangyo:demo-400"}})

(def clean-place
  {:op :booking/place :subject "prop-100" :id "bk-2" :property-id "prop-100"
   :guest-id "g-1" :check-in "2026-09-01" :check-out "2026-09-03" :guests-count 4
   :source {:class :direct-booking-desk :ref "demo"}})

(def clean-report
  {:op :report/query :subject "bk-1" :property-id "prop-100"})

(def dispute-req
  {:op :dispute/request :subject "bk-1" :disputed-field :guests-count :claim 14})

(defn- run [phase req ctx]
  (let [s (store/seed-db)
        actor (op/build s)]
    [s (g/run* actor {:request req :context (assoc ctx :phase phase)}
               {:thread-id (str "ph-" phase "-" (:op req))})]))

(deftest phase0-holds-all-writes
  (let [[s res] (run 0 clean-register onboarding)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) first :phase-reason)))
    (is (nil? (store/property s "prop-400")) "SSoT untouched in phase 0")))

(deftest phase0-allows-governed-reads
  (testing "report/query is a read → phase 0 lets it through (governor still applies)"
    (let [[_ res] (run 0 clean-report {:actor-id "pu-1" :actor-role :partner-user :tenant "tenant-ota1"})]
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest phase1-forces-approval-on-clean-register
  (testing "a clean registration that auto-commits in phase 3 must go to a human in phase 1"
    (let [[_ res] (run 1 clean-register onboarding)]
      (is (= :interrupted (:status res)))
      (is (= :phase-approval (-> res :state :audit last :reason))))))

(deftest phase2-enables-booking-place-under-approval
  (let [[_ res] (run 2 clean-place coordinator)]
    (is (= :interrupted (:status res)))
    (is (= :phase-approval (-> res :state :audit last :reason)))))

(deftest phase3-auto-commits-clean-register
  (let [[s res] (run 3 clean-register onboarding)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "デモキャビン" (:name (store/property s "prop-400"))))))

(deftest governor-hold-beats-phase
  (testing "a hard governor violation (overbooked capacity) holds even in the most permissive phase"
    (let [[_ res] (run 3 {:op :booking/place :subject "prop-100" :id "bk-4" :property-id "prop-100"
                          :guest-id "g-1" :check-in "2026-08-02" :check-out "2026-08-04" :guests-count 8
                          :source {:class :direct-booking-desk :ref "demo"}}
                       coordinator)]
      (is (= :hold (get-in res [:state :disposition]))))))

(deftest dispute-request-never-auto-commits-at-any-phase
  (testing "a booking dispute never reaches :commit without an explicit human :approval"
    (doseq [ph [0 1 2 3]]
      (let [[_ res] (run ph dispute-req relations)]
        (is (not= :commit (get-in res [:state :disposition]))
            (str "phase " ph " must not auto-commit a dispute"))))))

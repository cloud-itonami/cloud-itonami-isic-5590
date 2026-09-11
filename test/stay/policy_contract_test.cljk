(ns stay.policy-contract-test
  "The governor contract as executable tests — the analog of
  `cloud-itonami-isic-6311`/`cloud-itonami-isic-7820`'s
  policy_contract_test / robotaxi's safety_contract_test. The single
  invariant under test:

    StayBooking-LLM never registers/confirms/resolves a record the
    StayGovernor would reject, and every decision (commit OR hold) leaves
    exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [stay.store :as store]
            [stay.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def onboarding  {:actor-id "on-1" :actor-role :property-onboarding-officer})
(def coordinator {:actor-id "co-1" :actor-role :booking-coordinator})
(def relations   {:actor-id "re-1" :actor-role :guest-relations-officer})
;; default-phase is 1 (assisted, no auto-commit) -- tests that specifically
;; exercise governor-clean auto-commit/escalate behavior opt into phase 3
;; explicitly, the same way phase_test.clj parameterizes phase.
(def onboarding-p3  (assoc onboarding :phase 3))
(def coordinator-p3 (assoc coordinator :phase 3))
;; `:booking/place`/`:dispute/request` aren't enabled at all until phase 2
;; (see stay.phase's `phases` table) -- tests below that exercise the
;; StayGovernor's own escalation logic (flagged guest / dispute) use phase
;; 3 explicitly so the escalation is provably a GOVERNOR decision, not an
;; artifact of the op being phase-disabled at the conservative default.
(def relations-p3   (assoc relations :phase 3))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(deftest authorized-register-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :property/register :subject "prop-400" :id "prop-400"
                   :name "デモキャビン" :property-type :camping-cabin :jurisdiction :jpn
                   :capacity-total 6 :license-status :active :safety-cert-expiry "2027-01-01"
                   :source {:class :jpn-ryokangyo-license :ref "ryokangyo:demo-400"}}
                  onboarding-p3)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "デモキャビン" (:name (store/property db "prop-400"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))
    (is (= :commit (-> (store/ledger db) first :disposition)))))

(deftest unauthorized-role-is-held
  (testing "a :partner-user role has no register permission → HOLD, no write"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :property/register :subject "prop-400" :id "prop-400"
                     :name "X" :property-type :hostel :jurisdiction :jpn
                     :capacity-total 6 :license-status :active :safety-cert-expiry "2027-01-01"
                     :source {:class :jpn-ryokangyo-license :ref "demo"}}
                    {:actor-id "pu-1" :actor-role :partner-user})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (nil? (store/property db "prop-400")) "SSoT unchanged")
      (is (= [:rbac] (-> (store/ledger db) first :basis))))))

(deftest unsourced-booking-is-held
  (testing "a booking with no channel citation (dropped OTA webhook) → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :booking/place :subject "prop-100" :id "bk-2" :property-id "prop-100"
                     :guest-id "g-1" :check-in "2026-09-01" :check-out "2026-09-03" :guests-count 4
                     :source {:class :direct-booking-desk :ref "demo"} :unsourced? true}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:source-provenance-gate} (-> (store/ledger db) first :basis)))
      (is (nil? (store/booking db "bk-2")) "no booking written"))))

(deftest unlicensed-operator-attested-property-is-held
  (testing "a property citing :operator-attested-license whose license-ref is inactive → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t3b"
                    {:op :property/register :subject "prop-500" :id "prop-500"
                     :name "X" :property-type :dormitory :jurisdiction :usa
                     :capacity-total 10 :license-status :active :safety-cert-expiry "2027-01-01"
                     :source {:class :operator-attested-license :ref "demo" :license-ref "lic-op-2"}}
                    onboarding)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:source-provenance-gate} (-> (store/ledger db) first :basis))))))

(deftest license-lapsed-property-booking-is-held
  (testing "a booking at a property whose safety-cert-expiry precedes check-in → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t4"
                    {:op :booking/place :subject "prop-200" :id "bk-3" :property-id "prop-200"
                     :guest-id "g-1" :check-in "2026-08-10" :check-out "2026-08-12" :guests-count 2
                     :source {:class :direct-booking-desk :ref "demo"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:license-lapsed-gate} (-> (store/ledger db) first :basis)))
      (is (nil? (store/booking db "bk-3"))))))

(deftest capacity-overbooking-is-held
  (testing "an overlapping booking pushing occupancy past total capacity → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :booking/place :subject "prop-100" :id "bk-4" :property-id "prop-100"
                     :guest-id "g-1" :check-in "2026-08-02" :check-out "2026-08-04" :guests-count 8
                     :source {:class :direct-booking-desk :ref "demo"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:capacity-overbooking-gate} (-> (store/ledger db) first :basis)))
      (is (nil? (store/booking db "bk-4"))))))

(deftest non-overlapping-booking-does-not-trip-capacity-gate
  (testing "a booking at the same property but non-overlapping dates ignores prior occupancy"
    (let [[_db actor] (fresh)
          res (exec-op actor "t5b"
                    {:op :booking/place :subject "prop-100" :id "bk-6" :property-id "prop-100"
                     :guest-id "g-1" :check-in "2026-09-01" :check-out "2026-09-03" :guests-count 18
                     :source {:class :direct-booking-desk :ref "demo"}}
                    coordinator-p3)]
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest uncontracted-report-is-held
  (testing "a report query from a tenant with no registered contract → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :report/query :subject "bk-1" :property-id "prop-100"}
                    {:actor-id "pu-2" :actor-role :partner-user :tenant "tenant-ghost"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis))))))

(deftest over-disclosure-beyond-tier-is-held
  (testing "a report query pulling columns beyond the contract's tier → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :report/query :subject "bk-1" :property-id "prop-100" :greedy? true}
                    {:actor-id "pu-1" :actor-role :partner-user :tenant "tenant-ota1"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis))))))

(deftest clean-report-within-tier-commits-directly
  (testing "a clean, in-tier report query auto-serves (it's a governed read)"
    (let [[_db actor] (fresh)
          res (exec-op actor "t7b"
                    {:op :report/query :subject "bk-1" :property-id "prop-100"}
                    {:actor-id "pu-1" :actor-role :partner-user :tenant "tenant-ota1"})]
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest flagged-guest-escalates-then-human-decides
  (testing "an otherwise-clean booking for a flagged guest interrupts for human approval"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t8"
                   {:op :booking/place :subject "prop-300" :id "bk-5" :property-id "prop-300"
                    :guest-id "g-2" :check-in "2026-10-01" :check-out "2026-10-03" :guests-count 3
                    :source {:class :direct-booking-desk :ref "demo"}}
                   coordinator-p3)]
      (is (= :interrupted (:status r1)) "pauses for human approval")
      (is (= :flagged-guest (-> r1 :state :audit last :reason)))
      (testing "approve → commit"
        (let [r2 (g/run* actor {:approval {:status :approved :by "relations-1"}}
                         {:thread-id "t8" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :confirmed (:status (store/booking db "bk-5"))))
          (is (= :commit (-> (store/ledger db) last :disposition)))))))
  (testing "reject → hold"
    (let [[db actor] (fresh)
          _  (exec-op actor "t9"
                  {:op :booking/place :subject "prop-300" :id "bk-5" :property-id "prop-300"
                   :guest-id "g-2" :check-in "2026-10-01" :check-out "2026-10-03" :guests-count 3
                   :source {:class :direct-booking-desk :ref "demo"}}
                  coordinator-p3)
          r2 (g/run* actor {:approval {:status :rejected :by "relations-1"}}
                     {:thread-id "t9" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (nil? (store/booking db "bk-5"))))))

(deftest dispute-request-always-escalates-regardless-of-confidence
  (testing "a booking dispute always reaches a human, never auto-resolves"
    (let [[db actor] (fresh)
          before (store/booking db "bk-1")
          r1 (exec-op actor "t10"
                   {:op :dispute/request :subject "bk-1" :disputed-field :guests-count :claim 14}
                   relations-p3)]
      (is (= :interrupted (:status r1)))
      (is (= :booking-dispute (-> r1 :state :audit last :reason)))
      (testing "approve → commit applies the correction"
        (let [r2 (g/run* actor {:approval {:status :approved :by "relations-1"}}
                         {:thread-id "t10" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= 14 (:guests-count (store/booking db "bk-1"))))))
      (testing "a second, rejected dispute leaves the booking unchanged"
        (let [[db2 actor2] (fresh)
              _  (exec-op actor2 "t11"
                      {:op :dispute/request :subject "bk-1" :disputed-field :guests-count :claim 14}
                      relations-p3)
              r3 (g/run* actor2 {:approval {:status :rejected :by "relations-1"}}
                        {:thread-id "t11" :resume? true})]
          (is (= :hold (get-in r3 [:state :disposition])))
          (is (= (:guests-count before) (:guests-count (store/booking db2 "bk-1")))))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations → N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :property/register :subject "prop-400" :id "prop-400"
                          :name "X" :property-type :hostel :jurisdiction :jpn
                          :capacity-total 6 :license-status :active :safety-cert-expiry "2027-01-01"
                          :source {:class :jpn-ryokangyo-license :ref "demo"}}
               onboarding-p3)
      (exec-op actor "b" {:op :booking/place :subject "prop-100" :id "bk-2" :property-id "prop-100"
                          :guest-id "g-1" :check-in "2026-09-01" :check-out "2026-09-03" :guests-count 4
                          :source nil :unsourced? true}
               coordinator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))

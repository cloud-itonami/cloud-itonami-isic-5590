(ns stay.llm-test
  "StayBooking-LLM proposal generation, unit-level (no governor/actor
  involved — that integration is covered by policy_contract_test)."
  (:require [clojure.test :refer [deftest is testing]]
            [stay.store :as store]
            [stay.llm :as llm]))

(deftest register-proposal-carries-source-and-cites
  (let [db (store/seed-db)
        p (llm/infer db {:op :property/register :subject "prop-400" :id "prop-400"
                         :name "X" :property-type :hostel :jurisdiction :jpn
                         :capacity-total 6 :license-status :active :safety-cert-expiry "2027-01-01"
                         :source {:class :jpn-ryokangyo-license :ref "demo"}})]
    (is (= :property-upsert (:effect p)))
    (is (= {:class :jpn-ryokangyo-license :ref "demo"} (:source p)))
    (is (>= (:confidence p) 0.9))))

(deftest unsourced-place-proposal-carries-nil-source
  (testing "the LLM layer does not filter — that is the governor's job; this only proves the injected failure mode actually reaches the proposal"
    (let [db (store/seed-db)
          p (llm/infer db {:op :booking/place :subject "prop-100" :id "bk-2" :property-id "prop-100"
                           :guest-id "g-1" :check-in "2026-09-01" :check-out "2026-09-03" :guests-count 4
                           :source {:class :direct-booking-desk :ref "demo"} :unsourced? true})]
      (is (nil? (:source p)))
      (is (>= (:confidence p) 0.85) "still high-confidence — proves source-provenance cannot rely on confidence as a proxy"))))

(deftest report-proposal-greedy-adds-extra-columns
  (let [db (store/seed-db)
        clean (llm/infer db {:op :report/query :subject "bk-1" :property-id "prop-100"})
        greedy (llm/infer db {:op :report/query :subject "bk-1" :property-id "prop-100" :greedy? true})]
    (is (< (count (:columns clean)) (count (:columns greedy))))
    (is (some #{:guest-id :guests-count} (:columns greedy)))))

(deftest dispute-proposal-never-marks-high-confidence
  (let [db (store/seed-db)
        p (llm/infer db {:op :dispute/request :subject "bk-1" :disputed-field :guests-count :claim 14})]
    (is (= :dispute-apply (:effect p)))
    (is (< (:confidence p) 0.9) "disputes are claims pending human verification, never auto-confident")))

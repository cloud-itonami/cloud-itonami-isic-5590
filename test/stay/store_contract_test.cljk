(ns stay.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and the
  Datomic-backed (langchain.db) store satisfy the same contract is what
  makes 'swap the SSoT for Datomic' a configuration change, not a
  rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [stay.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "出島ホステル(デモ)" (:name (store/property s "prop-100"))))
      (is (= 20 (:capacity-total (store/property s "prop-100"))))
      (is (= :active (:license-status (store/property s "prop-200"))))
      (is (= "2026-01-01" (:safety-cert-expiry (store/property s "prop-200"))))
      (is (= {:class :jpn-ryokangyo-license :ref "ryokangyo:demo-100"}
             (:license-basis (store/property s "prop-100")))
          "license-basis citation round-trips (stored as EDN on Datomic, not a sub-entity)")
      (is (= 1 (count (store/bookings-of-property s "prop-100"))))
      (is (= "bk-1" (:id (first (store/bookings-of-property s "prop-100")))))
      (is (true? (:flagged? (store/guest s "g-2"))))
      (is (false? (:flagged? (store/guest s "g-1"))))
      (is (true? (:active? (store/license-record s "lic-op-1"))))
      (is (false? (:active? (store/license-record s "lic-op-2"))))
      (is (= 3 (count (store/all-properties s)))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "booking upsert commits a new confirmed booking"
        (store/commit-record! s {:effect :booking-upsert
                                 :value {:id "bk-9" :property-id "prop-100" :guest-id "g-1"
                                         :check-in "2026-09-01" :check-out "2026-09-03"
                                         :guests-count 4 :status :confirmed
                                         :source {:class :direct-booking-desk :ref "demo"}}})
        (is (= :confirmed (:status (store/booking s "bk-9"))))
        (is (= 2 (count (store/bookings-of-property s "prop-100")))))
      (testing "dispute-apply patches the target booking"
        (store/commit-record! s {:effect :dispute-apply
                                 :value {:patch {:guests-count 14}}
                                 :path ["bk-1"]})
        (is (= 14 (:guests-count (store/booking s "bk-1")))))
      (testing "property upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :property-upsert
                                 :value {:id "prop-100" :license-status :suspended}})
        (is (= :suspended (:license-status (store/property s "prop-100"))))
        (is (= "出島ホステル(デモ)" (:name (store/property s "prop-100"))) "name preserved"))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (take-last 2 (store/ledger s)))))))))

(deftest contract-lookup
  (doseq [[label s] (backends)]
    (testing label
      (is (= :tier/detailed (:tier (store/contract s "tenant-ota2"))))
      (is (true? (:active? (store/contract s "tenant-ota2"))))
      (is (nil? (store/contract s "tenant-ghost"))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/property s "nope")))
    (is (= [] (store/all-properties s)))
    (is (= [] (store/ledger s)))
    (store/with-properties s {"x" {:id "x" :name "X" :capacity-total 5}})
    (is (= "X" (:name (store/property s "x"))))))

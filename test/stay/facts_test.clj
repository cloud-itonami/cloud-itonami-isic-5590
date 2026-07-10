(ns stay.facts-test
  "The R0 provenance catalog is the whole ground truth for the
  source-provenance gate — these tests guard its own internal honesty
  (every class it advertises is actually backed by a catalog entry, no
  duplicate/aspirational entries)."
  (:require [clojure.test :refer [deftest is testing]]
            [stay.facts :as facts]))

(deftest catalog-entries-are-well-formed
  (doseq [{:keys [id name kind class]} facts/catalog]
    (testing (str id)
      (is (keyword? id))
      (is (string? name))
      (is (contains? #{:license-basis :booking-channel} kind))
      (is (keyword? class)))))

(deftest allowed-source-classes-matches-catalog
  (is (= (into #{} (map :class facts/catalog)) facts/allowed-source-classes)))

(deftest class-allowed?-rejects-unlisted-classes
  (is (facts/class-allowed? :jpn-ryokangyo-license))
  (is (facts/class-allowed? :gbr-fire-safety-order-2005))
  (is (facts/class-allowed? :operator-attested-license))
  (is (facts/class-allowed? :direct-booking-desk))
  (is (facts/class-allowed? :ota-partner-verified))
  (is (not (facts/class-allowed? :inference)))
  (is (not (facts/class-allowed? :self-attested-no-basis)))
  (is (not (facts/class-allowed? nil))))

(deftest operator-attested-class-recognized
  (is (facts/operator-attested-class? :operator-attested-license))
  (is (not (facts/operator-attested-class? :jpn-ryokangyo-license))))

(deftest coverage-is-honest-not-aspirational
  (let [c (facts/coverage)]
    ;; the catalog is 2 real regimes + 1 structural class + 2 booking
    ;; channels, not "全世界の宿泊施設ライセンス制度" — this test fails
    ;; loudly if someone pads the catalog with unverifiable entries.
    (is (= (count facts/catalog) (:source-count c)))
    (is (<= (:source-count c) 15) "R0 catalog should stay small and citable, not bulk-padded")
    (is (contains? (:license-jurisdictions c) :jpn))
    (is (contains? (:license-jurisdictions c) :gbr))
    (is (= 2 (:booking-channels c)))))

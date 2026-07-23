(ns stay.store
  "SSoT for the alternative/short-stay accommodation actor, behind a
  `Store` protocol so the backend is a swap, not a rewrite:

    - `MemStore`     — atom of Datomic-shaped EDN. The deterministic default
                       for dev/tests/demo (no deps).
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible EAV
                       store. Pure `.cljc`, so it runs offline AND can be
                       pointed at a real Datomic Local or a kotoba-server pod
                       by swapping `langchain.db`'s `:db-api`.

  Both implement the same protocol and pass the same contract
  (test/stay/store_contract_test.clj) — the actor, the StayGovernor and
  the audit ledger never know which SSoT they run on.

  Entity shapes: a property (hostel/guesthouse/camping-cabin/dormitory —
  `:license-status`/`:safety-cert-expiry` is what the license-lapsed-gate
  polices), a booking (property×guest, date range, guest count — the unit
  the capacity-overbooking-gate polices via interval overlap against every
  OTHER confirmed booking at the same property), a guest (with an optional
  operator caution flag), an operator-registered license record (for the
  `:operator-attested-license` provenance class), and a client-billing
  contract (tenant × tier, licensed disclosure). There is NO field
  anywhere in this schema for payment capture, refund execution, or a
  physical door/key-code credential — this actor proposes/confirms
  bookings and disputes, it never moves money or issues physical access
  (the same class of structural exclusion as `cloud-itonami-isic-6311`'s
  market-data actor never trading, or `cloud-itonami-isic-7820`'s staffing
  actor never disbursing payroll).

  The ledger stays append-only on every backend — 'who booked/registered/
  disputed what, on what license/source basis' is always a query over an
  immutable log."
  (:require [clojure.string :as str]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (property [s id])
  (all-properties [s])
  (bookings-of-property [s property-id] "all bookings (any status) at this property, for the capacity-overlap calc")
  (booking [s id])
  (guest [s id])
  (license-record [s license-ref])
  (contract [s tenant])
  (ledger [s])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision/disclosure fact")
  (with-properties [s properties] "replace/seed properties (map id→property)")
  (with-bookings [s bookings]     "replace/seed bookings (map id→booking)")
  (with-guests [s guests]         "replace/seed guests (map id→guest)")
  (with-licenses [s licenses]     "replace/seed operator license records (map license-ref→license)")
  (with-contracts [s contracts]   "replace/seed client billing contracts (map tenant→contract)"))

;; ───────────────────────── demo data (fictitious, non-real properties) ──

(defn demo-data
  "A small, entirely fictitious dataset so the actor + tests run offline
  and no real property or person is ever named in this repository.
  `prop-100` is pre-booked to 15/20 capacity for 2026-08-01..05 so a new
  overlapping booking of 8 guests exercises the capacity-overbooking-gate.
  `prop-200`'s `:safety-cert-expiry` is already in the past (demo
  `:license-status :active` — the LAPSED CERT, not a manually-flipped
  status, is what the license-lapsed-gate must catch) to exercise that
  gate. `g-2` carries a demo `:flagged?` purely to exercise the
  guest-flagged governor gate."
  []
  {:properties
   {"prop-100" {:id "prop-100" :name "出島ホステル(デモ)" :property-type :hostel
                :jurisdiction :jpn :capacity-total 20 :license-status :active
                :license-basis {:class :jpn-ryokangyo-license :ref "ryokangyo:demo-100"}
                :safety-cert-expiry "2027-06-01"}
    "prop-200" {:id "prop-200" :name "Northwind Guesthouse (demo)" :property-type :guesthouse
                :jurisdiction :gbr :capacity-total 10 :license-status :active
                :license-basis {:class :gbr-fire-safety-order-2005 :ref "fsa:demo-200"}
                :safety-cert-expiry "2026-01-01"}
    "prop-300" {:id "prop-300" :name "Demo Worker Dormitory" :property-type :dormitory
                :jurisdiction :usa :capacity-total 15 :license-status :active
                :license-basis {:class :operator-attested-license :ref "lic-op-1"}
                :safety-cert-expiry "2027-06-01"}}
   :bookings
   {"bk-1" {:id "bk-1" :property-id "prop-100" :guest-id "g-1"
            :check-in "2026-08-01" :check-out "2026-08-05" :guests-count 15
            :status :confirmed
            :source {:class :direct-booking-desk :ref "desk:demo-bk-1"}}}
   :guests
   {"g-1" {:id "g-1" :name "山田 一郎(デモ)" :flagged? false}
    "g-2" {:id "g-2" :name "Jane Smith (demo)" :flagged? true :flag-reason :prior-property-damage}}
   :licenses
   {"lic-op-1" {:license-ref "lic-op-1" :jurisdiction :usa :active? true}
    "lic-op-2" {:license-ref "lic-op-2" :jurisdiction :usa :active? false}}
   :contracts
   {"tenant-ota1" {:tenant "tenant-ota1" :tier :tier/basic :active? true :purpose :ota-listing}
    "tenant-ota2" {:tenant "tenant-ota2" :tier :tier/detailed :active? true :purpose :property-owner-portal}}})

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (property [_ id] (get-in @a [:properties id]))
  (all-properties [_] (sort-by :id (vals (:properties @a))))
  (bookings-of-property [_ property-id]
    (->> (vals (:bookings @a)) (filter #(= property-id (:property-id %))) (sort-by :id)))
  (booking [_ id] (get-in @a [:bookings id]))
  (guest [_ id] (get-in @a [:guests id]))
  (license-record [_ license-ref] (get-in @a [:licenses license-ref]))
  (contract [_ tenant] (get-in @a [:contracts tenant]))
  (ledger [_] (:ledger @a))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :property-upsert (swap! a update-in [:properties (:id value)] merge value)
      :booking-upsert   (swap! a assoc-in [:bookings (:id value)] value)
      :dispute-apply    (swap! a update-in [:bookings (first path)] merge (:patch value))
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-properties [s ps] (when (seq ps) (swap! a assoc :properties ps)) s)
  (with-bookings [s bs]   (when (seq bs) (swap! a assoc :bookings bs)) s)
  (with-guests [s gs]     (when (seq gs) (swap! a assoc :guests gs)) s)
  (with-licenses [s ls]   (when (seq ls) (swap! a assoc :licenses ls)) s)
  (with-contracts [s cts] (when (seq cts) (swap! a assoc :contracts cts)) s))

(defn seed-db
  "A MemStore seeded with the demo data. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger []))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (license-basis citations, source citations) are
  stored as EDN strings so `langchain.db` doesn't expand them into
  sub-entities."
  {:property/id     {:db/unique :db.unique/identity}
   :booking/id      {:db/unique :db.unique/identity}
   :guest/id        {:db/unique :db.unique/identity}
   :license/ref     {:db/unique :db.unique/identity}
   :contract/tenant {:db/unique :db.unique/identity}
   :ledger/seq      {:db/unique :db.unique/identity}})

(defn- property->tx [{:keys [id name property-type jurisdiction capacity-total
                             license-status license-basis safety-cert-expiry]}]
  (cond-> {:property/id id}
    name              (assoc :property/name name)
    property-type     (assoc :property/type property-type)
    jurisdiction      (assoc :property/jurisdiction jurisdiction)
    capacity-total    (assoc :property/capacity-total capacity-total)
    license-status    (assoc :property/license-status license-status)
    license-basis     (assoc :property/license-basis (ls/enc license-basis))
    safety-cert-expiry (assoc :property/safety-cert-expiry safety-cert-expiry)))

(defn- pull->property [m]
  (when (:property/id m)
    {:id (:property/id m) :name (:property/name m) :property-type (:property/type m)
     :jurisdiction (:property/jurisdiction m) :capacity-total (:property/capacity-total m)
     :license-status (:property/license-status m) :license-basis (ls/dec* (:property/license-basis m))
     :safety-cert-expiry (:property/safety-cert-expiry m)}))

(def ^:private property-pull
  [:property/id :property/name :property/type :property/jurisdiction :property/capacity-total
   :property/license-status :property/license-basis :property/safety-cert-expiry])

(defn- booking->tx [{:keys [id property-id guest-id check-in check-out guests-count status source]}]
  {:booking/id id :booking/property-id property-id :booking/guest-id guest-id
   :booking/check-in check-in :booking/check-out check-out :booking/guests-count guests-count
   :booking/status status :booking/source (ls/enc source)})

(defn- pull->booking [m]
  (when (:booking/id m)
    {:id (:booking/id m) :property-id (:booking/property-id m) :guest-id (:booking/guest-id m)
     :check-in (:booking/check-in m) :check-out (:booking/check-out m)
     :guests-count (:booking/guests-count m) :status (:booking/status m) :source (ls/dec* (:booking/source m))}))

(def ^:private booking-pull
  [:booking/id :booking/property-id :booking/guest-id :booking/check-in :booking/check-out
   :booking/guests-count :booking/status :booking/source])

(defn- guest->tx [{:keys [id name flagged? flag-reason]}]
  (cond-> {:guest/id id}
    name (assoc :guest/name name)
    true (assoc :guest/flagged (boolean flagged?))
    flag-reason (assoc :guest/flag-reason flag-reason)))

(defn- pull->guest [m]
  (when (:guest/id m)
    {:id (:guest/id m) :name (:guest/name m) :flagged? (:guest/flagged m) :flag-reason (:guest/flag-reason m)}))

(def ^:private guest-pull [:guest/id :guest/name :guest/flagged :guest/flag-reason])

(defn- license->tx [{:keys [license-ref jurisdiction active?]}]
  {:license/ref license-ref :license/jurisdiction jurisdiction :license/active active?})

(defn- pull->license [m]
  (when (:license/ref m)
    {:license-ref (:license/ref m) :jurisdiction (:license/jurisdiction m) :active? (:license/active m)}))

(def ^:private license-pull [:license/ref :license/jurisdiction :license/active])

(defn- contract->tx [{:keys [tenant tier active? purpose]}]
  {:contract/tenant tenant :contract/tier tier :contract/active active? :contract/purpose purpose})

(defn- pull->contract [m]
  (when (:contract/tenant m)
    {:tenant (:contract/tenant m) :tier (:contract/tier m)
     :active? (:contract/active m) :purpose (:contract/purpose m)}))

(def ^:private contract-pull [:contract/tenant :contract/tier :contract/active :contract/purpose])

(defrecord DatomicStore [conn]
  Store
  (property [_ id] (pull->property (d/pull (d/db conn) property-pull [:property/id id])))
  (all-properties [_]
    (->> (d/q '[:find [?id ...] :where [?e :property/id ?id]] (d/db conn))
         (map #(pull->property (d/pull (d/db conn) property-pull [:property/id %])))
         (sort-by :id)))
  (bookings-of-property [_ property-id]
    (->> (d/q '[:find [?id ...] :in $ ?pid :where [?b :booking/property-id ?pid] [?b :booking/id ?id]]
              (d/db conn) property-id)
         (map #(pull->booking (d/pull (d/db conn) booking-pull [:booking/id %])))
         (sort-by :id)))
  (booking [_ id] (pull->booking (d/pull (d/db conn) booking-pull [:booking/id id])))
  (guest [_ id] (pull->guest (d/pull (d/db conn) guest-pull [:guest/id id])))
  (license-record [_ license-ref] (pull->license (d/pull (d/db conn) license-pull [:license/ref license-ref])))
  (contract [_ tenant] (pull->contract (d/pull (d/db conn) contract-pull [:contract/tenant tenant])))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :property-upsert (d/transact! conn [(property->tx (merge (property s (:id value)) value))])
      :booking-upsert   (d/transact! conn [(booking->tx value)])
      :dispute-apply
      (d/transact! conn [(booking->tx (merge (booking s (first path)) (:patch value)))])
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (ls/enc fact)}])
    fact)
  (with-properties [s ps]
    (when (seq ps) (d/transact! conn (mapv property->tx (vals ps)))) s)
  (with-bookings [s bs]
    (when (seq bs) (d/transact! conn (mapv booking->tx (vals bs)))) s)
  (with-guests [s gs]
    (when (seq gs) (d/transact! conn (mapv guest->tx (vals gs)))) s)
  (with-licenses [s ls]
    (when (seq ls) (d/transact! conn (mapv license->tx (vals ls)))) s)
  (with-contracts [s cts]
    (when (seq cts) (d/transact! conn (mapv contract->tx (vals cts)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`; empty when
  omitted."
  ([] (datomic-store {}))
  ([{:keys [properties bookings guests licenses contracts]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-properties properties) (with-bookings bookings)
         (with-guests guests) (with-licenses licenses) (with-contracts contracts)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo data — the Datomic-backed analog of
  `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line
  "Human-readable one-liner for a ledger fact (used by the demo)."
  [{:keys [op actor subject disposition basis]}]
  (str/join " · "
            [(name disposition)
             (str "op=" op)
             (str "actor=" actor)
             (str "subject=" subject)
             (str "basis=" (pr-str basis))]))

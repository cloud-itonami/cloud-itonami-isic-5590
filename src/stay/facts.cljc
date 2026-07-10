(ns stay.facts
  "R0 provenance catalog — the ONLY classes `stay.policy`'s
  source-provenance-gate will accept as a citation, split into two kinds
  the same flat closed set serves (mirrors `cloud-itonami-isic-6311`'s
  `marketdata.facts` reusing one catalog across `:quote/ingest` and
  `:series/derive`):

    1. `:property/register` license-basis citations — real, citable
       statutory regimes (R0: honest, small — 2 jurisdictions) PLUS
       `:operator-attested-license`, a structural class for jurisdictions
       outside R0: accepted only when the proposal also carries a
       `:license-ref` that resolves to an operator-registered license
       record (checked separately, same shape as marketdata's
       `:licensed-operator-feed`).
    2. `:booking/place` booking-channel citations — how the booking was
       actually made (direct desk vs. OTA partner), never fabricated.

  Extend only by appending a real, citable regime or a real registered
  license — never fabricate either.")

(def catalog
  "Each entry: {:id :name :kind (:license-basis|:booking-channel)
  :class :jurisdiction|nil :access :url|nil}."
  [{:id :jpn-ryokangyo-license
    :name "旅館業法 (Ryokan Business Act) 旅館業許可 — hostel/guesthouse lodging license"
    :kind :license-basis :class :jpn-ryokangyo-license :jurisdiction :jpn
    :access :local-public-health-center
    :url "https://www.mhlw.go.jp/stf/seisakunitsuite/bunya/kenkou_iryou/kenkou/seikatsu-eisei/ryokan/"}
   {:id :gbr-fire-safety-order-2005
    :name "Regulatory Reform (Fire Safety) Order 2005 — fire risk assessment for guest accommodation"
    :kind :license-basis :class :gbr-fire-safety-order-2005 :jurisdiction :gbr
    :access :public-website
    :url "https://www.legislation.gov.uk/uksi/2005/1541/contents/made"}
   {:id :operator-attested-license
    :name "Operator-registered license (jurisdictions outside R0's 2 explicit regimes)"
    :kind :license-basis :class :operator-attested-license :jurisdiction nil
    :access :operator-licensed :url nil}
   {:id :direct-booking-desk
    :name "Direct booking (property's own desk/site, staff-verified)"
    :kind :booking-channel :class :direct-booking-desk :jurisdiction nil
    :access :internal :url nil}
   {:id :ota-partner-verified
    :name "OTA partner channel (requires a partner-id + booking-ref)"
    :kind :booking-channel :class :ota-partner-verified :jurisdiction nil
    :access :partner-api :url nil}])

(def allowed-source-classes
  "The set of `:source :class` values the source-provenance-gate will
  accept anywhere. A closed set — a class not in `catalog` (e.g.
  :inference, :self-attested-no-basis) must be rejected."
  (into #{} (map :class catalog)))

(def license-basis-classes (into #{} (comp (filter #(= :license-basis (:kind %))) (map :class)) catalog))
(def booking-channel-classes (into #{} (comp (filter #(= :booking-channel (:kind %))) (map :class)) catalog))

(defn coverage
  "Honest, machine-checkable report of what R0 actually covers — never
  overstate ('全世界の宿泊施設ライセンス制度' in prose, 2 real regimes +
  1 structural operator-attested class in fact)."
  []
  {:source-count (count catalog)
   :license-jurisdictions (into (sorted-set) (keep :jurisdiction (filter #(= :license-basis (:kind %)) catalog)))
   :booking-channels (count booking-channel-classes)
   :note (str "R0 scope: 2 real statutory license regimes (JPN 旅館業法, "
              "GBR Fire Safety Order 2005) + 1 structural operator-attested "
              "class for other jurisdictions, + 2 real booking-channel "
              "classes. Extend only by appending a real regime or a real "
              "registered license — never fabricate either.")})

(defn class-allowed? [source-class]
  (contains? allowed-source-classes source-class))

(defn operator-attested-class? [source-class]
  (= :operator-attested-license source-class))

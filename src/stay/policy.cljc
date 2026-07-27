(ns stay.policy
  "StayGovernor — the independent compliance layer that earns the
  StayBooking-LLM the right to register a property, confirm a booking, or
  serve a client report. The LLM has no notion of physical capacity,
  license/fire-safety-certification lapse, or a partner's disclosure
  entitlement, so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD (register/confirm/serve nothing) — this
  actor's analog of `cloud-itonami-isic-6311`'s MarketDataGovernor and
  `cloud-itonami-isic-7820`'s StaffingGovernor.

  Eight checks, in priority order. The first five are HARD violations: a
  human approver CANNOT override them. The last three are SOFT/always-
  escalate: they route to a human, who may approve.

    1. rbac                    — does actor-role have permission for op?
    2. capacity-overbooking-gate — would confirming this booking push the
                                  property's overlapping-date occupancy
                                  past its total capacity? (this actor's
                                  domain-unique HARD check: real interval-
                                  overlap arithmetic against every OTHER
                                  confirmed booking at the same property,
                                  no analog in any sibling actor)
    3. license-lapsed-gate      — is the target property's license
                                  ACTIVE and its safety-cert-expiry on or
                                  after check-in? (this actor's second
                                  domain-unique HARD check — a lapsed fire/
                                  safety certificate is a structural
                                  no-go, not a judgment call)
    4. source-provenance-gate   — does the proposal cite an allowed
                                  provenance class (license-basis for
                                  `:property/register`, booking-channel
                                  for `:booking/place`) — and, for
                                  `:operator-attested-license`, an ACTIVE
                                  registered license record?
    5. licensed-disclosure      — is there an active client-billing
                                  contract, and does the requested report
                                  stay within its tier?
    6. confidence floor         — LLM confidence below threshold → escalate.
    7. guest-flagged gate       — the guest carries an operator caution
                                  flag → always escalate, regardless of
                                  confidence.
    8. dispute requests         — a booking/billing dispute NEVER
                                  auto-resolves, at any confidence, any
                                  phase."
  (:require [clojure.set :as set]
            [kotoba.reservation :as res]
            [stay.facts :as facts]
            [stay.store :as store]))

;; ───────────────────────── policy tables ─────────────────────────

(def confidence-floor 0.6)

(def permissions
  "actor-role → set of operations it may perform."
  {:property-onboarding-officer #{:property/register}
   :booking-coordinator         #{:booking/place}
   :guest-relations-officer     #{:dispute/request}
   :partner-user                #{:report/query}})

(def tier-columns
  "For `:report/query` — the columns each licensed client-billing tier may
  see. Anything beyond this is over-disclosure (licensed-disclosure
  violation), the accommodation analog of `dossier`/`marketdata`/
  `staffing`'s tier tables."
  (let [base #{:booking-id :property-id :check-in :check-out :status}
        detailed-extra #{:guest-id :guests-count}]
    {:tier/basic    base
     :tier/detailed (into base detailed-extra)}))

;; ───────────────────────── checks ─────────────────────────

(defn- rbac-violations [{:keys [op]} {:keys [actor-role]}]
  (when-not (contains? (get permissions actor-role #{}) op)
    [{:rule :rbac :detail (str actor-role " は " op " の権限を持たない")}]))

(defn- overlaps?
  "Half-open interval overlap on ISO `YYYY-MM-DD` date strings — lexical
  comparison is correct for this format."
  [check-in-a check-out-a check-in-b check-out-b]
  (and (< 0 (compare check-out-a check-in-b))
       (< 0 (compare check-out-b check-in-a))))

(defn- capacity-violations
  "The property's total capacity against every confirmed booking whose
  dates overlap this one.

  Three ways this used to pass a booking it could not actually verify,
  all the same mistake -- mapping `cannot determine` onto `safe`:

    - `(or guests-count 0)` meant a booking with NO guest count
      contributed ZERO to the occupancy and therefore always cleared
      the capacity gate;
    - a non-numeric guest count reached `+` and threw a
      ClassCastException out of the governor itself;
    - `(when (and cap ...))` skipped the check entirely when the
      property had no recorded `:capacity-total`.

  A count that is not a number cannot be added to the occupancy, and a
  property with no recorded capacity has nothing to compare against, so
  in both cases the check cannot be performed -- which is a violation,
  not a pass and not a crash. Existing bookings carrying a non-numeric
  count are likewise counted as unverifiable rather than skipped."
  [{:keys [op]} proposal st]
  (when (= op :booking/place)
    (let [{:keys [id property-id check-in check-out guests-count]} (:value proposal)
          prop (store/property st property-id)
          cap  (:capacity-total prop)
          overlapping-others
          (->> (store/bookings-of-property st property-id)
               (filter #(= :confirmed (:status %)))
               (remove #(= id (:id %)))
               (filter #(overlaps? check-in check-out (:check-in %) (:check-out %))))
          bad-existing (remove #(number? (:guests-count %)) overlapping-others)
          occupied (reduce + 0 (map :guests-count (filter #(number? (:guests-count %))
                                                          overlapping-others)))]
      (cond
        (not (number? cap))
        [{:rule :capacity-overbooking-gate
          :detail (str "property=" property-id " に capacity-total が無い(" (pr-str cap) ") -- "
                       "空室数を検算できないため受け付けない")}]

        (not (number? guests-count))
        [{:rule :capacity-overbooking-gate
          :detail (str "guests-count が数値でない(" (pr-str guests-count) ") -- "
                       "占有数に加算できないため検算できない")}]

        (seq bad-existing)
        [{:rule :capacity-overbooking-gate
          :detail (str "重複日程の既存予約 " (:id (first bad-existing))
                       " の guests-count が数値でない -- 現占有数を確定できない")}]

        (> (+ occupied guests-count) cap)
        [{:rule :capacity-overbooking-gate
          :detail (str "重複日程の占有が総capacityを超過: property=" property-id
                       " occupied+new=" (+ occupied guests-count) " > capacity=" cap)}]))))

(defn- license-lapsed-violations
  [{:keys [op]} proposal st]
  (when (= op :booking/place)
    (let [{:keys [property-id check-in]} (:value proposal)
          prop (store/property st property-id)]
      (cond
        (not= :active (:license-status prop))
        [{:rule :license-lapsed-gate
          :detail (str "property の license-status が active でない: " (:license-status prop))}]

        (and (:safety-cert-expiry prop) check-in
             (neg? (compare (:safety-cert-expiry prop) check-in)))
        [{:rule :license-lapsed-gate
          :detail (str "safety-cert-expiry が check-in より前: expiry="
                       (:safety-cert-expiry prop) " check-in=" check-in)}]

        :else nil))))

(defn- source-provenance-violations
  [{:keys [op]} proposal st]
  (when (contains? #{:property/register :booking/place} op)
    (let [src (:source proposal)]
      (cond
        (or (nil? src) (not (facts/class-allowed? (:class src))))
        [{:rule :source-provenance-gate
          :detail (str "出典が無いか許可された出典クラスでない: " (pr-str src))}]

        (facts/operator-attested-class? (:class src))
        (let [lic (store/license-record st (:license-ref src))]
          (when (or (nil? lic) (not (:active? lic)))
            [{:rule :source-provenance-gate
              :detail (str "有効な operator-registered license が無い: license-ref=" (:license-ref src))}]))

        :else nil))))

(defn- licensed-disclosure-violations
  [{:keys [op]} {:keys [tenant]} proposal st]
  (when (= op :report/query)
    (let [c (when tenant (store/contract st tenant))]
      (if (or (nil? c) (not (:active? c)))
        [{:rule :licensed-disclosure :detail (str "有効な契約が無い: tenant=" tenant)}]
        (let [allowed (get tier-columns (:tier c) #{})
              cols    (set (:columns proposal))
              extra   (set/difference cols allowed)]
          (when (seq extra)
            [{:rule :licensed-disclosure
              :detail (str "契約 tier " (:tier c) " に対し過剰な列: " (vec extra))}]))))))

(defn- rate-recompute-violations
  "RECOMPUTE the booking's total from the property's own filed rate plan
  and reject a claimed total that does not match.

  A booking is the point where money attaches to a stay, and until this
  gate existed a booking could be placed carrying no price at all, or
  any price the advisor felt like stating. This is a ground-truth
  recompute, not a restatement: the nights come from
  `kotoba.reservation/nights-between` applied to the booking's OWN
  check-in/check-out, so shortening a night list in the proposal cannot
  make a wrong total pass.

  A check that CANNOT be performed is a violation, not a pass. A
  property with no filed rate plan, a booking with no claimed total, or
  a date range that yields no nights is un-recomputable, and this
  governor does not wave through a price it was structurally unable to
  verify."
  [{:keys [op]} proposal st]
  (when (= op :booking/place)
    (let [{:keys [property-id check-in check-out guests-count quoted-total]} (:value proposal)
          prop (store/property st property-id)
          plan (:rate-plan prop)
          nights (res/nights-between check-in check-out)]
      (cond
        (nil? plan)
        [{:rule :rate-recompute-gate
          :detail (str "property=" property-id " に届出料金設定(rate plan)が無い -- 提示総額を独立に再計算できない")}]

        (nil? quoted-total)
        [{:rule :rate-recompute-gate
          :detail "予約に :quoted-total が無い -- 価格の付かない予約は受け付けない"}]

        (empty? nights)
        [{:rule :rate-recompute-gate
          :detail (str "check-in/check-out から宿泊数を確定できない: " check-in ".." check-out)}]

        (not (res/quote-matches-claim? plan {:dates nights :qty (or guests-count 1)} quoted-total))
        [{:rule :rate-mismatch-gate
          :detail (str "提示総額 " quoted-total " は料金設定からの再計算結果 "
                       (res/quote-total (res/quote-for plan {:dates nights :qty (or guests-count 1)}))
                       " と一致しない(" (count nights) "泊 x " (or guests-count 1) "名)")}]))))

(defn- guest-flagged?
  [{:keys [op]} proposal st]
  (when (= op :booking/place)
    (boolean (:flagged? (store/guest st (get-in proposal [:value :guest-id]))))))

(defn check
  "Censors a StayBooking-LLM proposal against the policy tables. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :flagged? bool
    :hard? bool :dispute? bool}.

   - :hard?       — at least one HARD violation (capacity-overbooking-gate/
                    license-lapsed-gate/source-provenance-gate/
                    licensed-disclosure). Forces HOLD; a human cannot
                    override.
   - :escalate?   — soft: low confidence, flagged guest, OR a dispute
                    request. A human decides.
   - :ok?         — clean AND not escalating: safe to auto-commit/-serve."
  [request context proposal st]
  (let [hard    (into []
                      (concat (rbac-violations request context)
                              (capacity-violations request proposal st)
                              (license-lapsed-violations request proposal st)
                              (rate-recompute-violations request proposal st)
                              (source-provenance-violations request proposal st)
                              (licensed-disclosure-violations request context proposal st)))
        conf     (:confidence proposal 0.0)
        low?     (< conf confidence-floor)
        flagged? (guest-flagged? request proposal st)
        dispute? (= :dispute/request (:op request))
        hard?    (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not flagged?) (not dispute?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? flagged? dispute?))
     :flagged?     flagged?
     :dispute?     dispute?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :policy-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})

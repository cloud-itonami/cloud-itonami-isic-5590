(ns stay.llm
  "StayBooking-LLM client — the *contained intelligence node*.

  It normalizes property-onboarding submissions, normalizes incoming
  booking requests, proposes client-report column sets, and drafts
  dispute-resolution notes. CRITICAL: it is a smart-but-untrusted advisor.
  It returns a *proposal* (with a rationale + the fields/source it cited),
  never a committed or confirmed record. Every output is censored
  downstream by `stay.policy` (the StayGovernor) before anything touches
  the SSoT or is disclosed to a partner.

  Like `cloud-itonami-isic-6311`'s MarketData-LLM and
  `cloud-itonami-isic-7820`'s TempStaffing-LLM, this is a deterministic
  mock so the actor graph runs offline and the governor contract is
  exercised end-to-end. In production this calls a real LLM (kotoba-llm)
  with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why — SCANNED by the source-provenance gate
     :cites      [kw|str ..]    ; fields/attrs the LLM used
     :source     {:class kw :ref str :license-ref str?}|nil ; SCANNED
     :effect     kw             ; how a commit would mutate the SSoT
     :value      map|nil        ; the record patch, for register/place/dispute
     :columns    [kw ..]|nil    ; proposed disclosure column set
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]
            [stay.store :as store]))

(defn- propose-register
  "Property-onboarding normalization — the LLM only normalizes/validates
  the submission (adds no new facts). `:unsourced?` injects the failure
  mode we must defend against: a property submitted with no license-basis
  citation at all — the StayGovernor's source-provenance-gate must reject
  this outright, regardless of confidence."
  [_db {:keys [id name property-type jurisdiction capacity-total
              license-status safety-cert-expiry source unsourced?]}]
  (let [src (when-not unsourced? source)]
    {:summary   (str "property register: " id " (" name ")")
     :rationale "出典引用済みのライセンス基準の正規化のみ。新規事実の生成なし。"
     :cites     [:id :name :property-type :jurisdiction :capacity-total]
     :source    src
     :effect    :property-upsert
     :value     {:id id :name name :property-type property-type :jurisdiction jurisdiction
                 :capacity-total capacity-total :license-status license-status
                 :license-basis src :safety-cert-expiry safety-cert-expiry}
     :confidence (if unsourced? 0.9 0.95)}))

(defn- propose-place
  "Booking normalization — the LLM only normalizes/validates the incoming
  request. `:unsourced?` injects the same class of failure as
  `propose-register`: a booking arriving with no channel citation at all
  (a dropped OTA webhook header) — the source-provenance-gate must reject
  it regardless of confidence."
  [_db {:keys [id property-id guest-id check-in check-out guests-count source unsourced?]}]
  (let [src (when-not unsourced? source)]
    {:summary   (str "booking place: " property-id " " check-in ".." check-out
                     " x" guests-count)
     :rationale "出典引用済み予約チャネルの正規化のみ。"
     :cites     [:property-id :guest-id :check-in :check-out :guests-count]
     :source    src
     :effect    :booking-upsert
     :value     {:id id :property-id property-id :guest-id guest-id
                 :check-in check-in :check-out check-out :guests-count guests-count
                 :status :confirmed :source src}
     :confidence (if unsourced? 0.9 0.95)}))

(defn- propose-report
  "Client report column-set proposal. `:greedy?` injects over-disclosure
  (pulls `:guest-id`/`:guests-count` columns beyond a basic-tier contract)
  — the StayGovernor's licensed-disclosure gate must reject the excess
  columns."
  [_db {:keys [property-id greedy?]}]
  (let [base [:booking-id :property-id :check-in :check-out :status]
        greedy-extra [:guest-id :guests-count]]
    {:summary   (str "開示列提案: " property-id)
     :rationale (if greedy? "分析に有用そうな列を広めに含めた。" "契約 tier に必要な最小列のみ。")
     :cites     base
     :source    nil
     :effect    :report-serve
     :columns   (if greedy? (into base greedy-extra) base)
     :confidence 0.9}))

(defn- propose-dispute
  "Booking/billing dispute resolution draft. This NEVER auto-applies —
  `stay.policy` and `stay.phase` both structurally force every
  `:dispute/request` to human review, independent of confidence."
  [_db {:keys [disputed-field claim]}]
  {:summary   (str "booking の " disputed-field " について紛争解決案ドラフト")
   :rationale (str "申立て内容: " claim "。裏取りは人間レビューで行う。")
   :cites     [disputed-field]
   :source    nil
   :effect    :dispute-apply
   :value     {:patch {disputed-field claim}}
   :confidence 0.5})

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :property/register  (propose-register db request)
    :booking/place       (propose-place db request)
    :report/query        (propose-report db request)
    :dispute/request      (propose-dispute db request)
    {:summary "未対応の操作" :rationale (str op) :cites [] :source nil
     :effect :noop :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────
;; The advisor is injected into the OperationActor, so the contained
;; intelligence node is a swap: a deterministic mock for dev/tests, or a
;; real LLM in production. Either way its output is a PROPOSAL the
;; StayGovernor still censors — the single invariant never depends on
;; which advisor ran.

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは短期/代替宿泊施設(ホステル/ゲストハウス/寮)の予約・登録"
       "アドバイザーです。与えられた事実のみに基づき、提案を1つだけ EDN "
       "マップで返します。説明や前置きは一切書かず、EDN だけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :source({:class .. :ref .. :license-ref? ..}か nil) "
       ":effect(:property-upsert|:booking-upsert|:report-serve|:dispute-apply) "
       ":value(該当マップ) :confidence(0..1)。\n"
       "重要: 出典(:source)を伴わない登録・予約は絶対に提案してはいけません。"
       "収容人数超過の妥当性判断や、ライセンス失効中施設への予約可否は"
       "あなたの責務ではありません(governor が判定します)。"))

(defn- facts-for [st {:keys [subject property-id]}]
  {:property (store/property st (or property-id subject))})

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the StayGovernor escalates/holds —
  an LLM hiccup can never auto-commit or auto-confirm."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :source nil :effect :noop :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference).
  Pass `model/anthropic-model`, an OpenAI-compatible model (Ollama/vLLM/
  kotoba), or `model/mock-model` for offline tests. `gen-opts` is
  forwarded to -generate."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record — the LLM's interpretable rationale is a
  key asset (dispute appeals, audits). Persisted to the :audit channel."
  [request proposal]
  {:t          :stayllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :source     (:source proposal)
   :confidence (:confidence proposal)})

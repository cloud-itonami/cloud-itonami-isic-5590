(ns stay.sim
  "Demo runner: push seven representative operations through one
  OperationActor and watch the StayGovernor + approval workflow earn the
  StayBooking-LLM the right to register, confirm or resolve a dispute.

    op1  新規施設登録(出典あり)                          → commit
    op2  予約が出典なし(OTA webhook欠落)                 → source-provenance REJECT → hold
    op3  開示クエリが tier/basic 契約なのに guest列を要求 → licensed-disclosure REJECT → hold
    op3a 開示クエリが未契約 tenant から                   → licensed-disclosure REJECT → hold
    op4  ライセンス失効(safety-cert-expiry超過)施設への予約 → license-lapsed-gate REJECT → hold
    op5  重複日程で収容人数超過                            → capacity-overbooking-gate REJECT → hold
    op6  要注意フラグ付きゲストの予約(出典・容量は正常)    → 人間承認へ escalate → approve → commit
    op7  予約紛争申立て(どの phase でも常に人間レビュー)   → escalate → approve → commit

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [stay.store :as store]
            [stay.operation :as op]
            [stay.facts :as facts]
            [stay.report :as report]))

(defn- line [& xs] (println (apply str xs)))

(defn- run-op!
  "Run one operation on its own thread-id. If it interrupts for human
  approval, a guest-relations officer 'approves' and we resume."
  [actor thread-id request context approve?]
  (let [res (g/run* actor {:request request :context context} {:thread-id thread-id})]
    (if (= :interrupted (:status res))
      (do (line "   ⏸  人間レビュー待ち (reason: "
                (-> res :state :audit last :reason) ")")
          (let [res2 (g/run* actor
                             {:approval {:status (if approve? :approved :rejected)
                                         :by "relations-1"}}
                             {:thread-id thread-id :resume? true})]
            (line "   ▶  " (if approve? "承認 → " "却下 → ") "disposition = "
                  (get-in res2 [:state :disposition]))
            res2))
      (do (line "   → disposition = " (get-in res [:state :disposition])
                "  (confidence " (get-in res [:state :verdict :confidence]) ")")
          res))))

(defn -main [& _]
  (let [db    (store/seed-db)
        actor (op/build db)
        onboarding {:actor-id "on-1" :actor-role :property-onboarding-officer :phase 3}
        coordinator {:actor-id "co-1" :actor-role :booking-coordinator :phase 3}
        relations {:actor-id "re-1" :actor-role :guest-relations-officer :phase 3}]

    (line "── R0 出典カバレッジ(正直な現状) ──")
    (line (pr-str (facts/coverage)))

    (line "\n── OperationActor (StayBooking-LLM sealed; StayGovernor active) ──")

    (line "\nop1  新規施設登録(出典あり)")
    (run-op! actor "op1"
             {:op :property/register :subject "prop-400" :id "prop-400"
              :name "山あいキャンプキャビン(デモ)" :property-type :camping-cabin
              :jurisdiction :jpn :capacity-total 6 :license-status :active
              :safety-cert-expiry "2027-01-01"
              :source {:class :jpn-ryokangyo-license :ref "ryokangyo:demo-400"}}
             onboarding true)

    (line "\nop2  予約 — StayBooking-LLM が出典なしで提案(OTA webhook欠落)")
    (run-op! actor "op2"
             {:op :booking/place :subject "prop-100" :id "bk-2" :property-id "prop-100"
              :guest-id "g-1" :check-in "2026-09-01" :check-out "2026-09-03" :guests-count 4
              :source {:class :direct-booking-desk :ref "desk:demo-bk-2"} :unsourced? true}
             coordinator true)

    (line "\nop3  開示クエリ(tier/basic 契約なのに guest-id/guests-count まで要求)")
    (run-op! actor "op3"
             {:op :report/query :subject "bk-1" :property-id "prop-100" :greedy? true}
             {:actor-id "pu-1" :actor-role :partner-user :tenant "tenant-ota1"} true)

    (line "\nop3a 開示クエリ(登録されていない tenant から)")
    (run-op! actor "op3a"
             {:op :report/query :subject "bk-1" :property-id "prop-100"}
             {:actor-id "pu-2" :actor-role :partner-user :tenant "tenant-ghost"} true)

    (line "\nop4  ライセンス失効(safety-cert-expiry超過)施設への予約")
    (run-op! actor "op4"
             {:op :booking/place :subject "prop-200" :id "bk-3" :property-id "prop-200"
              :guest-id "g-1" :check-in "2026-08-10" :check-out "2026-08-12" :guests-count 2
              :source {:class :direct-booking-desk :ref "desk:demo-bk-3"}}
             coordinator true)

    (line "\nop5  重複日程で収容人数超過(既存15名 + 新規8名 > capacity 20)")
    (run-op! actor "op5"
             {:op :booking/place :subject "prop-100" :id "bk-4" :property-id "prop-100"
              :guest-id "g-1" :check-in "2026-08-02" :check-out "2026-08-04" :guests-count 8
              :source {:class :direct-booking-desk :ref "desk:demo-bk-4"}}
             coordinator true)

    (line "\nop6  要注意フラグ付きゲストの予約(出典・容量・ライセンスは正常でも人間承認)")
    (run-op! actor "op6"
             {:op :booking/place :subject "prop-300" :id "bk-5" :property-id "prop-300"
              :guest-id "g-2" :check-in "2026-10-01" :check-out "2026-10-03" :guests-count 3
              :source {:class :direct-booking-desk :ref "desk:demo-bk-5"}}
             coordinator true)

    (line "\nop7  予約紛争申立て — ゲスト人数の記録誤りを是正(どの phase でも常に人間レビュー)")
    (run-op! actor "op7"
             {:op :dispute/request :subject "bk-1" :disputed-field :guests-count :claim 14}
             relations true)

    (line "\n── 開示(governor が承認した tier/basic 列のみ) ──")
    (line (pr-str (report/render-booking db "bk-1" [:booking-id :property-id :check-in :check-out :status])))

    (line "\n── 監査台帳 (append-only; 誰が・何を・どの契約/出典で register/place/開示したか) ──")
    (doseq [f (store/ledger db)]
      (line "  " (store/ledger-line f)))

    (line "\ndone.")))

# Alternative-Accommodation Actor Design — StayBooking-LLM as a contained intelligence node

ホステル/ゲストハウス/キャンプキャビン/寮級のalternative accommodation
予約サービスを、SaaS課金に依存せず OSS の actor として自前運用するための
設計。`cloud-itonami-isic-6311`/`cloud-itonami-isic-7820` が価格/派遣データ
ドメインで封じ込め+独立governor+不変台帳パターンを実装した構図を、宿泊予約
ドメインへ写像している。

## 1. 前提: なぜ actor 層が要るのか

施設登録・予約リクエストの正規化・開示列の提案は LLM で加速できる。
しかし LLM は次の理由で**登録・予約確定・紛争解決の最終権限を持てない**:

| LLM が起こしうる失敗 | この業態での帰結 |
|---|---|
| 出典なしに施設登録・予約を「提案」で確定 | 未検証データの伝播 |
| 重複日程の収容人数超過をそのまま通す | オーバーブッキング・実害 |
| ライセンス/防火認証失効中施設への予約を高確信のまま自動確定 | 安全上のリスク・法令違反 |
| 契約 tier を超えた列を開示 | 過剰開示・契約違反 |

したがって設計課題は「LLM で予約を回す」ことではなく、**「LLM を信頼境界の
内側に封じ込め、収容力・ライセンス・出典・人間レビューの層をどう被せるか」**
である。

## 2. アクター・トポロジ(監督ツリー)

```
StaySystem (root supervisor)
│
├── RegistrationActor ……… 施設登録の正規化・取込(:property/register)
├── BookingActor ……… 予約リクエストの正規化・取込(:booking/place)
│
├── OperationActor[op] … ★ 1操作 = 1 actor run; StayBooking-LLM 封じ込め ★
│     ├── StayBooking-LLM (sealed)  proposal only(src/stay/llm.cljc)
│     ├── StayGovernor             INDEPENDENT ゲート(src/stay/policy.cljc)
│     ├── Committer                 SSoT/台帳への書き込み(src/stay/store.cljc)
│     └── Recorder                   監査台帳(append-only)
│
├── ReviewActor ……… 人間レビュー(要注意フラグ・紛争申立ての interrupt を受ける)
└── DisclosureActor ……… governed read(report.cljc、契約 tier 列のみ)
```

原則:

1. **StayBooking-LLM は最下層ノードで、台帳・開示経路に直接触れない。** 出力は
   常に StayGovernor で検閲される。
2. **監督。** 子の失敗は親へ escalate し、最終的に **hold(登録/予約確定しない)**
   に倒す。robotaxi の MRC(安全停止)に相当する既定。
3. **すべてが台帳に積まれる。** 「誰が・何を・どの契約/出典で登録/予約したか」
   は監査台帳への Datalog クエリ — 監査・紛争対応が同一ファクトログから出る。

## 3. OperationActor 内部(StayBooking-LLM ラッパー)

`src/stay/operation.cljc` の langgraph-clj StateGraph として実装。
**1 run = 1 操作** — 有界で監査可能、無限内部ループを持たない。

```
intake → advise → govern → decide ─┬─ commit ───────────────────▶ commit → END
                                   ├─ escalate ─▶ request-approval ┐ [interrupt-before]
                                   │                               │ 承認/却下で resume
                                   │              approved ─▶ commit┘ / rejected ─▶ hold
                                   └─ hold ─────────────────────────────────────▶ hold → END
```

### 3.1 注入される3つの依存(すべて swap)

- **Store**(`stay.store/Store` プロトコル): `MemStore`(既定)/ `DatomicStore`。
- **Advisor**(`stay.llm/Advisor` プロトコル): `mock-advisor`(既定)/
  `llm-advisor`。応答破損時は confidence 0 の noop に落ちる。
- **Phase**(`stay.phase`、context の `:phase 0..3`): 段階導入。既定値は
  保守的な `1`(assisted、auto-commit 無し)。**`:dispute/request` はどの
  phase の `:auto` にも入らない**(恒久ゲート)。

## 4. StayGovernor(独立検閲層)

`src/stay/policy.cljc`。8チェック(5 HARD + 3 SOFT)、優先順位:

1. **rbac** — actor-role が operation の権限を持つか。
2. **capacity-overbooking-gate**(新規、業態固有) — `:booking/place` の
   新規予約が、同一施設で重複する日程の他の確定予約の合計人数と合わせて
   総capacityを超えたら拒否。半開区間の日程重複判定 + 合計人数演算という、
   他の cloud-itonami actor に存在しない業態固有の算術チェック。
3. **license-lapsed-gate**(新規、業態固有) — 対象施設の `:license-status`
   が active でない、または `:safety-cert-expiry` が check-in より前なら
   拒否。
4. **source-provenance-gate** — `:property/register`(license-basis)/
   `:booking/place`(booking channel)の出典クラスが
   `stay.facts/allowed-source-classes` に無ければ拒否。
   `:operator-attested-license` は加えてアクティブな license record を要求。
5. **licensed-disclosure** — `:report/query` は有効な契約(tenant×tier)を
   要求し、開示列が tier を超えたら拒否。
6. **確信度フロア** — `:confidence < 0.6` → escalate(soft)。
7. **guest-flagged gate** — 対象ゲストが要注意フラグ付き → 必ず人間承認。
8. **dispute-request** — `:dispute/request` は常に escalate(soft だが
   confidence に関わらず無条件)。

## 5. SSoT と監査台帳

`src/stay/store.cljc`。entities: `properties`(license-status/
safety-cert-expiry含む) `bookings`(capacity-overbooking-gate の対象)
`guests`(flagged?) `licenses`(operator-attested-license の裏付け)
`contracts`(partner licensing)。ledger は append-only。

## 6. デモ(`clojure -M:dev:run`)

`src/stay/sim.cljc` が7操作を actor に通す(§sim.cljc docstring 参照):
正当な施設登録 → commit、出典なし予約 → hold、tier超過/未契約の開示 →
hold ×2、ライセンス失効施設への予約 → hold、収容人数超過予約 → hold、
要注意フラグ付きゲストの予約 → 人間承認 → commit、紛争申立て → 常に
人間承認 → commit。

## 7. テスト(`clojure -M:dev:test`)

`test/stay/policy_contract_test.clj` が**ガバナンス契約を実行可能**にする。
`test/stay/phase_test.clj` が段階導入と「紛争は恒久的に人間専用」を保証。
`test/stay/facts_test.clj` が出典カタログ自体の正直さ(捏造禁止)を保証。

## 8. 実装と業態の対応

| 実在業態の機能 | stay actor での実体 |
|---|---|
| 施設マスタ・ライセンス管理 | `store` properties + `:property/register` |
| 予約管理 | `store` bookings + `:booking/place` |
| オーバーブッキング防止 | capacity-overbooking-gate(日程重複算術) |
| 防火/安全認証の失効管理 | license-lapsed-gate |
| ゲスト要注意リスト | `store` guests `:flagged?` + guest-flagged gate |
| パートナー向けレポーティング | `report/render-booking`(tier 列限定) |
| ゲスト/精算紛争 | `:dispute/request`(恒久 human-only) |
| (SaaS/従来ベンダーと同型)監査台帳 | `store` append-only ledger |

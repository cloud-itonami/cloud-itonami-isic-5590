# ADR-0001: cloud-itonami-isic-5590 — StayBooking-LLM を封じ込めた知能ノードとする代替宿泊予約アクター設計

- Status: Accepted (2026-07-10)
- 関連: `cloud-itonami-isic-6311`(MarketData-LLM を MarketDataGovernor で
  封じ込める構図、tolerance-gate 等ドメイン固有 HARD チェックの先例)、
  `cloud-itonami-isic-7820`(TempStaffing-LLM を StaffingGovernor で封じ込め
  る構図、default-phase=1 の恒久保守的既定)、`cloud-itonami-isic-8291`
  (封じ込め+独立governor+不変台帳パターンの直接の手本)
- 文脈: com-junkawasaki/root superproject ADR(本 ADR の対、経緯・スコープ
  決定の全文はそちら)

## 課題

`kotoba-lang/industry` registry の未着手 `:spec` スロットから、ISIC Rev.4
5590「Other accommodation」(ホステル/ゲストハウス/キャンプキャビン/寮 —
ホテル業(5510)・Airbnb型の丸ごと貸し(通常別コード)とは区別される)を
選定した。フィード正規化・予約リクエストの正規化には LLM が有効だが、
**LLM に施設登録・予約確定・紛争解決を直接行わせるのは危険**である(出典
なきデータの断定、重複日程での収容人数超過=オーバーブッキング、ライセンス/
防火認証失効中施設への自動予約確定=安全上のリスク)。したがって設計課題は
「LLM で予約を回す」ことではなく、**「LLM を信頼境界の内側に封じ込め、
収容力・ライセンス・出典・人間レビューの層をどう被せるか」**である。

## 決定

### 1. StayBooking-LLM は最下層の1ノードに封じ込め、直接登録/確定/解決させない

> **StayBooking-LLM は、StayGovernor が拒否する施設登録・予約確定・
> 紛争解決を決して行わない。**

### 2. StayGovernor は8チェック(5 HARD + 3 SOFT)

capacity-overbooking-gate(日程重複算術による収容力超過検知)と
license-lapsed-gate(ライセンス/防火認証失効検知)の2つを、他の
cloud-itonami actor に存在しない業態固有の HARD チェックとして新設した。
詳細は `docs/DESIGN.md` §4。

### 3. default-phase = 1(恒久保守的既定)

`cloud-itonami-isic-6311`/`isic-7820` で確立された「`:phase` 省略時は
最も保守的な既定にする」規律を、新規実装時点から適用した。

### 4. R0 の正直なスコープ(捏造禁止)

出典カタログ(`src/stay/facts.cljc`)は実在する2つの statutory 制度(日本
旅館業法・英国 Fire Safety Order 2005)+ 1つの構造的クラス
`:operator-attested-license`(他法域は operator 登録の実ライセンスのみ受理)
+ 2つの実在 booking channel クラス。

### 5. Robotics premise: false

施設登録・予約管理は書面/システム上の業務であり、actor の境界の外に物理的
な作動(実際の宿泊自体)は存在しない。

## Consequences

- (+) `kotoba-lang/industry` registry の 5590 スロットが実装へ昇格。
- (+) capacity-overbooking-gate という、実際の日程重複・人数算術を伴う、
  他の cloud-itonami actor に存在しないチェックを新設した。
- (+) `clojure -M:dev:test`/`clojure -M:lint`/`clojure -M:dev:run` を
  ローカルで実行し合格を確認済み(詳細は superproject ADR の Consequences
  節)。
- (-) R0 のライセンス regime は2法域のみ。他法域は operator の
  license-record 登録が必須。
- (-) Datomic/kotoba-server backend は次のシーム(未接続)。

## 代替案と不採用理由

- **LLM に登録・確定権限を直接付与(エージェント自律)**: 出典なき断定・
  オーバーブッキング・失効施設への予約確定を構造的に防げない。単一不変
  条件(決定1)に反する。
- **capacity-overbooking-gate を SOFT にとどめる**: 物理的な収容力超過は
  人間承認で事後的に許容できる性質のものではない(実際に部屋/ベッドが
  足りない)。HARD が必須と判断した。

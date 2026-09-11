(ns stay.report
  "Disclosure rendering — output as a GOVERNED read. The column set is not
  chosen here; it is whatever the StayGovernor's licensed-disclosure gate
  approved for the caller's contract tier (see `:report/query`). This
  namespace only renders the approved columns, so a disclosure can never
  exceed the licensed tier."
  (:require [stay.store :as store]))

(defn render-booking
  "Render one booking's report over exactly `columns` (already governor-
  approved)."
  [db booking-id columns]
  (let [b (store/booking db booking-id)
        cell (fn [col] (case col :booking-id booking-id (get b col)))]
    (into {} (map (juxt identity cell)) columns)))

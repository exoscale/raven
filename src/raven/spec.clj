(ns raven.spec
  "specifications for the wire JSON structure when talking to sentry."
  (:refer-clojure :exclude [assert])
  (:require [clojure.spec.alpha :as s]
            [expound.alpha      :as expound]))

; We declare the message spec in the raven.client namespace to allow easy
; reference from there (simply "::payload" when using the spec).
(s/def :raven.client/payload (s/keys :req-un [::level ::server_name ::timestamp ::platform]))

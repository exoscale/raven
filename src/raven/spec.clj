(ns raven.spec
  "Specifications for the wire JSON structure when talking to sentry."
  (:require [clojure.spec.alpha :as s]))

(def valid-levels
  "A list of valid message levels."
  ["debug" "info" "warning" "warn" "error" "exception" "critical" "fatal"])

(def valid-types
  "A list of valid breadcrumbs types."
  ;; TODO: support the other types of breadcrumbs as well.
  ;;["default" "navigation" "http"]
  ["default"])

(defn is-valid-type?
  [typ]
  (some #(= % typ) valid-types))

(defn is-valid-level?
  [lvl]
  (some #(= % lvl) valid-levels))

(defn is-valid-platform?
  "Only one platform choice is valid for clojure."
  [platform]
  (= platform "java"))

;; timestamp is expected to be "the number of seconds since the epoch", with a
;; precision of a millisecond.
(s/def ::timestamp float?)
(s/def ::type is-valid-type?)
(s/def ::level is-valid-level?)
(s/def ::message string?)
(s/def ::sever_name string?)
(s/def ::culprit string?)
(s/def ::platform is-valid-platform?)
(s/def ::headers map?)
(s/def ::env map?)
(s/def ::breadcrumb (s/keys :req-un [::type ::timestamp ::level ::message ::category]))
(s/def ::values (s/coll-of ::breadcrumb))
(s/def ::breadcrumbs (s/keys :req-un [::values]))
(s/def ::user (s/keys :req-un [::id] :opt-un [::username ::email ::ip_address]))
(s/def ::request (s/keys :req-un [::method ::url] :opt-un [::query_string ::cookies ::headers ::env ::data]))

;; We declare the message spec in the raven.client namespace to allow easy
;; reference from there (simply "::payload" when using the spec).
(s/def :raven.client/payload (s/keys :req-un [::event_id ::culprit ::level ::server_name ::timestamp ::platform] :opt-un [::breadcrumbs ::user ::request]))

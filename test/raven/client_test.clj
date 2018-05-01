(ns raven.client-test
  (:require [clojure.test :refer :all]
            [raven.client :refer :all]))

(def dsn-fixture
  "https://098f6bcd4621d373cade4e832627b4f6:ad0234829205b9033196ba818f7a872b@sentry.example.com/42")

(def expected-parsed-dsn
  {:key "098f6bcd4621d373cade4e832627b4f6"
   :secret "ad0234829205b9033196ba818f7a872b"
   :uri "https://sentry.example.com"
   :pid 42})

(def expected-sig
  "da3edd4ce3c55f1552fb01108f974a1181513323")

(def frozen-ts
  "2018-05-01 16:20:44.955")

(def expected-header
  (str "Sentry sentry_version=2.0, sentry_signature=" expected-sig ", sentry_timestamp=" frozen-ts ", sentry_client=spootnik-raven/0.1.4, sentry_key=" (:key expected-parsed-dsn)))

(deftest raven-client-tests
  (testing "parsing DSN"
    (is (= (parse-dsn dsn-fixture) expected-parsed-dsn)))

  (testing "signing"
    (is (= (sign "payload" frozen-ts (:key expected-parsed-dsn) (:secret expected-parsed-dsn)) expected-sig)))

  (testing "the auth header is what we expect"
    (is (= (auth-header frozen-ts (:key expected-parsed-dsn) expected-sig) expected-header))))

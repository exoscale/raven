(ns raven.dsn-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [raven.client :as client]
            [raven.spec :as spec]))

(deftest test-dsn
  (testing "dsn parsing (deprecated)"
    (let [dsn "https://123a5fdad8f086af639588ca3420b959:1234bd9f1023d0b337da52265365581b@o1288209.ingest.sentry.io/1234567890398208"]
      (is (some? (client/parse-dsn dsn))))

    (testing "dsn spec validation"
      (let [old-dsn "https://123a5fdad8f086af639588ca3420b959:1234bd9f1023d0b337da52265365581b@o1288209.ingest.sentry.io/1234567890398208"
            new-dsn "https://123a5fdad8f012af123456ca3420b123@o1288209.ingest.sentry.io/1234567890398208"
            inmem  ":memory:"]

        (is (= ::spec/deprecated-dsn (first (s/conform ::spec/dsn old-dsn))))
        (is (= ::spec/dsn (first (s/conform ::spec/dsn new-dsn))))
        (is (= ::spec/in-memory-dsn (first (s/conform ::spec/dsn inmem))))

        (is (not (s/valid? ::spec/dsn "http://asd@")))))))



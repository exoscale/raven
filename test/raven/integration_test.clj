(ns raven.integration-test
  (:require [clojure.test :refer :all]
            [raven.client :refer :all]))

(def http-info-map
  (make-http-info "http://example.com" "POST" {:Content-Type "text/html"} "somekey=somevalue" "somecookie=somevalue" "some POST data. This might be BIG!" {:some-env "a value"}))

(defn get-dsn
  []
  (let [dsn (System/getenv "DSN")]
    (when (nil? dsn)
      (throw (Exception. "Please provide a 'DSN' environment variable with a valid DSN.")))
    dsn))

(deftest ^:integration-test raven-integration-test
  (testing "Sending out a test sentry entry."
    (add-breadcrumb! (make-breadcrumb! "The user did something" "category.1"))
    (add-breadcrumb! (make-breadcrumb! "The user did something else" "category.1"))
    (add-breadcrumb! (make-breadcrumb! "The user did something bad" "category.2" "error"))
    (add-user! (make-user "123456" "huginn@example.com" "127.0.0.1" "Huginn"))
    (add-http-info! http-info-map)
    (capture! (get-dsn) (Exception. "Test exception") {:arbitrary-tag "arbitrary-value"})
    ;; We sleep for a second since otherwise the process dies before the request had time to fly out
    ;; to sentry (since it's asynchronous and therefore doesn't block the main thread).
    (Thread/sleep 1000)))

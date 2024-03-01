(ns raven.integration-test
  (:require [clojure.test :refer :all]
            [raven.client :refer :all]
            [raven.spec])
  (:import [io.sentry Sentry$OptionsConfiguration SentryEvent]))

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
    (let [ex (Exception. "Test exception")]
      (add-breadcrumb! (make-breadcrumb! "The user did something" "category.1"))
      (add-breadcrumb! (make-breadcrumb! "The user did something else" "category.1"))
      (add-breadcrumb! (make-breadcrumb! "The user did something bad" "category.2" "error"))
      (add-user! (make-user "123456" "huginn@example.com" "127.0.0.1" "Huginn"))
      (add-tag! :integration-test-pool "default")
      (add-tag! :integration-test-context "thread-local")
      (add-http-info! http-info-map)
      (capture! (get-dsn) ex {:arbitrary-tag "arbitrary-value"})

      (let [[^SentryEvent evt] @sentry-captures-stub]
        (testing "breadcrumbs"
          (let [[b1 b2 b3] (->> (.getBreadcrumbs evt) (sort-by #(.getMessage %)))]
            (is (= "The user did something" (.getMessage b1)))
            (is (= "category.1" (.getCategory b1)))

            (is (= "The user did something bad" (.getMessage b2)))
            (is (= "category.2" (.getCategory b2)))

            (is (= "The user did something else" (.getMessage b3)))
            (is (= "category.1" (.getCategory b3)))))

        (testing "user"
          (let [user (.getUser evt)]
            (is (= "123456" (.getId user)))
            (is (= "huginn@example.com" (.getEmail user)))
            (is (= "127.0.0.1" (.getIpAddress user)))
            (is (= "Huginn" (.getUsername user)))))

        (testing "tags"
          (let [tags (.getTags evt)]
            (is (= {"arbitrary-tag" "arbitrary-value", "integration-test-pool" "default", "integration-test-context" "thread-local"}
                   tags))))

        (testing "request"
          (let [req (.getRequest evt)]
            (is (= "http://example.com" (.getUrl req)))
            (is (= "POST" (.getMethod req)))
            (is (= "somekey=somevalue" (.getQueryString req)))
            (is (= "\"some POST data. This might be BIG!\"" (.getData req)))
            (is (= "somecookie=somevalue" (.getCookies req)))
            (is (= {"Content-Type" "text/html"} (.getHeaders req)))
            (is (= {"some-env" "a value"} (.getEnvs req)))
            (is (nil? (.getOthers req)))
            (is (nil? (.getApiTarget req)))))

        (testing "exception"
          (let [x (.getThrowable evt)]
            (is (= ex x))))))

    (clear-captures-stub)))

(comment
  (import '[io.sentry Sentry Sentry$OptionsConfiguration])
  (Sentry/init (reify Sentry$OptionsConfiguration
                 (configure [this opt]
                   (.setEnableExternalConfiguration opt true))))


  (capture! (System/getenv "SENTRY_DSN") (RuntimeException. "some text") {:arbitrary-tag "arbitrary-value"})

  (capture! (System/getenv "SENTRY_DSN") (ex-info "some text" {:some "data"}) {:arbitrary-tag "arbitrary-value"})

  (let [ex (Exception. "Test complete exception" (RuntimeException. "Cause"))]
    (add-breadcrumb! (make-breadcrumb! "The user did something" "category.1"))
    (add-breadcrumb! (make-breadcrumb! "The user did something else" "category.1"))
    (add-breadcrumb! (make-breadcrumb! "The user did something bad" "category.2" "error"))
    (add-user! (make-user "123456" "huginn@example.com" "127.0.0.1" "Huginn"))
    (add-tag! :integration-test-pool "default")
    (add-tag! :integration-test-context "thread-local")
    (add-http-info! http-info-map)
    (capture! (System/getenv "TEST_DSN") ex {:arbitrary-tag "arbitrary-value"}))
  "")
(ns raven.client-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [raven.client :refer :all]
            [jsonista.core :as json]
            [manifold.deferred :as d]
            [clojure.edn :as edn])
  (:import [manifold.deferred
            SuccessDeferred Deferred]))

(def dsn-fixture
  "https://098f6bcd4621d373cade4e832627b4f6:ad0234829205b9033196ba818f7a872b@sentry.example.com/42")

(def expected-parsed-dsn
  {:key "098f6bcd4621d373cade4e832627b4f6"
   :secret "ad0234829205b9033196ba818f7a872b"
   :uri "https://sentry.example.com"
   :pid 42})

(def expected-sig
  "75e297d21055bbd1b51229f266d71701e1b70e68")

(def frozen-ts
  1525265277.63)

(def frozen-uuid
  "a059419cd1bd46a685b95080f260aed4")

(def frozen-servername
  "Muninn")

(def frozen-request
  "A frozen Ring request object"
  {:remote-addr "127.0.0.1"
   :params {}
   :route-params {}
   :headers {"accept" "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
             "accept-encoding" "gzip, deflate"
             "accept-language" "en-GB,en;q=0.5"
             "connection" "keep-alive"
             "cookie" "csrftoken=somecsrfcookie; blah=something"
             "host" "localhost:8080"
             "upgrade-insecure-requests" "1"
             "user-agent" "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:60.0) Gecko/20100101 Firefox/60.0"}
   :server-port 8080
   :content-length 0
   :websocket? false
   :content-type nil
   :character-encoding "utf8"
   :uri "/example"
   :server-name "localhost"
   :query-string nil
   :body nil
   :scheme :http
   :request-method :get})

(def expected-test-url
  "http://localhost:8080/example")

(def expected-message
  "a test message")

(def expected-header
  (str "Sentry sentry_version=2.0, sentry_signature=" expected-sig ", sentry_timestamp=" frozen-ts ", sentry_client=" user-agent ", sentry_key=" (:key expected-parsed-dsn)))

(def expected-user-id
  "Huginn")

(def expected-payload
  {:level "error"
   :server_name frozen-servername
   :timestamp frozen-ts
   :platform "java"
   :event_id frozen-uuid
   :message expected-message})

(def payload-validation-keys
  [:level :server_name :culprit :timestamp :platform :event_id :project :message])

(def expected-breadcrumb
  {:type "default"
   :timestamp frozen-ts
   :level "info"
   :message "message"
   :category "category"})

(def expected-fingerprint
  ["Huginn" "og" "Muninn"])

(def simple-http-info
  {:url "http://example.com"
   :method "POST"})

(defn reset-storage
  "A fixture to reset the per-thread atom between tests."
  [f]
  (f)
  (clear-context)
  (clear-http-stub))

(use-fixtures :each reset-storage)

(defn assert-equal-for-key
  [current-key payload reference]
  (= (current-key payload) (current-key reference)))

(defn check-payload
  "Validate the payload against the expected payload."
  [expected-payload payload]
  (reduce #(and %1 %2) (map #(assert-equal-for-key % payload expected-payload) payload-validation-keys)))

(defn make-test-payload
  [context]
  (payload (assoc context :event_id frozen-uuid) expected-message frozen-ts frozen-servername {}))

(deftest raven-client-tests
  (testing "parsing DSN"
    (is (= (parse-dsn dsn-fixture) expected-parsed-dsn)))

  (testing "signing"
    (is (= (sign (.getBytes "payload") frozen-ts (:key expected-parsed-dsn) (:secret expected-parsed-dsn)) expected-sig)))

  (testing "the auth header is what we expect"
    (is (= (auth-header frozen-ts (:key expected-parsed-dsn) expected-sig) expected-header)))

  (testing "the payload is constructed from a map"
    (is (check-payload expected-payload (make-test-payload {}))))

  (testing "the payload is constructed from a string"
    (is (check-payload expected-payload (make-test-payload {}))))

  (testing "getting a full ring URL"
    (is (= expected-test-url (get-full-ring-url frozen-request))))

  (testing "contexts are provided in the payload"
    (is (= (get-contexts) (:contexts (make-test-payload {}))))))

(deftest gather-breadcrumbs
  (testing "we can gather breadcrumbs"
    (add-breadcrumb! (make-breadcrumb! (:message expected-breadcrumb) (:category expected-breadcrumb) (:level expected-breadcrumb) frozen-ts))
    (is (= [expected-breadcrumb] (:breadcrumbs @@thread-storage)))))

(deftest add-breadcrumbs
  (testing "breadcrumbs are added to the payload"
    (add-breadcrumb! (make-breadcrumb! (:message expected-breadcrumb) (:category expected-breadcrumb) (:level expected-breadcrumb) frozen-ts))
    (is (= expected-breadcrumb (first (:values (:breadcrumbs (make-test-payload @@thread-storage))))))))

(deftest multi-breadcrumbs
  (testing "adding several breadcrumbs to the payload"
    (add-breadcrumb! (make-breadcrumb! (:message expected-breadcrumb) (:category expected-breadcrumb) (:level expected-breadcrumb) frozen-ts))
    (add-breadcrumb! (make-breadcrumb! (:message expected-breadcrumb) (:category expected-breadcrumb) (:level expected-breadcrumb) frozen-ts))
    (is (= 2 (count (:values (:breadcrumbs (make-test-payload @@thread-storage))))))))

(deftest multi-thread
  (testing "breadcrumbs are thread local"
    (add-breadcrumb! (make-breadcrumb! (:message expected-breadcrumb) (:category expected-breadcrumb) (:level expected-breadcrumb) frozen-ts))
    (add-breadcrumb! (make-breadcrumb! (:message expected-breadcrumb) (:category expected-breadcrumb) (:level expected-breadcrumb) frozen-ts))
    (is (nil? @(future (:breadcrumbs (make-test-payload @@thread-storage)))))))

(deftest manual-context
  (testing "breadcrumbs are sent using a manual context."
    (let [context {:breadcrumbs [(make-breadcrumb! (:message expected-breadcrumb) (:category expected-breadcrumb) (:level expected-breadcrumb) frozen-ts)]}]
      (is (= expected-breadcrumb (first (:values (:breadcrumbs (make-test-payload context)))))))))

(deftest multi-breadcrumbs-in-manual-context
  (testing "multiple breadcrumbs are sent using a manual context."
    (let [context {:breadcrumbs [(make-breadcrumb! (:message expected-breadcrumb) (:category expected-breadcrumb) (:level expected-breadcrumb) frozen-ts) (make-breadcrumb! (:message expected-breadcrumb) (:category expected-breadcrumb))]}]
      (is (= expected-breadcrumb (first (:values (:breadcrumbs (make-test-payload context))))))
      (is (= 2 (count (:values (:breadcrumbs (make-test-payload context)))))))))

(deftest add-user
  (testing "user is added to the payload"
    (add-user! (make-user expected-user-id))
    (is (= expected-user-id (:id (:user (make-test-payload @@thread-storage)))))))

(deftest manual-user
  (testing "user is sent using a manual context"
    (let [context {:user (make-user expected-user-id)}]
      (is (= expected-user-id (:id (:user (make-test-payload context))))))))

(deftest add-request
  (testing "http information is added to the payload"
    (add-http-info! (make-http-info (:url simple-http-info) (:method simple-http-info)))
    (is (= simple-http-info (:request (make-test-payload @@thread-storage))))))

(deftest manual-request
  (testing "http information is sent using a manual context"
    (let [context {:request (make-http-info (:url simple-http-info) (:method simple-http-info))}]
      (is (= simple-http-info (:request (make-test-payload context)))))))

(deftest add-fingerprint
  (testing "fingerprints can be added to the payload"
    (add-fingerprint! expected-fingerprint)
    (is (= expected-fingerprint (:fingerprint (make-test-payload @@thread-storage))))))

(deftest manual-fingerprint
  (testing "fingerprints are sent using a manual context"
    (let [context (add-fingerprint! {} expected-fingerprint)]
      (is (= expected-fingerprint (:fingerprint (make-test-payload context)))))))

(deftest capture-with-subbing
  (testing "we can capture payloads with in-memory stubbing."
    (capture! ":memory:" {:message "This is a stub message"})
    (is (= "This is a stub message" (:message (first @http-requests-payload-stub))))))

(deftest capture-returns-uuid
  (testing "capturing an event returns the event's uuid"
    (let [out @(capture! ":memory:" {:message "whatever"})]
      (is (= 32 (count out)))
      (is (string? out)))))

(deftest capture-without-tags
  (testing "we don't add a tags key if no tags are specified"
    (capture! ":memory:" {:message "This is a stub message"})
    (is (not (contains? (first @http-requests-payload-stub) :tags)))))

(deftest capture-without-users
  (testing "we don't add a user key if no user is specified"
    (capture! ":memory:" {:message "This is a stub message"})
    (is (not (contains? (first @http-requests-payload-stub) :user)))))

(deftest capture-with-inline-tags
  (testing "tags are added if they are passed during capture"
    (capture! ":memory:" {:message "This is a stub message"} {:feather_color "black"})
    (is (= {:feather_color "black"} (:tags (first @http-requests-payload-stub))))))

(deftest capture-with-context-tags
  (testing "tags added by context are passed during capture"
    (add-tag! :feather_color "black")
    (capture! ":memory:" (Exception.))
    (is (= {:feather_color "black"} (:tags (first @http-requests-payload-stub)))))
  (testing "tags added by context are passed during capture"
    (add-tags! {:env "prod" :feather_color "black"})
    (capture! ":memory:" (Exception.))
    (is (= {:feather_color "black" :env "prod"} (:tags (second @http-requests-payload-stub))))))

(deftest capture-tags-override-context
  (testing "tags added by context are overriden by inline tags"
    (add-tag! :feather_color "black")
    (capture! ":memory:" (Exception.) {:feather_color "svartur"})
    (is (= {:feather_color "svartur"} (:tags (first @http-requests-payload-stub))))))

(deftest ring-request-composure
  (testing "passing a ring request to Sentry with compojure"
    (add-http-info! (make-ring-request-info (assoc frozen-request :compojure/route [:get "/example"])))
    (capture! ":memory:" expected-message)
    (is (= (:url (:request (first @http-requests-payload-stub))) expected-test-url))
    (is (= (get-in (first @http-requests-payload-stub) [:request :env :compojure/route]) [:get "/example"]))))

(deftest ring-request-no-composure
  (testing "passing a ring request to Sentry with no compojure"
    (add-http-info! (make-ring-request-info frozen-request))
    (capture! ":memory:" expected-message)
    (is (= (:url (:request (first @http-requests-payload-stub))) expected-test-url))
    (is (= (get-in (first @http-requests-payload-stub) [:request :env :compojure/route]) nil))))

(deftest ring-request-query-string
  (testing "passing a ring request to Sentry with a query string"
    (add-http-info! (make-ring-request-info (assoc frozen-request :query-string "name=munnin")))
    (capture! ":memory:" expected-message)
    (is (= (:url (:request (first @http-requests-payload-stub))) (str expected-test-url "?name=munnin")))
    (is (= (get-in (first @http-requests-payload-stub) [:request :query_string]) "name=munnin"))))

(deftest ring-request-no-params
  (testing "passing a ring request does not forward :params to sentry"
    (add-ring-request! (assoc frozen-request :params {:something "blah"}))
    (capture! ":memory:" expected-message)
    (is (nil? (:params (:env (:request (first @http-requests-payload-stub))))))))

(deftest no-http-client-in-context
  (testing "unused keys in context don't end up in payload"
    (let [context {:http_client "something"}]
      (is (= nil (:http_client (make-test-payload context)))))))

(deftest composed-ring-request
  (testing "the composition add-ring-request! adds a ring request to the payload"
    (add-full-ring-request! frozen-request)
    (capture! ":memory:" expected-message)
    (is (= (:url (:request (first @http-requests-payload-stub))) expected-test-url))))

(deftest top-level-copmosition
  (testing "events can be produced by threading top-level functions"
    (capture! ":memory:" (-> {}
                             (add-breadcrumb! expected-breadcrumb)
                             (add-user! (make-user expected-user-id))
                             (add-full-ring-request! frozen-request)
                             (add-exception! (Exception.))
                             (add-tag! :feather_color "black")
                             (add-tag! :beak_color "black")))
    (is (= (:url (:request (first @http-requests-payload-stub))) expected-test-url))
    (is (= "black" (:feather_color (:tags (first @http-requests-payload-stub)))))
    (is (= "black" (:beak_color (:tags (first @http-requests-payload-stub)))))
    (is (= expected-user-id (:id (:user (first @http-requests-payload-stub)))))
    (is (= expected-breadcrumb (first (:values (:breadcrumbs (first @http-requests-payload-stub))))))))

(deftest passing-sentry-id
  (testing "passing an outside sentry ID will forward it to the server"
    (capture! ":memory:" (-> {:event_id "abcd"}
                             (add-exception! (Exception.))))
    (is (= "abcd" (:event_id (first @http-requests-payload-stub))))))


(deftest exception-sign
  (testing "Fixate the way we build a string to hash"

    (let [e (ex-info "error1" {:field1 1
                               :type ::special-error}
                     (new Exception "error2"
                          (ex-info "error3" {:field3 3})))

          sign (-> e Throwable->map raven.exception/ex-map->sign)

          lines [":raven.client-test/special-error"
                 "java.lang.Exception:error2"
                 "clojure.lang.ExceptionInfo:error3"]]

      (is (= sign (str/join \newline lines))))))


(deftest test-capture-result
  (let [result (capture! ":memory:" {:event_id "abcd"})]
    (is (instance? SuccessDeferred result))))


(deftest test-capture-http-exception
  (with-redefs [aleph.http/post
                (fn [& _]
                  (d/future
                    (throw (new Exception "boom"))))]
    (let [result (capture! "https://xxx:yyy@example.com/999" {:event_id "abcd"})]
      (is (instance? Deferred result))
      (is (thrown-with-msg? Exception #"boom" @result)))))


(deftest exception-structure

  (testing "Fixate the exceptions's structure"

    (let [e (ex-info "ex1" {:field1 1}
                     (ex-info "ex2" {:field2 2}))

          context (add-exception! nil e)]

      (capture! ":memory:" context))

    (let [fields [:message :culprit :checksum :extra :exception]
          submap (-> @http-requests-payload-stub
                     first
                     (select-keys fields)
                     (update-in [:extra :via]
                                (fn [via]
                                  (mapv #(dissoc % :at) via))))]

      (is (= submap
             '{:message "ex1"
               :culprit "clojure.lang.ExceptionInfo"
               :checksum "1364801774"
               :extra
               {:via
                [{:type clojure.lang.ExceptionInfo
                  :message "ex1"
                  :data {:field1 1}}
                 {:type clojure.lang.ExceptionInfo
                  :message "ex2"
                  :data {:field2 2}}]}
               :exception
               {:values
                [{:type "clojure.lang.ExceptionInfo" :value "ex1"}
                 {:type "clojure.lang.ExceptionInfo" :value "ex2"}]}})))))

(deftest exception-preserves-extra
  (testing "Adding an exception to the context saves old extra"

    (let [e (ex-info "ex1" {:field1 1}
                     (ex-info "ex2" {:field2 2}))

          context (-> nil
                      (add-extra! {:aaa 1})
                      (add-exception! e)
                      (add-extra! {:bbb 2}))]

      (capture! ":memory:" context))

    (let [extra (-> @http-requests-payload-stub
                    first
                    :extra)]

      (is (= (dissoc extra :via)
             {:aaa 1 :bbb 2}))

      (is (-> extra :via vector?)))))


(defrecord Foo [b])
(deftest test-json-serializer
  (is (thrown? Exception (json/write-value-as-bytes (Object.)))
      "throws without our mapper")
  (is (json/write-value-as-bytes (Object.)
                                 json-mapper)
      "pass trough with our mapper")

  (is (= "{\"a\":{\"b\":{}}}"
         (String. (json/write-value-as-bytes {:a (Foo. (java.lang.Exception. "yolo"))}
                                             json-mapper)))
      "don't do (bean x) on unknown values")
  (is (= "\"#exoscale/safe-map [:a :b]\""
         (json/write-value-as-string (safe-map {:a 1 :b 2})
                                     json-mapper)))
  (is (= [:a :b] (edn/read-string {:readers {'exoscale/safe-map identity}}
                                  (with-out-str (pr (safe-map {:a 1 :b 2})))))
      "make sure it's edn readable"))

(ns raven.client
  "A netty-based Sentry client."
  (:import java.lang.Throwable
           java.lang.Exception)
  (:require [aleph.http            :as http]
            [manifold.deferred     :as d]
            [jsonista.core         :as json]
            [clojure.string        :as str]
            [clojure.spec.alpha    :as s]
            [clojure.java.shell    :as sh]
            [raven.spec            :as spec]
            [raven.exception       :as e]
            [flatland.useful.utils :as useful])
  (:import java.io.Closeable
           (com.fasterxml.jackson.databind MapperFeature
                                           SerializationFeature)))

(def user-agent
  "Our advertized UA"
  "exoscale-raven/0.4.4")

;; Make sure we enforce spec assertions.
(s/check-asserts true)

(def thread-storage
  "Storage for this particular thread.

  This is a little funny in that it needs to be dereferenced once in order to
  obtain an atom that is sure to be per-thread."
  (useful/thread-local (atom {})))

(def http-requests-payload-stub
  "Storage for stubbed http 'requests' - actually, we store just the request's
  payload in clojure form (before it's serialized to JSON)."
  (atom []))

(defn clear-http-stub
  "A convenience function to clear the http stub.

    This stub is only used when passing a DSN of ':memory:' to the lib."
  []
  (swap! http-requests-payload-stub (fn [x] (vector))))

(defn clear-context
  "Reset this thread's context"
  []
  (reset! @thread-storage {}))

(defn sentry-uuid!
  "A random UUID, without dashes"
  []
  (str/replace (str (java.util.UUID/randomUUID)) "-" ""))

(def hostname-refresh-interval
  "How often to allow reading /etc/hostname, in seconds."
  60)

(defn get-hostname
  "Get the current hostname by shelling out to 'hostname'"
  []
  (or
   (try
     (let [{:keys [exit out]} (sh/sh "hostname")]
       (if (= exit 0)
         (str/trim out)))
     (catch Exception _))
   "<unknown>"))

(defn hostname
  "Fetches the hostname by shelling to 'hostname', whenever the given age
  is stale enough. If the given age is recent, as defined by
  hostname-refresh-interval, returns age and val instead."
  [[age val]]
  (if (and val (<= (* 1000 hostname-refresh-interval)
                   (- (System/currentTimeMillis) age)))
    [age val]
    [(System/currentTimeMillis) (get-hostname)]))

(let [cache (atom [nil nil])]
  (defn localhost
    "Returns the local host name."
    []
    (if (re-find #"^Windows" (System/getProperty "os.name"))
      (or (System/getenv "COMPUTERNAME") "localhost")
      (or (System/getenv "HOSTNAME")
          (second (swap! cache hostname))))))

(def dsn-pattern
  "The shape of a sentry DSN"
  #"^(https?)://([^:]*):([^@]*)@(.*)/([0-9]+)$")

(defn parse-dsn
  "Extract DSN parameters into a map"
  [dsn]
  (if-let [[_ proto key secret uri ^String pid] (re-find dsn-pattern dsn)]
    {:key    key
     :secret secret
     :uri    (format "%s://%s" proto uri)
     :pid    (Long. pid)}
    (throw (ex-info "could not parse sentry DSN" {:dsn dsn}))))

(defn default-payload
  "Provide default values for a payload."
  [ts localhost]
  {:level       "error"
   :server_name localhost
   :timestamp   ts
   :platform    "java"})

(defn auth-header
  "Compute the Sentry auth header."
  [ts key sig]
  (let [params [[:version   "2.0"]
                [:signature sig]
                [:timestamp ts]
                [:client    user-agent]
                [:key       key]]
        param  (fn [[k v]] (format "sentry_%s=%s" (name k) v))]
    (str "Sentry " (str/join ", " (map param params)))))

(defn merged-payload
  "Return a payload map depending on the type of the event."
  [event ts localhost]
  (merge (default-payload ts localhost)
         (cond
           (map? event)         event
           (e/exception? event) (e/exception->ev event)
           :else                {:message (str event)})))

(defn add-breadcrumbs-to-payload
  [context payload]
  (let [breadcrumbs-list (or (:breadcrumbs payload) (:breadcrumbs context))]
    (cond-> payload (seq breadcrumbs-list) (assoc :breadcrumbs {:values breadcrumbs-list}))))

(defn add-user-to-payload
  [context payload]
  (cond-> payload (:user context) (assoc :user (:user context))))

(defn add-http-info-to-payload
  [context payload]
  (cond-> payload (:request context) (assoc :request (:request context))))

(defn slurp-pretty-name
  [path]
  (try
    (last (re-find #"PRETTY_NAME=\"(.*)\"" (slurp path)))
    (catch Exception _)))

(defn get-linux-pretty-name
  "Get the Linux distribution pretty name from /etc/os-release resp. /usr/lib/os-release."
  []
  (or
   (slurp-pretty-name "/etc/os-release")
   (slurp-pretty-name "/usr/lib/os-release")
   "Unknown Linux"))

(let [cache (atom nil)]  ;; cache version forever
  (defn get-os-name-linux
    "Get a human-readable name for the current linux distribution from /etc/os-release,
     caching the output"
    []
    (or @cache
        (swap! cache (constantly (get-linux-pretty-name))))))

(defn get-os-context
  []
  (let [os-name (System/getProperty "os.name")
        os-version (System/getProperty "os.version")]
    (if (re-find #"^Linux" os-name)
      {:name os-name :version (get-os-name-linux) :kernel_version os-version}
      {:name os-name :version os-version})))

(defn get-contexts
  []
  {:java {:name (System/getProperty "java.vendor")
          :version (System/getProperty "java.version")}
   :os (get-os-context)
   :clojure {:name "clojure" :version (clojure-version)}})

(defn add-contexts-to-payload
  "Add relevant bits of sentry.interfaces.Contexts to our payload."
  [payload]
  (assoc payload :contexts (get-contexts)))

(defn add-fingerprint-to-payload
  "If the context provides a fingerprint override entry, pass it to the payload."
  [context payload]
  (cond-> payload (:fingerprint context) (assoc :fingerprint (:fingerprint context))))

(defn add-tags-to-payload
  "If the context provides tags or we were given tags directly during capture!,
  ad them to the payload. Tags provided by capture! override tags provided by the
  context map."
  [context payload tags]
  (let [merged (merge (:tags context) tags)]
    (cond->
     payload
      (not (empty? merged)) (assoc :tags merged))))

(defn add-uuid-to-payload
  [context payload]
  (assoc payload :event_id (:event_id context (:event_id payload (sentry-uuid!)))))

(defn validate-payload
  "Returns a validated payload."
  [merged]
  (s/assert :raven.spec/payload merged))

(defn payload
  "Build a full valid payload."
  [context event ts localhost tags]
  (let [breadcrumbs-adder (partial add-breadcrumbs-to-payload context)
        user-adder (partial add-user-to-payload context)
        http-info-adder (partial add-http-info-to-payload context)
        fingerprint-adder (partial add-fingerprint-to-payload context)
        tags-adder (partial add-tags-to-payload context)
        uuid-adder (partial add-uuid-to-payload context)]
    (-> (merged-payload event ts localhost)
        (uuid-adder)
        (breadcrumbs-adder)
        (user-adder)
        (fingerprint-adder)
        (http-info-adder)
        (tags-adder tags)
        (add-contexts-to-payload)
        (validate-payload))))

(defn timestamp!
  "Retrieve a timestamp.

  The format used is the same as python's 'time.time()' function - the number
  of seconds since the epoch, as a double to acount for fractional seconds (since
  the granularity is miliseconds)."
  []
  (double (/ (System/currentTimeMillis) 1000)))

(defn sign
  "HMAC-SHA1 for Sentry's format."
  [payload ts key ^String secret]
  (let [key (javax.crypto.spec.SecretKeySpec. (.getBytes secret) "HmacSHA1")
        bs  (-> (doto (javax.crypto.Mac/getInstance "HmacSHA1")
                  (.init key)
                  (.update (.getBytes (str ts)))
                  (.update (.getBytes " ")))
                (.doFinal payload))]
    (reduce str (for [b bs] (format "%02x" b)))))

(defn perform-in-memory-request
  "Perform an in-memory pseudo-request, actually just storing the payload on a storage
    atom, to let users inspect/retrieve the payload in their tests."
  [payload]
  (swap! http-requests-payload-stub conj payload))

(def json-mapper
  (doto (json/object-mapper {})
    (.configure SerializationFeature/FAIL_ON_EMPTY_BEANS false)
    (.configure MapperFeature/AUTO_DETECT_GETTERS false)
    (.configure MapperFeature/AUTO_DETECT_IS_GETTERS false)
    (.configure MapperFeature/AUTO_DETECT_SETTERS false)
    (.configure MapperFeature/AUTO_DETECT_FIELDS false)
    (.configure MapperFeature/DEFAULT_VIEW_INCLUSION false)))

(defn perform-http-request
  [context dsn ts payload]
  (let [json-payload             (json/write-value-as-bytes payload json-mapper)
        {:keys [key secret uri]} (parse-dsn dsn)
        sig                      (sign json-payload ts key secret)]
    ;; This is async, but we don't wait for the result since we don't really care if the
    ;; event makes it to the sentry server or not (we certainly don't want to block until
    ;; it fails).
    (d/chain
      (http/post (str uri "/api/store/")
                 (merge (select-keys context [:pool :middleware :pool-timeout
                                              :response-executor :request-timeout
                                              :read-timeout :connection-timeout])
                        {:headers           {:x-sentry-auth  (auth-header ts key sig)
                                             :accept         "application/json"
                                             :content-type   "application/json;charset=utf-8"}
                         :body              json-payload
                         :throw-exceptions? false}))
      #(.close ^Closeable (:body %)))))

(defn capture!
  "Send a capture over the network. If `ev` is an exception,
   build an appropriate payload for the exception."
  ([context dsn event tags]
   (let [ts      (timestamp!)
         payload (payload context event ts (localhost) tags)
         uuid    (:event_id payload)]
     (d/chain
      (if (= dsn ":memory:")
        (perform-in-memory-request payload)
        (perform-http-request context dsn ts payload))
      (fn [_]
        (clear-context)
        uuid))))
  ([dsn ev tags]
   (capture! @@thread-storage dsn ev tags))
  ([dsn ev]
   (capture! @@thread-storage dsn ev {})))

(defn make-breadcrumb!
  "Create a breadcrumb map.

  level can be one of:
    ['debug' 'info' 'warning' 'warn' 'error' 'exception' 'critical' 'fatal']"
  ([message category]
   (make-breadcrumb! message category "info"))
  ([message category level]
   (make-breadcrumb! message category level (timestamp!)))
  ([message category level timestamp]
   {:type      "default" ;; TODO: Extend to support more than just default breadcrumbs.
    :timestamp timestamp
    :level     level
    :message   message
    :category  category})
  ;; :data (expected in case of non-default)
)

(defn add-breadcrumb!
  "Append a breadcrumb to the list of breadcrumbs.

  The context is expected to be a map, and if no context is specified, a
  thread-local storage map will be used instead.
  "
  ([breadcrumb]
   ;; We need to dereference to get the atom since "thread-storage" is thread-local.
   (swap! @thread-storage add-breadcrumb! breadcrumb))
  ([context breadcrumb]
   ;; We add the breadcrumb to the context instead, in a ":breadcrumb" key.
   (update context :breadcrumbs conj (s/assert :raven.spec/breadcrumb breadcrumb))))

(defn make-user
  "Create a user map."
  ([id]
   {:id id})
  ([id email ip-address username]
   {:id         id
    :email      email
    :ip_address ip-address
    :username   username}))

(defn add-user!
  "Add user information to the sentry context (or a thread-local storage)."
  ([user]
   (swap! @thread-storage add-user! user))
  ([context user]
   (assoc context :user (s/assert :raven.spec/user user))))

(defn make-http-info
  ([url method]
   {:url    url
    :method method})
  ([url method headers query_string cookies data env]
   {:url          url
    :method       method
    :headers      headers
    :query_string query_string
    :cookies      cookies
    :data         data
    :env          env}))

(defn get-full-ring-url
  "Given a ring compliant request object, return the full URL.
    This was lifted from ring's source code so as to not depend on it."
  [request]
  (str (-> request :scheme name)
       "://"
       (get-in request [:headers "host"])
       (:uri request)
       (when-let [query (:query-string request)]
         (str "?" query))))

(defn get-ring-env
  [request]
  (cond-> {:REMOTE_ADDR  (:remote-addr request)
           :websocket?   (:websocket? request)
           :route-params (:route-params request)}
    (some? (:compojure/route request)) (assoc :compojure/route (:compojure/route request))
    (some? (:route request))           (assoc :bidi/route (get-in request [:route :handler]))))

(defn make-ring-request-info
  "Create well-formatted context map for the Sentry 'HTTP' interface by
    extracting the information from a standard ring-compliant 'request', as
    defined in https://github.com/ring-clojure/ring/wiki/Concepts#requests"
  [request]
  {:url          (get-full-ring-url request)
   :method       (:request-method request)
   :cookies      (get-in request [:headers "cookie"])
   :headers      (:headers request)
   :query_string (:query-string request)
   :env          (get-ring-env request)
   :data         (:body request)})

(defn add-http-info!
  "Add HTTP information to the sentry context (or a thread-local storage)."
  ([http-info]
   (swap! @thread-storage add-http-info! http-info))
  ([context http-info]
   (assoc context :request (s/assert :raven.spec/request http-info))))

(defn add-ring-request!
  "Add HTTP information to the Sentry payload from a ring-compliant request
    map, excluding its body."
  ([request]
   (add-http-info! (make-ring-request-info (dissoc request :body))))
  ([context request]
   (add-http-info! context (make-ring-request-info (dissoc request :body)))))

(defn add-full-ring-request!
  "Add HTTP information to the Sentry payload from a ring-compliant request
    map, including the request body"
  ([request]
   (add-http-info! (make-ring-request-info request)))
  ([context request]
   (add-http-info! context (make-ring-request-info request))))

(defn add-fingerprint!
  "Add a custom fingerprint to the context (or a thread-local storage)."
  ([fingerprint]
   (swap! @thread-storage add-fingerprint! fingerprint))
  ([context fingerprint]
   (assoc context :fingerprint (s/assert :raven.spec/fingerprint fingerprint))))

(defn add-tag!
  "Add a custom tag to the context (or a thread-local storage)."
  ([tag value]
   (swap! @thread-storage add-tag! tag value))
  ([context tag value]
   (assoc-in context [:tags tag] value)))

(defn add-tags!
  "Add custom tags to the context (or a thread-local storage)."
  ([tags]
   (swap! @thread-storage add-tags! tags))
  ([context tags]
   (reduce-kv add-tag! context tags)))

(defn add-extra!
  "Add a map of extra data to the context (or a thread-local storage)
  preserving its previous keys."
  ([extra]
   (swap! @thread-storage add-extra! extra))
  ([context extra]
   (update context :extra merge extra)))

(defn add-exception!
  "Add an exception to the context (or a thread-local storage)."
  ([^Throwable e]
   (swap! @thread-storage add-exception! e))
  ([context ^Throwable e]
   (let [env (e/exception->ev e)]
     (-> context
         (merge (dissoc env :extra))
         (add-extra! (:extra env))))))

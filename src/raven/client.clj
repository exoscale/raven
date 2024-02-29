(ns raven.client
  "A netty-based Sentry client."
  (:import java.lang.Throwable
           java.lang.Exception)
  (:require [clojure.walk :as walk]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.java.shell :as sh]
            [raven.exception :as e]
            [flatland.useful.utils :as useful])
  (:import [io.sentry Breadcrumb Sentry SentryEvent SentryLevel]
           [io.sentry.protocol Message Request SentryId User]
           [java.util Collection Date HashMap ArrayList Map UUID]))

;; Make sure we enforce spec assertions.
(s/check-asserts true)

(def thread-storage
  "Storage for this particular thread.

  This is a little funny in that it needs to be dereferenced once in order to
  obtain an atom that is sure to be per-thread."
  (useful/thread-local (atom {})))

(def sentry-captures-stub
  "Storage for stubbed sentry 'events' - actually, we store just the events."
  (atom []))

(defn clear-captures-stub
  "A convenience function to clear the events stub.
    This stub is only used when passing a DSN of ':memory:' to the lib."
  []
  (swap! sentry-captures-stub (fn [x] (vector))))

(defn clear-context
  "Reset this thread's context"
  []
  (reset! @thread-storage {}))

(defn sentry-uuid!
  "A random UUID, without dashes"
  []
  (UUID/randomUUID))

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
  [context ts localhost tags]
  (let [breadcrumbs-adder (partial add-breadcrumbs-to-payload context) ;;breadcrumbs
        user-adder (partial add-user-to-payload context) ;;user
        http-info-adder (partial add-http-info-to-payload context) ;;request
        fingerprint-adder (partial add-fingerprint-to-payload context) ;;fingerprint
        tags-adder (partial add-tags-to-payload context) ;;tags
        uuid-adder (partial add-uuid-to-payload context)] ;;event_id
    (-> (merged-payload {} ts localhost) ;; level server_name timestamp platform, and message culprit checksum stacktrace extra exception
        (uuid-adder)
        (breadcrumbs-adder)
        (user-adder)
        (fingerprint-adder)
        (http-info-adder)
        (tags-adder tags)
        (add-contexts-to-payload)
        (validate-payload))))

(defn timestamp!
  "Retrieve a timestamp."
  []
  (Date.))

(defrecord SafeMap [])
(defmethod clojure.core/print-method SafeMap
  [m ^java.io.Writer writer]
  (.write writer (format "#exoscale/safe-map [%s]" (str/join " " (keys m)))))

(def safe-map
  "Wraps map into SafeMap record, effectively hidding values from json
  output, will not allow 2-way roundtrip. ex: (safe-map {:a 1}) ->
  \"#exoscale/safe-map [:a]\". Can be used to hide secrets and/or shorten
  large/deep maps"
  map->SafeMap)

(defmacro doto->
  "Combines `doto` and `cond->`, such as:
  ```
  (doto-> (HashMap.)
    true  (.put 1 2)
    false (.put 3 4))
  ```
  returns a map with `{1 2}`"
  [x & forms]
  (let [gx (gensym)]
    `(let [~gx ~x]
       ~@(map (fn [[t expr]]
                `(when ~t ~(concat [(first expr) gx] (next expr))))
              (partition 2 forms))
       ~gx)))

(defn- ->user [{:keys [id username email ip_address]}]
  (let [user (User.)]
    (doto-> user
          id (.setId id)
          username (.setUsername username)
          email (.setEmail email)
          ip_address (.setIpAddress ip_address))
    user))

(defn- ->request [{:keys [method url query_string cookies headers env data]}]
  (let [request (Request.)]
    (doto-> request
            method (.setMethod  method)
            url (.setUrl url)
            query_string (.setQueryString query_string)
            cookies (.setCookies cookies)
            headers (.setHeaders (HashMap. ^Map (walk/stringify-keys headers)))
            env (.setEnvs (HashMap. ^Map (walk/stringify-keys env)))
            data (.setData (pr-str data)))
    request))

(defn- ->breadcrumbs [{:keys [values] :as breadcrumbs}]
  (ArrayList. ^Collection
              (for [breadcrumb values
                    :let [breadcrumb-type (:raven.spec.breadcrumb/type breadcrumb)
                          {:keys [timestamp level message category]} breadcrumb
                          bcrumb (if timestamp (Breadcrumb. ^Date timestamp) (Breadcrumb.))]]
                (doto-> bcrumb
                        breadcrumb-type (.setType breadcrumb-type)
                        level (.setLevel (SentryLevel/valueOf (.toUpperCase (str level))))
                        message (.setMessage ^String message)
                        category (.setCategory category)))))

(defn- ->message [s]
  (let [m (Message.)]
    (.setMessage m s)
    m))

(defn- make-sentry-event [dsn event
                          {:keys [event_id level server_name timestamp platform contexts tags
                                  breadcrumbs user request fingerprint culprit] :as payload}]

  (let [sentry-event (SentryEvent.)]
    ;; https://docs.sentry.io/platforms/java/enriching-events/
    (doto-> sentry-event
            breadcrumbs (.setBreadcrumbs (->breadcrumbs breadcrumbs))
            fingerprint (.setFingerprints (ArrayList. ^Collection fingerprint))
            event_id (.setEventId (SentryId. ^UUID event_id))
            level (.setLevel ^SentryLevel (SentryLevel/valueOf (.toUpperCase (str level))))
            server_name (.setServerName server_name)
            timestamp (.setTimestamp timestamp)
            platform (.setPlatform platform)
            user (.setUser (->user user))
            request (.setRequest (->request request))
            tags (.setTags (HashMap. ^Map (walk/stringify-keys tags)))
            (map? contexts) (.setExtras (HashMap. ^Map (walk/stringify-keys contexts))))

    ;; handle event proper
    (cond
      (e/exception? event) (.setThrowable sentry-event event)
      ;; this can override some extras from the context no?
      (map? event) (.setExtras sentry-event (HashMap. ^Map (walk/stringify-keys tags)))
      :default     (.setMessage sentry-event (->message event)))

    sentry-event))

(defn perform-sentry-capture [dsn event {:keys [contexts] :as payload}]
  (let [sentry-event (make-sentry-event dsn event payload)]
    ;; https://docs.sentry.io/platforms/java/enriching-events/scopes/
    (if (= dsn ":memory:")
      (swap! sentry-captures-stub conj sentry-event)
      (Sentry/captureEvent ^SentryEvent sentry-event))))

(defn capture!
  "Send a capture over the network. If `ev` is an exception,
   build an appropriate payload for the exception."
  ([context dsn event tags]
   (let [ts      (timestamp!)
         payload (payload context ts (localhost) tags)
         uuid    (:event_id payload)]
     (try
       (perform-sentry-capture dsn event payload)
       uuid
       (finally
         (clear-context)))))
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
    :category  category}))
  ;; :data (expected in case of non-default)


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

(defn release!
  "Release new application version with provided webhook release URL."
  [webhook-endpoint payload]
  (throw (ex-info "release not implemented in Sentry SDK" {:webhook-endpoint webhook-endpoint
                                                           :payload payload})))
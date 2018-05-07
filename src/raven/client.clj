(ns raven.client
  "A netty-based Sentry client."
  (:import java.lang.Throwable
           java.lang.Exception
           java.lang.StackTraceElement)
  (:require [net.http.client    :as http]
            [cheshire.core      :as json]
            [clojure.string     :as str]
            [clojure.spec.alpha :as s]
            [net.codec.b64      :as b64]
            [net.transform.string :as st]
            [clojure.java.shell :as sh]
            [raven.spec :as spec]
            [flatland.useful.utils :as useful]))

;; Make sure we enforce spec assertions.
(s/check-asserts true)

(def thread-storage
  "Storage for this particular thread.

  This is a little funny in that it needs to be dereferenced once in order to
  obtain an atom that is sure to be per-thread."
  (useful/thread-local (atom {})))

(defn clear-breadcrumbs
  "Reset this thread's breadcrumbs."
  []
  (swap! @thread-storage dissoc :breadcrumbs))

(defn clear-user
  "Reset this thread's user."
  []
  (swap! @thread-storage dissoc :user))

(defn clear-context
  "Reset this thread's context"
  []
  (clear-user)
  (clear-breadcrumbs))

(defn clear-user
  "Reset this thread's user."
  []
  (swap! @thread-storage dissoc :user))

(defn clear-context
  "Reset this thread's context"
  []
  (clear-user)
  (clear-breadcrumbs))

(defn md5
  [^String x]
  (reduce
   str
   (for [b (-> (java.security.MessageDigest/getInstance "MD5")
               (.digest (.getBytes x)))]
     (format "%02x" b))))

(defn exception?
  "Is the value an exception?"
  [e]
  (instance? Exception e))

(defn frame->info
  "Format a stack-trace frame."
  [^StackTraceElement frame]
  {:filename (.getFileName frame)
   :lineno   (.getLineNumber frame)
   :function (str (.getClassName frame) "." (.getMethodName frame))})

(defn exception-frames
  [^Exception e]
  (for [f (reverse (.getStackTrace e))]
    (frame->info f)))

(defn exception->ev
  "Format an exception in an appropriate manner."
  [^Throwable e]
  (let [data (ex-data e)]
    (cond-> {:message                      (.getMessage e)
             :culprit                      (str (class e))
             :checksum                     (md5 (str (class e)))
             :sentry.interfaces.Stacktrace {:frames (exception-frames e)}
             :sentry.interfaces.Exception  {:message   (.getMessage e)
                                            :type      (str (class e))}}
      data (assoc :extra data))))

(def user-agent
  "Our advertized UA"
  "spootnik-raven/0.1.4")

(defn random-uuid!
  "A random UUID, without dashes"
  []
  (str/replace (str (java.util.UUID/randomUUID)) "-" ""))

(def hostname-refresh-interval
  "How often to allow shelling out to hostname (1), in seconds. (stolen from riemann)"
  60)

(defn hostname
  "Fetches the hostname by shelling out to hostname (1), whenever the given age
  is stale enough. If the given age is recent, as defined by
  hostname-refresh-interval, returns age and val instead. (stolen from riemann)"
  [[age val]]
  (if (and val (<= (* 1000 hostname-refresh-interval)
                   (- (System/currentTimeMillis) age)))
    [age val]
    [(System/currentTimeMillis)
     (let [{:keys [exit out]} (sh/sh "hostname")]
       (if (= exit 0)
         (str/trim out)))]))

(let [cache (atom [nil nil])]
  (defn localhost
    "Returns the local host name."
    []
    (if (re-find #"^Windows" (System/getProperty "os.name"))
      (or (System/getenv "COMPUTERNAME") "localhost")
      (or (System/getenv "HOSTNAME")
          (second (swap! cache hostname))
          "localhost"))))

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
   :culprit    "<none>"
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
  [event ts pid uuid localhost]
  (merge (default-payload ts localhost)
         (cond
           (map? event)       event
           (exception? event) (exception->ev event)
           :else              {:message (str event)})
         {:event_id uuid
          :project  pid}))

(defn add-breadcrumbs-to-payload
  [context payload]
  (let [breadcrumbs-list (:breadcrumbs context)]
    (cond-> payload (seq breadcrumbs-list) (assoc :breadcrumbs {:values breadcrumbs-list}))))

(defn add-user-to-payload
  [context payload]
  (cond-> payload (:user context) (assoc :user (:user context))))

(defn validate-payload
  "Returns a validated payload."
  [merged]
  (s/assert ::payload merged))

(defn payload
  "Build a full valid payload."
  [context event ts pid uuid localhost]
  (let [breadcrumbs-adder (partial add-breadcrumbs-to-payload context)
        user-adder (partial add-user-to-payload context)]
    (-> (merged-payload event ts pid uuid localhost)
        (breadcrumbs-adder)
        (user-adder)
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
                  (.init key))
                (.doFinal (.getBytes (format "%s %s" ts payload))))]
    (reduce str (for [b bs] (format "%02x" b)))))

(defn get-http-client
  "Get a http client given a context.
    
    We expect callers to pass the http client in the context object at the
    :http key."
  [context]
  (or (:http context) (http/build-client {})))

(defn capture!
  "Send a capture over the network. If `ev` is an exception,
   build an appropriate payload for the exception."
  ([context dsn event]
   (let [ts                           (timestamp!)
         {:keys [key secret uri pid]} (parse-dsn dsn)
         payload                      (json/generate-string (payload context event ts pid (random-uuid!) (localhost)))
         sig                          (sign payload ts key secret)]
     (do
       (http/request (get-http-client context)
                     {:uri            (format "%s/api/store/" uri pid)
                      :request-method :post
                      :headers        {"X-Sentry-Auth" (auth-header ts key sig)
                                       "User-Agent"    user-agent
                                       "Content-Type"  "application/json"
                                       "Content-Length" (count payload)}
                      :transform      st/transform
                      :body           payload})
       ;; Make sure we clear the local-storage.
       (clear-context))))
  ([dsn ev]
   (capture! @@thread-storage dsn ev)))

(defn make-breadcrumb!
  "Create a breadcrumb map.
    
  level can be one of:
    ['debug' 'info' 'warning' 'warn' 'error' 'exception' 'critical' 'fatal']"
  ([message category]
   (make-breadcrumb! message category "info"))
  ([message category level]
   (make-breadcrumb! message category level (timestamp!)))
  ([message category level timestamp]
   {:type "default" ;; TODO: Extend to support more than just default breadcrumbs.
    :timestamp timestamp
    :level level
    :message message
    :category category})
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
   (update context :breadcrumbs conj breadcrumb)))

(defn make-user
  "Create a user map."
  ([id]
   {:id id})
  ([id email ip-address username]
   {:id id
    :email email
    :ip_address ip-address
    :username username}))

(defn add-user!
  "Add user information to the sentry context (or a thread-local storage)."
  ([user]
   (swap! @thread-storage add-user! user))
  ([context user]
   (assoc context :user user)))

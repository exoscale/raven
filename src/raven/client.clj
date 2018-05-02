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
            [raven.spec :as spec]))

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

(defn- default-payload
  "Provide default values for a payload."
  [ts]
  {:level       "error"
   :server_name (localhost)
   :culprit    "<none>"
   :timestamp   ts
   :platform    "java"})

(defn auth-header
  ""
  [ts key sig]
  (let [params [[:version   "2.0"]
                [:signature sig]
                [:timestamp ts]
                [:client    user-agent]
                [:key       key]]
        param  (fn [[k v]] (format "sentry_%s=%s" (name k) v))]
    (str "Sentry " (str/join ", " (map param params)))))

(defn- merged-payload
  "Return a payload map depending on the type of the event."
  [ev ts pid uuid]
  (merge (default-payload ts)
         (cond
           (map? ev)       ev
           (exception? ev) (exception->ev ev)
           :else           {:message (str ev)})
         {:event_id uuid
          :project  pid}))

(defn- validated-payload
  "Returns a validated payload."
  [merged]
  (if (s/valid? ::payload merged)
    merged
    (s/explain ::payload merged)))

(defn payload
  "Build a full valid payload"
  [ev ts pid uuid]
  (json/generate-string (validated-payload (merged-payload ev ts pid uuid))))

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

(defn capture!
  "Send a capture over the network. If `ev` is an exception,
   build an appropriate payload for the exception."
  ([client dsn ev]
   (let [ts                           (timestamp!)
         {:keys [key secret uri pid]} (parse-dsn dsn)
         payload                      (payload ev ts pid (random-uuid!))
         sig                          (sign payload ts key secret)]
     (http/request client
                   {:uri            (format "%s/api/store/" uri pid)
                    :request-method :post
                    :headers        {"X-Sentry-Auth" (auth-header ts key sig)
                                     "User-Agent"    user-agent
                                     "Content-Type"  "application/json"
                                     "Content-Length" (count payload)}
                    :transform      st/transform
                    :body           payload})))
  ([dsn ev]
   (capture! (http/build-client {}) dsn ev)))

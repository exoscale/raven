(ns raven.exception
  "
  A separate namespace to handle exceptions.
  "
  (:require
   [clojure.string :as str])
  (:import
   clojure.lang.Symbol
   java.util.Map))


(defn trace->frame
  "
  Turn a trace element into its Sentry counterpart.
  "
  [trace]

  (let [[^Symbol classname
         ^Symbol method
         ^String filename
         ^long   lineno] trace]

    {:filename filename
     :lineno lineno
     :function (str classname "." method)}))


(defn via->sign
  "
  Turn a `via` node into a signing node. Either take a `:type` field
  from  the data or compose a line from a class name and a message.
  "
  [^Map via]
  (let [{ex-type :type
         :keys [message data]} via
        {error-type :type} data]
    (or error-type
        (str ex-type ":" message))))


(defn ex-map->sign
  "
  Turn an exception map into a string for further hashing.
  "
  [^Map ex-map]
  (let [{:keys [via]} ex-map]
    (str/join \newline (map via->sign via))))


(defn via->exception
  "
  Turn one of the `via` nodes into a sentry exception map.
  https://docs.sentry.io/development/sdk-dev/interfaces/exception/
  We don't need to fill most of the fields since Sentry renders
  them not as expect anyway.
  "
  [^Map via]
  (let [{:keys [type message]} via]
    {:type (str type)
     :value message}))


(defn exception->ev
  "
  Turn an exception instance into a Sentry top-level map.
  "
  [^Throwable e]

  (let [ex-map (Throwable->map e)
        {:keys [trace via]} ex-map
        [ex-top] via
        {:keys [type message]} ex-top]

    {:message message
     :culprit (str type)
     :checksum (-> ex-map ex-map->sign hash str)
     :stacktrace {:frames (map trace->frame trace)}
     :extra (select-keys ex-map [:via])
     :exception {:values (mapv via->exception via)}}))


(defn exception?
  "Is the value an exception?"
  [e]
  (instance? Throwable e))


(comment

  (def _e
    (ex-info "aaa" {:foo {:baz [1 2 3 :foo]}}
             (ex-info "bbb" {:bar {:aaa {:bbb :CCC}}}
                      (ex-info "ccc" {:baz [true false]}))))

  (-> _e exception->ev (dissoc :stacktrace))

  )

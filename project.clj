(defproject exoscale/raven "0.4.3-SNAPSHOT"
  :description "clojure sentry client library"
  :url "https://github.com/exoscale/raven"
  :license {:name "MIT License"}
  :profiles {:dev {:global-vars {*warn-on-reflection* true}}}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [aleph               "0.4.6"]
                 [metosin/jsonista    "0.2.1"]
                 [org.flatland/useful "0.11.5"]]
  :test-selectors {:default (complement :integration-test)
                   :integration :integration-test})

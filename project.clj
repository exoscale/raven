(defproject exoscale/raven "0.4.14"
  :description "clojure sentry client library"
  :url "https://github.com/exoscale/raven"
  :license {:name "MIT License"}
  :profiles {:dev {:global-vars {*warn-on-reflection* true}}}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [aleph               "0.4.6"]
                 [metosin/jsonista    "0.2.2"]
                 [org.flatland/useful "0.11.6"]]
  :deploy-repositories [["snapshots" :clojars] ["releases" :clojars]]
  :test-selectors {:default (complement :integration-test)
                   :integration :integration-test})

(defproject exoscale/raven "1.0.0-SNAPSHOT"
  :description "clojure sentry client library"
  :url "https://github.com/exoscale/raven"
  :license {:name "MIT License"}
  :profiles {:dev {:global-vars {*warn-on-reflection* true}}}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [io.sentry/sentry    "7.4.0"]
                 [org.flatland/useful "0.11.6"]]
  :deploy-repositories [["snapshots" :clojars] ["releases" :clojars]]
  :test-selectors {:default (complement :integration-test)
                   :integration :integration-test})

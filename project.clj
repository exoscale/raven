(defproject spootnik/raven "0.1.2-SNAPSHOT"
  :description "clojure sentry client library"
  :url "https://github.com/pyr/raven"
  :license {:name "MIT License"}
  :profiles {:dev {:global-vars {*warn-on-reflection* true}}}
  :dependencies [[org.clojure/clojure "1.9.0-alpha14"]
                 [spootnik/net        "0.3.3-beta9"]
                 [cheshire            "5.7.0"]])

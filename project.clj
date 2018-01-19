(defproject spootnik/raven "0.1.4"
  :description "clojure sentry client library"
  :url "https://github.com/pyr/raven"
  :license {:name "MIT License"}
  :profiles {:dev {:global-vars {*warn-on-reflection* true}}}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [spootnik/net        "0.3.3-beta24"]
                 [cheshire            "5.8.0"]])

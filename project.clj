(defproject spootnik/raven "0.1.4"
  :description "clojure sentry client library"
  :url "https://github.com/pyr/raven"
  :license {:name "MIT License"}
  :profiles {:dev {:global-vars {*warn-on-reflection* true}}}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [spootnik/net        "0.3.3-beta24"]
                 [cheshire            "5.8.0"]
                 [org.flatland/useful "0.11.5"]]
  :plugins [[lein-cljfmt "0.5.7"]]
  :test-selectors {:default (complement :integration-test)
                   :integration :integration-test}
)

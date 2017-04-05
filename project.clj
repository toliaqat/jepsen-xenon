(defproject jepsen.xenon "0.1.0-SNAPSHOT"
  :description "Jepsen Tests for Xenon"
  :url "http://github.com"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :main jepsen.xenon
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [jepsen "0.1.4"]
                 [org.clojure/data.json "0.2.6"]
                 [verschlimmbesserung "0.1.3"]])
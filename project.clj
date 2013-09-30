(defproject com.keminglabs/zmq-async "0.1.0"
  :description "ZeroMQ 3 library for Clojure"
  :url "https://github.com/lynaghk/zmq-async"
  :license {:name "BSD" :url "http://www.opensource.org/licenses/BSD-3-Clause"}

  :min-lein-version "2.0.0"

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [com.keminglabs/jzmq "a6c1706"]
                 [com.keminglabs/jzmq-osx64 "a6c1706"]
                 [com.keminglabs/jzmq-linux64 "a6c1706"]

                 [org.clojure/core.match "0.2.0"]
                 [org.clojure/core.async "0.1.242.0-44b1e3-alpha"]]

  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[midje "1.5.1"]]}})
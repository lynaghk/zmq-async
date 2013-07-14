(defproject com.keminglabs/zmq-async "0.0.1-SNAPSHOT"
  :description "ZeroMQ 3 library for Clojure"
  :license {:name "BSD" :url "http://www.opensource.org/licenses/BSD-3-Clause"}

  :min-lein-version "2.0.0"

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.zeromq/jzmq "2.2.1-SNAPSHOT"]
                 [core.async "0.1.0-SNAPSHOT"]]

  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[midje "1.5.1"]]}}

  :jvm-opts ["-Djava.library.path=/usr/local/lib"])
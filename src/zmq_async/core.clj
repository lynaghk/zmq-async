(ns zmq-async.core
  (:import (org.zeromq ZMQ ZMQ$Socket)))

(def context
  (ZMQ/context 1))

(defn socket
  [socket-type]
  (.socket context (case socket-type
                     :pull ZMQ/PULL
                     :req ZMQ/REQ
                     :rep ZMQ/REP
                     :router ZMQ/ROUTER)))
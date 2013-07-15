(ns zmq-async.core
  (:require [clojure.core.async :refer [chan close!
                                        go <! >!
                                        thread <!! >!!]])
  (:import (org.zeromq ZMQ ZMQ$Socket)))

(def context
  (ZMQ/context 1))

(def BLOCK 0)

(defn request-socket
  "Channels supporting the REQ socket of a ZeroMQ REQ/REP pair.
A message must be sent before one can be recieved (in that order).
Returns two bufferless channels [in, out]."
  [addr]
  (let [send (chan) receive (chan)]
    (future
      (try
        (with-open [sock (.socket context ZMQ/REQ)]
          (.connect sock addr)
          ;;TODO: do I need to quit on InterruptedExceptions?
          (loop []
            (when-let [msg (<!! send)]
              (when-not (string? msg)
                (throw (Error. "String messages only for now, kthx.")))
              (println "sending request")
              (assert (.send sock msg))
              (println "sent request")
              (>!! receive (.recvStr sock BLOCK))
              (recur)))
          (println "request done")
          ;;(.disconnect sock addr)
          nil)
        (catch Throwable e
          (prn e))))
    [send receive]))

(defn reply-socket
  "Channels supporting the REP socket of a ZeroMQ REQ/REP pair.
A message must be received before one can be sent (in that order).
Returns two bufferless channels [in, out]."
  [addr]
  (let [send (chan) receive (chan)]
    (future
      (try
        (with-open [sock (.socket context ZMQ/REP)]
          (.bind sock addr)
          ;;TODO: do I need to quit on InterruptedExceptions?
          (loop []
            (>!! receive (.recvStr sock BLOCK))
            (when-let [msg (<!! send)]
              (when-not (string? msg)
                (throw (Error. "String messages only for now, kthx.")))
              (println "sending reply")
              (assert (.send sock msg))
              (println "sent reply")
              (recur)))

          ;;(.unbind sock)
          (println "reply done")
          nil)
        (catch Throwable e
          (println e))))
    
    [send receive]))


(comment
  (let [addr "ipc://grr.ipc"
        [csend crece] (request-socket addr)
        [ssend srece] (reply-socket addr)]


    (go ;;client
      (dotimes [i 5]
        (>! csend "hi")
        (println "server says: " (<! crece))))

    (go ;;server
      (dotimes [i 5]
        (println "client says:" (<! srece))
        (>! ssend (str i)))))



  (with-open [sock (.socket context ZMQ/REQ)]
    (.connect sock "ipc://should_get_cleaned_up.ipc")
    (.disconnect sock)
    nil)
  

  )
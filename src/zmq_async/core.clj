(ns zmq-async.core
  (:require [clojure.core.async :refer [chan
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
  (let [in (chan) out (chan)]
    (future
      (with-open [sock (.socket context ZMQ/REQ)]
        (.connect sock addr)
        ;;TODO: do I need to quit on InterruptedExceptions?
        (loop []
          (when-let [msg (<!! out)]
            (if-not (string? msg)
              (println "String messages only for now, kthx.")
              (.send sock msg))
            (>!! in (.recvStr sock BLOCK))
            (recur)))

        (.disconnect sock)
        nil))
    [in out]))

(defn reply-socket
  "Channels supporting the REP socket of a ZeroMQ REQ/REP pair.
A message must be received before one can be sent (in that order).
Returns two bufferless channels [in, out]."
  [addr]
  (let [in (chan) out (chan)]
    (future
      (with-open [sock (.socket context ZMQ/REP)]
        (.bind sock addr)

        ;;TODO: do I need to quit on InterruptedExceptions?
        (loop []
          (>!! in (.recvStr sock BLOCK))
          (when-let [msg (<!! out)]
            (if-not (string? msg)
              (println "String messages only for now, kthx.")
              (.send sock msg))
            (recur)))

        (.unbind sock)
        nil))
    [in out]))


(comment
  (let [addr "ipc://grr.ipc"
        [client-in client-out] (request-socket addr)
        [server-in server-out] (reply-socket addr)]


    (go ;;client
      (dotimes [i 5]
        (>! client-out "hi")
        (println "server says: " (<! client-in))))

    (go ;;server
      (dotimes [i 5]
        (println "client says:" (<! server-in))
        (>! server-out (str i)))))


  )
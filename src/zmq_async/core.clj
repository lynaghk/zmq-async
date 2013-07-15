(ns zmq-async.core
  (:require [clojure.core.async :refer [chan close! go <! >! <!! >!!]])
  (:import (org.zeromq ZMQ ZMQ$Socket ZMQ$Poller)))

(def context
  (ZMQ/context 1))

(def BLOCK 0)

(defn send!
  [^ZMQ$Socket sock ^String msg]
  (println "Sending on" sock " " msg)
  (assert (.send sock msg))
  (println "Sent on" sock))

(def inproc-control-addr
  "inproc://control")

(defn poll
  "Blocking poll that returns a [val, socket] tuple.
If multiple sockets are ready, one is chosen to be read from nondeterministically."
  [socks]
  ;;TODO: what's the perf cost of creating a new poller all the time?
  (let [n      (count socks)
        poller (ZMQ$Poller. n)]
    (doseq [s socks]
      (.register poller s))
    (.poll poller)
    ;;Randomly take the first ready socket, to match core.async's alts! behavior
    (->> (shuffle (range n))
         (filter #(.pollin poller %))
         first
         (.getSocket poller)
         ((juxt #(.recvStr %) identity)))))



;; (def zmq-thread
;;   (Thread. (fn []
;;              (let [control-sock (doto (.socket context ZMQ/PULL)
;;                                   (assert (.bind inproc-control-addr)))]
;;                (loop [socks #{control-sock}]
                 
;;                  (recur socks)))
  

;;              )))


(defn request-socket
  "Channels supporting the REQ socket of a ZeroMQ REQ/REP pair.
A message must be sent before one can be recieved (in that order).
Returns two bufferless channels [in, out]."
  [addr]
  (let [send (chan) receive (chan)]
  
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
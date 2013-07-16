(ns zmq-async.core
  (:refer-clojure :excludes [read-string])
  (:require [clojure.core.async :refer [chan close! go <! >! <!! >!! alts!!]]
            [clojure.core.match :refer [match]]
            [clojure.edn :refer [read-string]]
            [clojure.set :refer [map-invert]])
  (:import (org.zeromq ZMQ ZMQ$Socket ZMQ$Poller)))

(def context
  (ZMQ/context 1))

(def BLOCK 0)

(defn send!
  [^ZMQ$Socket sock ^String msg]
  (println "Sending on" sock " " msg)
  (assert (.send sock msg))
  (println "Sent on" sock))

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
    ;;Randomly take the first ready socket and its message, to match core.async's alts! behavior
    (->> (shuffle (range n))
         (filter #(.pollin poller %))
         first
         (.getSocket poller)
         ((juxt #(.recvStr %) identity)))))




(def zmq-control-addr
  "Address of in-process ZeroMQ socket used to control the ZeroMQ thread."
  "inproc://control")

(def async-control-chan
  (chan))



(defn zmq-looper
  "Runnable fn with blocking loop on zmq sockets.
Opens/closes zmq sockets according to messages recieved on `zmq-control-sock`.
Relays messages from zmq sockets to `async-control-chan`."
  ([] (zmq-looper (doto (.socket context ZMQ/PAIR)
                    (.bind zmq-control-addr))
                  zmq-control-addr
                  async-control-chan))
  ([zmq-control-sock zmq-control-addr async-control-chan]
     (fn []
       ;;Socks is a map of string socket addresses to sockets
       (loop [socks {zmq-control-addr zmq-control-sock}]
         (let [[val sock] (poll socks)
               sock-addr (get (map-invert socks) sock)]

           (assert (not (nil? sock-addr)))

           (recur
            (if (= sock-addr zmq-control-addr)
              (match [(read-string val)] ;;this is a message for us to send a value or open/close a socket
                [[:open addr type]] (assoc socks addr (doto (.socket context type)
                                                        ;;TODO: handle bind/connect correctly.
                                                        (.bind addr)))
                [[:close addr]]     (do
                                      (.close (socks addr)) ;;TODO: close vs. disconnect?
                                      (dissoc socks addr))
                [[addr msg]]        (do
                                      (send! (socks addr) msg)
                                      socks)
                [_]                 (throw (Exception. (str "bad ZMQ control message: " val))))

              (do ;;otherwise, it's just a message from a ZeroMQ socket that we need to pass along to the core.async thread
                ;; TODO: do we want to do an async put here?
                (>!! async-control-chan [sock-addr val])
                socks))))))))

(defn addr-for-chan
  [c chans]
  (first (for [[addr {recv :recv send :send}] chans
               :when (#{recv send} c)]
           addr)))

(defn async-looper
  "Runnable fn with blocking loop on channels.
Relays messages from channels to ZMQ control socket."
  ([] (async-looper async-control-chan
                    (doto (.socket context ZMQ/PAIR)
                      (.connect zmq-control-addr))))
  ([async-control-chan zmq-control-sock]
     (fn []
       (loop [chans {"control" {:recv c}}]
         (let [recv-chans (remove nil? (map :recv (vals chans)))
               [val c] (alts!! recv-chans)]
           (recur
            (if (= c async-control-chan)
              (match [val] ;;this is a message for us to send a value or to tell ZMQ to open a socket
                [[:open addr type new-chans]]
                (do (send! zmq-control-sock (pr-str [:open addr type]))
                    (assoc chans addr new-chans))

                [[addr msg]]
                (let [send-chan (get-in chans [addr :send])]
                  (assert send-chan)
                  (>!! send-chan msg)
                  chans)
                
                [_]                 (throw (Exception. (str "bad async control message: " val))))
              
              ;;if it's not on the control chan, then it's just a message we need to convey
              (let [addr (addr-for-chan c chans)]
                (send! zmq-control-sock (pr-str (if val [addr msg] [:close addr])))
                chans))))))))




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
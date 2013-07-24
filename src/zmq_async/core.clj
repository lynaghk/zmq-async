(ns zmq-async.core
  (:refer-clojure :exclude [read-string])
  (:require [clojure.core.async :refer [chan close! go <! >! <!! >!! alts!!]]
            [clojure.core.match :refer [match]]
            [clojure.edn :refer [read-string]]
            [clojure.set :refer [map-invert]])
  (:import (org.zeromq ZMQ ZContext ZMQ$Socket ZMQ$Poller)))

;;Some terminology:
;;
;; sock: ZeroMQ socket object
;; addr: address of a sock (a string)
;; sock-id: randomly generated string ID created by the core.async thread when a new socket is requested
;; chan: core.async channel
;; pairing: map entry of {addr {:send chan :recv chan}}, where existence of :send and :recv depend on the type of ZeroMQ socket at addr.
;;
;; Also, all send/recv labels are written to apply in this namespace, though the docstrings are inverted.
;; E.g., when the library consumer gets a send channel it is held under a :recv map key in this namespace, since the code here needs to receive from that channel to convey the message to the ZeroMQ socket.

(def context
  (ZContext.))

(def BLOCK 0)

(defn send!
  [^ZMQ$Socket sock ^String msg]
  (assert (.send sock msg)))

(defn poll
  "Blocking poll that returns a [val, socket] tuple.
If multiple sockets are ready, one is chosen to be read from nondeterministically."
  [socks]
  ;;TODO: what's the perf cost of creating a new poller all the time?
  (let [n      (count socks)
        poller (ZMQ$Poller. n)]
    (doseq [s socks]
      (.register poller s ZMQ$Poller/POLLIN))
    (.poll poller)

    ;;Randomly take the first ready socket and its message, to match core.async's alts! behavior
    (->> (shuffle (range n))
         (filter #(.pollin poller %))
         first
         (.getSocket poller)
         ((juxt #(.recvStr %) identity)))))


(defn process-zmq-control!
  "Process a message recieved on the zmq thread's control channel.
This fn is part of the async thread's inner loop, and non-nil return values will be recurred."
  [msg socks async-control-chan]
  (match [msg]
    [[:open addr type bind-or-connect id]] (let [sock (.createSocket context type)]
                                             (case bind-or-connect
                                               :bind (.bind sock addr)
                                               :connect (.connect sock addr))
                                             (assoc socks id sock))
    [[:close id]]          (do
                             (.close (socks id))
                             (dissoc socks id))
    [:shutdown]            (doseq [[_ sock] socks]
                             (.close sock))
    [[id val]]             (do
                             (send! (socks id) val)
                             socks)
    [_]                    (throw (Exception. (str "bad ZMQ control message: " msg)))))


(defn zmq-looper
  "Runnable fn with blocking loop on zmq sockets.
Opens/closes zmq sockets according to messages recieved on `zmq-control-sock`.
Relays messages from zmq sockets to `async-control-chan`."
  [zmq-control-sock async-control-chan]
  (fn []
    ;;Socks is a map of string socket addresses to sockets
    (loop [socks {:control zmq-control-sock}]
      (let [[val sock] (poll (vals socks))
            sock-id (get (map-invert socks) sock)]

        (assert (not (nil? sock-id)))

        (if (= sock-id :control)
          ;;this is a message for us to send a value or open/close a socket
          (when-let [new-socks (process-zmq-control! (read-string val) socks async-control-chan)]
            (recur new-socks))
          ;;Otherwise, it's just a message from a ZeroMQ socket that we need to pass along to the core.async thread
          ;;TODO: do we want to do an async put here?
          (do
            (>!! async-control-chan [sock-id val])
            (recur socks)))))))

(defn sock-id-for-chan
  [c pairings]
  (first (for [[id {recv :recv send :send}] pairings
               :when (#{recv send} c)]
           id)))

(defn shutdown-pairing!
  "Close ZeroMQ socket with `id` and all associated channels."
  [[sock-id chanmap] zmq-control-sock]
  (send! zmq-control-sock (pr-str [:close sock-id]))
  (doseq [[_ c] chanmap]
    (close! c)))

(defn process-async-control!
  "Process a message recieved on the async thread's control channel.
This fn is part of the async thread's inner loop, and non-nil return values will be recurred."
  [msg pairings zmq-control-sock]
  (match [msg]
    [[:open addr type bind-or-connect new-chanmap]] (let [sock-id (str (gensym "zmq-"))]
                                                      (send! zmq-control-sock (pr-str [:open addr type bind-or-connect sock-id]))
                                                      (assoc pairings sock-id new-chanmap))

    [[sock-id val]] (let [send-chan (get-in pairings [sock-id :send])]
                      (assert send-chan)
                      (>!! send-chan val)
                      pairings)

    ;;if the control channel is closed, close all ZMQ sockets and channels
    [nil] (let [opened-pairings (dissoc pairings :control)]

            (doseq [p opened-pairings]
              (shutdown-pairing! p zmq-control-sock))
            ;;tell the ZMQ thread to shutdown
            (send! zmq-control-sock (pr-str :shutdown))

            ;;return nil, so async thread doesn't recur
            nil)

    [_] (throw (Exception. (str "bad async control message: " msg)))))

(defn async-looper
  "Runnable fn with blocking loop on channels.
Controlled by messages sent over provided `async-control-chan`.
Sends messages to complementary `zmq-looper` by sending messages over provided `zmq-control-sock` (assumed to already be connected)."
  [async-control-chan zmq-control-sock]
  (fn []
    (loop [pairings {:control {:recv async-control-chan}}]
      (let [recv-chans (remove nil? (map :recv (vals pairings)))
            [val c] (alts!! recv-chans)
            id (sock-id-for-chan c pairings)]

        (match [id val]
          [:control control-message]
          (when-let [new-pairings (process-async-control! val pairings zmq-control-sock)]
            (recur new-pairings))

          [id nil] ;;The channel was closed, We need to shut down this socket
          (do
            (shutdown-pairing! [id (pairings id)] zmq-control-sock)
            (recur (dissoc pairings id)))

          [id msg] ;;Just convey the message to the ZeroMQ socket.
          (do
            (send! zmq-control-sock (pr-str [id msg]))
            (recur pairings)))))))




;;;;;;;;;;;;;;;;;;;;;;;;;
;;Start both threads
;;TODO: Are these toplevel forms and thread executions going to be a problem?
;;What's a nicer way to let the consuming user kick things off?


(def zmq-control-addr
  "inproc://control")

(def async-control-chan
  (chan))

(def zmq-thread
  (doto (Thread. (zmq-looper (doto (.createSocket context ZMQ/PAIR)
                               (.bind zmq-control-addr))
                             async-control-chan))
    (.setName (str "ZeroMQ looper " "[" zmq-control-addr "]"))
    (.setDaemon true)))

(def async-thread
  (doto (Thread. (async-looper async-control-chan
                               (doto (.createSocket context ZMQ/PAIR)
                                 (.connect zmq-control-addr))))
    (.setName (str "core.async looper" "[" zmq-control-addr "]"))
    (.setDaemon true)))

(.start zmq-thread)
(.start async-thread)


(defn request-socket
  "Channels supporting the REQ socket of a ZeroMQ REQ/REP pair.
A message must be sent before one can be recieved (in that order).
Returns two bufferless channels [send recv]."
  ([addr bind-or-connect] (request-socket addr bind-or-connect async-control-chan))
  ([addr bind-or-connect async-control-chan]
     (let [send (chan) recv (chan)]
       (>!! async-control-chan [:open addr ZMQ/REQ bind-or-connect {:send send :recv recv}])
       [recv send])))

(defn reply-socket
  "Channels supporting the REP socket of a ZeroMQ REQ/REP pair.
A message must be received before one can be sent (in that order).
Returns two bufferless channels [send, recv]."
  ([addr bind-or-connect] (reply-socket addr bind-or-connect async-control-chan))
  ([addr bind-or-connect async-control-chan]
     (let [send (chan) recv (chan)]
       (>!! async-control-chan [:open addr ZMQ/REP bind-or-connect {:send send :recv recv}])
       [recv send])))

(defn pair-socket
  "Channels supporting a ZeroMQ PAIR socket.
Returns two bufferless channels [send, recv]."
  ([addr bind-or-connect] (pair-socket addr bind-or-connect async-control-chan))
  ([addr bind-or-connect async-control-chan]
     (let [send (chan) recv (chan)]
       (>!! async-control-chan [:open addr ZMQ/PAIR bind-or-connect {:send send :recv recv}])
       [recv send])))


(comment
  (require '[clojure.pprint :refer [pprint]]
           '[clojure.stacktrace :refer [e]]
           '[clojure.tools.namespace.repl :refer [refresh refresh-all]])
  (clojure.tools.namespace.repl/refresh)


  )
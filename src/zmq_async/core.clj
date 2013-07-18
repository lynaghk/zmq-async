(ns zmq-async.core
  (:refer-clojure :exclude [read-string])
  (:require [clojure.core.async :refer [chan close! go <! >! <!! >!! alts!!]]
            [clojure.core.match :refer [match]]
            [clojure.edn :refer [read-string]]
            [clojure.set :refer [map-invert]])
  (:import (org.zeromq ZMQ ZMQ$Socket ZMQ$Poller)))

(defmacro p [x]
  `(do (prn ~x)
     ~x))

;;Some terminology:
;;
;; sock: ZeroMQ socket object
;; addr: address of a sock (a string)
;; sock-id: randomly generated string ID corresponding to a ZeroMQ socket, created by core.async thread when a new socket is requested
;; chan: core.async channel
;; pairing: map entry of {addr {:send chan :recv chan}}, where existence of :send and :recv depends on the type of ZeroMQ socket at addr.
;;
;; Also, all send/recv labels are written to apply in this namespace, though the docstrings are inverted.
;; E.g., when the library consumer gets a send channel it is held under a :recv map key in this namespace, since the code here needs to recieve from that channel to convey the message to the ZeroMQ socket.

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
      (.register poller s ZMQ$Poller/POLLIN))
    (.poll poller)

    ;;Randomly take the first ready socket and its message, to match core.async's alts! behavior
    (->> (shuffle (range n))
         (filter #(.pollin poller %))
         first
         (.getSocket poller)
         ((juxt #(.recvStr %) identity)))))

(defn zmq-looper
  "Runnable fn with blocking loop on zmq sockets.
Opens/closes zmq sockets according to messages recieved on `zmq-control-sock`.
Relays messages from zmq sockets to `async-control-chan`."
  [zmq-control-sock zmq-control-addr async-control-chan]
  (fn []
    ;;Socks is a map of string socket addresses to sockets
    (loop [socks {:control zmq-control-sock}]
      (prn "zmq loop")
      (prn socks)
      (let [[val sock] (poll (vals socks))
            sock-id (get (map-invert socks) sock)]

        (assert (not (nil? sock-id)))
        
        (recur
         (match [sock-id (read-string val)] ;;this is a message for us to send a value or open/close a socket
           [:control [:open addr type id]] (assoc socks id (doto (.socket context type)
                                                             (.bind addr)))
           [:control [:close id]]     (do
                                          (.close (socks id))
                                        (dissoc socks id))
           [:control [id msg]]        (do
                                          (send! (socks id) msg)
                                          socks)
           [:control _]                 (throw (Exception. (str "bad ZMQ control message: " val)))

           ;;it's just a message from a ZeroMQ socket that we need to pass along to the core.async thread
           ;;TODO: do we want to do an async put here?
           [id msg] (do
                        (>!! async-control-chan [id val])
                      socks)))))))

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
    [[:open addr type new-chanmap]] (let [sock-id (str (gensym "zmq-"))]
                                      (send! zmq-control-sock (pr-str [:open addr type sock-id]))
                                      (assoc pairings sock-id new-chanmap))

    [[sock-id msg]] (let [send-chan (get-in pairings [sock-id :send])]
                      (assert send-chan)
                      (>!! send-chan msg)
                      pairings)

    ;;if the control channel is closed, close all ZMQ sockets and channels
    [nil] (doseq [p pairings]
            (shutdown-pairing! p zmq-control-sock))

    [_] (throw (Exception. (str "bad async control message: " msg)))))

(defn async-looper
  "Runnable fn with blocking loop on channels.
Controlled by messages sent over provided `async-control-chan`.
Sends messages to complementary `zmq-looper` by sending messages over provided `zmq-control-sock` (assumed to already be connected)."
  [async-control-chan zmq-control-sock]
  (fn []
    (loop [pairings {:control {:recv async-control-chan}}]
      (prn "async loop")
      (prn pairings)
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
  (Thread. (zmq-looper (doto (.socket context ZMQ/PAIR)
                         (.bind zmq-control-addr))
                       zmq-control-addr
                       async-control-chan)))

(def async-thread
  (Thread. (async-looper async-control-chan
                         (doto (.socket context ZMQ/PAIR)
                           (.connect zmq-control-addr)))))

(.start zmq-thread)
(.start async-thread)


(defn request-socket
  "Channels supporting the REQ socket of a ZeroMQ REQ/REP pair.
A message must be sent before one can be recieved (in that order).
Returns two bufferless channels [send recv]."
  [addr]
  (let [send (chan) recv (chan)]
    (>!! async-control-chan [:open addr ZMQ/REQ {:send send :recv recv}])
    [recv send]))

(defn reply-socket
  "Channels supporting the REP socket of a ZeroMQ REQ/REP pair.
A message must be received before one can be sent (in that order).
Returns two bufferless channels [in, out]."
  [addr]
  (let [send (chan) recv (chan)]
    (>!! async-control-chan [:open addr ZMQ/REP {:send send :recv recv}])
    [recv send]))

(defn pair-socket
  "Channels supporting a ZeroMQ PAIR socket.
Returns two bufferless channels [in, out]."
  [addr]
  (let [send (chan) recv (chan)]
    (>!! async-control-chan [:open addr ZMQ/PAIR {:send send :recv recv}])
    [recv send]))


(comment
  (require '[clojure.pprint :refer [pprint]]
           '[clojure.stacktrace :refer [e]]
           '[clojure.tools.namespace.repl :refer [refresh refresh-all]])
  (clojure.tools.namespace.repl/refresh)

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
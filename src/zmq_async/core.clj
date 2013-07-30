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
;; pairing: map entry of {sock-id {:send chan :recv chan}}, where existence of :send and :recv channels depend on the type of ZeroMQ socket.
;;
;; All send/recv labels are written relative to this namespace; the docstrings are inverted.
;; E.g., when the library consumer gets a send channel it is held under a :recv map key in this namespace, since the code here needs to receive from that channel to convey the message to the ZeroMQ socket.

(def zmq-context
  (ZContext.))

(def BLOCK 0)

(defn send!
  [^ZMQ$Socket sock ^String msg]
  (assert (.send sock msg ZMQ/NOBLOCK)))

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
  "Process a message received on the zmq thread's control channel.
This fn is part of the async thread's inner loop, and non-nil return values will be recurred."
  [msg socks async-control-chan]
  (match [msg]
    [[:open addr type bind-or-connect id]] (let [sock (.createSocket zmq-context type)]
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
Opens/closes zmq sockets according to messages received on `zmq-control-sock`.
Relays messages from zmq sockets to `async-control-chan`."
  [zmq-control-sock async-control-chan]
  (fn []
    ;;Socks is a map of string socket-ids to ZeroMQ socket objects (plus a single :control keyword key associated with the thread's control socket).
    (loop [socks {:control zmq-control-sock}]
      (let [[val sock] (poll (vals socks))
            sock-id (get (map-invert socks) sock)]

        (assert (not (nil? sock-id)))

        (if (= sock-id :control)
          ;;this is a message for us to send a value or open/close a socket
          (when-let [new-socks (process-zmq-control! (read-string val) socks async-control-chan)]
            (recur new-socks))
          ;;Otherwise, it's just a message from a ZeroMQ socket that we need to pass along to the core.async thread
          ;;TODO: do we want to do an async put! or go-block >! here?
          ;;Probably not, since we can't do anything without the async control thread and there's no point in getting ahead of it.
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
  "Process a message received on the async thread's control channel.
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
Sends messages to complementary `zmq-looper` via provided `zmq-control-sock` (assumed to be connected)."
  [async-control-chan zmq-control-sock]
  (fn []
    ;; Pairings is a map of string id to {:send chan :recv chan} map, where existence of :send and :recv depend on the type of ZeroMQ socket at
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





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Public API
(defn create-context
  "Creates a zmq-async context map containing the following keys:

  shutdown              no-arg fn that shuts down this context, closing all ZeroMQ sockets
  addr                  address of in-process ZeroMQ socket used to control ZeroMQ thread
  sock-server           server end of zmq pair socket; must be bound via (.bind addr) method before starting the zmq thread
  sock-client           client end of zmq pair socket; must be connected via (.connect addr) method before starting the async thread
  async-control-chan    channel used to control async thread
  zmq-thread
  async-thread"

  ([] (create-context nil))
  ([name]
     (let [addr (str "inproc://" (gensym "zmq-async-"))
           sock-server (.createSocket zmq-context ZMQ/PAIR)
           sock-client (.createSocket zmq-context ZMQ/PAIR)
           async-control-chan (chan)

           zmq-thread (doto (Thread. (zmq-looper sock-server async-control-chan))
                        (.setName (str "ZeroMQ looper " "[" (or name addr) "]"))
                        (.setDaemon true))
           async-thread (doto (Thread. (async-looper async-control-chan sock-client))
                          (.setName (str "core.async looper" "[" (or name addr) "]"))
                          (.setDaemon true))]

       {:addr addr
        :sock-server sock-server
        :sock-client sock-client
        :async-control-chan async-control-chan
        :zmq-thread zmq-thread
        :async-thread async-thread
        :shutdown #(close! async-control-chan)})))

(defn initialize!
  "Initializes a context, returning it."
  [context]
  (let [{:keys [addr sock-server sock-client
                zmq-thread async-thread]} context]

    (.bind sock-server addr)
    (.start zmq-thread)

    (.connect sock-client addr)
    (.start async-thread))
  nil)

(defn request-socket
  "Channels supporting the REQ socket of a ZeroMQ REQ/REP pair.
A message must be sent before one can be received (in that order).
Returns two bufferless ports [send recv]."
  [context addr bind-or-connect]
  (let [send (chan) recv (chan)]
    (>!! (:async-control-chan context) [:open addr ZMQ/REQ bind-or-connect {:send send :recv recv}])
    [recv send]))

(defn reply-socket
  "Channels supporting the REP socket of a ZeroMQ REQ/REP pair.
A message must be received before one can be sent (in that order).
Returns two bufferless channels [send, recv]."
  [context addr bind-or-connect]
  (let [send (chan) recv (chan)]
    (>!! (:async-control-chan context) [:open addr ZMQ/REP bind-or-connect {:send send :recv recv}])
    [recv send]))

(defn pair-socket
  "Channels supporting a ZeroMQ PAIR socket.
Returns two bufferless channels [send, recv]."
  [context addr bind-or-connect]
  (let [send (chan) recv (chan)]
    (>!! (:async-control-chan context) [:open addr ZMQ/PAIR bind-or-connect {:send send :recv recv}])
    [recv send]))




(comment
  (require '[clojure.pprint :refer [pprint]]
           '[clojure.stacktrace :refer [e]]
           '[clojure.tools.namespace.repl :refer [refresh refresh-all]])
  (clojure.tools.namespace.repl/refresh)


  )
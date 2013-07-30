(ns zmq-async.core
  (:refer-clojure :exclude [read-string])
  (:require [clojure.core.async :refer [chan close! go <! >! <!! >!! alts!!]]
            [clojure.core.match :refer [match]]
            [clojure.set :refer [subset?]]
            [clojure.edn :refer [read-string]]
            [clojure.set :refer [map-invert]])
  (:import java.util.concurrent.LinkedBlockingQueue
           (org.zeromq ZMQ ZContext ZMQ$Socket ZMQ$Poller)))

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

(def valid-async->zmq-messages
  "All valid string messages that can be sent over the ZeroMQ control socket."
  #{"sentinel" "shutdown"})


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


(defn zmq-looper
  "Runnable fn with blocking loop on zmq sockets.
Opens/closes zmq sockets according to messages received on `zmq-control-sock`.
Relays messages from zmq sockets to `async-control-chan`."
  [queue zmq-control-sock async-control-chan]
  (fn []
    ;;Socks is a map of string socket-ids to ZeroMQ socket objects (plus a single :control keyword key associated with the thread's control socket).
    (loop [socks {:control zmq-control-sock}]
      (let [[val sock] (poll (vals socks))
            id (get (map-invert socks) sock)]

        (assert (not (nil? id)))

        (match [id val]

          ;;A message indicating there's a message waiting for us to process on the queue.
          [:control "sentinel"]
          (let [msg (.take queue)]
            (match [msg]

              [[:register sock-id new-sock]]
              (recur (assoc socks sock-id new-sock))

              [[:close sock-id]]
              (do
                (.close (socks sock-id))
                (recur (dissoc socks sock-id)))

              ;;Send a message out
              [[sock-id outgoing-message]]
              (do
                ;;TODO: handle byte arrays and byte buffers
                (send! (socks sock-id) outgoing-message)
                (recur socks))))

          [:control "shutdown"]
          (doseq [[_ sock] socks]
            (.close sock))

          [:control msg]
          (throw (Exception. (str "bad ZMQ control message: " msg)))

          ;;It's an incoming message, send it to the async thread to convey to the application
          [incoming-sock-id msg]
          (do
            (>!! async-control-chan [incoming-sock-id msg])
            (recur socks)))))))


(defn sock-id-for-chan
  [c pairings]
  (first (for [[id {recv :recv send :send}] pairings
               :when (#{recv send} c)]
           id)))

(defn command-zmq-thread!
  "Helper used by the core.async thread to relay a command to the ZeroMQ thread.
Puts message of interest on queue and then sends a sentinel value over zmq-control-sock so that ZeroMQ thread unblocks."
  [zmq-control-sock queue msg]
  (.put queue msg)
  (send! zmq-control-sock "sentinel"))

(defn shutdown-pairing!
  "Close ZeroMQ socket with `id` and all associated channels."
  [[sock-id chanmap] zmq-control-sock queue]
  (command-zmq-thread! zmq-control-sock queue
                       [:close sock-id])
  (doseq [[_ c] chanmap]
    (close! c)))

(defn async-looper
  "Runnable fn with blocking loop on channels.
Controlled by messages sent over provided `async-control-chan`.
Sends messages to complementary `zmq-looper` via provided `zmq-control-sock` (assumed to be connected)."
  [queue async-control-chan zmq-control-sock]
  (fn []
    ;; Pairings is a map of string id to {:send chan :recv chan} map, where existence of :send and :recv depend on the type of ZeroMQ socket.
    (loop [pairings {:control {:recv async-control-chan}}]
      (let [recv-chans (remove nil? (map :recv (vals pairings)))
            [val c] (alts!! recv-chans)
            id (sock-id-for-chan c pairings)]

        (match [id val]
          ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
          ;;Control messages

          ;;Register a new socket.
          [:control [:register sock chanmap]]
          (let [sock-id (str (gensym "zmq-"))]
            (command-zmq-thread! zmq-control-sock queue [:register sock-id sock])
            (recur (assoc pairings sock-id chanmap)))

          ;;Relay a message from ZeroMQ socket to core.async channel.
          [:control [sock-id val]]
          (let [send-chan (get-in pairings [sock-id :send])]
            (assert send-chan)
            ;;We have a contract with library consumers that they cannot give us channels that can block, so this >!! won't tie up the async looper.
            (>!! send-chan val)
            (recur pairings))

          ;;The control channel has been closed, close all ZMQ sockets and channels.
          [:control nil]
          (let [opened-pairings (dissoc pairings :control)]

            (doseq [p opened-pairings]
              (shutdown-pairing! p zmq-control-sock queue))

            (send! zmq-control-sock "shutdown")
            ;;Don't recur...
            nil)

          [:control msg] (throw (Exception. (str "bad async control message: " msg)))


          ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
          ;;Non-control messages

          ;;The channel was closed, close the corresponding socket.
          [id nil]
          (do
            (shutdown-pairing! [id (pairings id)] zmq-control-sock queue)
            (recur (dissoc pairings id)))

          ;;Just convey the message to the ZeroMQ socket.
          [id msg]
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

           ;;Shouldn't have to have a large queue; it's okay to block core.async thread puts since that'll give time for the ZeroMQ thread to catch up.
           queue (LinkedBlockingQueue. 8)

           async-control-chan (chan)

           zmq-thread (doto (Thread. (zmq-looper queue sock-server async-control-chan))
                        (.setName (str "ZeroMQ looper " "[" (or name addr) "]"))
                        (.setDaemon true))
           async-thread (doto (Thread. (async-looper queue async-control-chan sock-client))
                          (.setName (str "core.async looper" "[" (or name addr) "]"))
                          (.setDaemon true))]

       {:addr addr
        :sock-server sock-server
        :sock-client sock-client
        :queue queue
        :async-control-chan async-control-chan
        :zmq-thread zmq-thread
        :async-thread async-thread
        :shutdown #(close! async-control-chan)})))

(defn initialize!
  "Initializes a zmq-async context by binding/connecting both ends of the ZeroMQ control scoket and starting both threads."
  [context]
  (let [{:keys [addr sock-server sock-client
                zmq-thread async-thread]} context]

    (.bind sock-server addr)
    (.start zmq-thread)

    (.connect sock-client addr)
    (.start async-thread))
  nil)

(defn register-socket!
  [context socket chanmap configurator]
  (assert (subset? (keys chanmap) #{:send :recv})
          "Only :send and :recv ports can be associated with sockets")
  (configurator socket)
  (>!! (:async-control-chan context) [:register socket chanmap]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;"Easy" Public API

(defn request-socket!
  "Creates a ZeroMQ REQ socket and either connects or binds it to `addr`, then associates write-only `send` and read-only `recv` ports with it.
The `recv` port must never block writes.
The socket will be .close'd when the `send` port is closed."
  [context connect-or-bind addr send recv]
  (register-socket! context (.createSocket zmq-context ZMQ/REQ) {:send send :recv recv}
                    (case connect-or-bind
                      :connect #(.connect % addr)
                      :bind #(.bind % addr))))

(defn reply-socket!
  "Creates a ZeroMQ REP socket and either connects or binds it to `addr`, then associates write-only `send` and read-only `recv` ports with it.
The `recv` port must never block writes.
The socket will be .close'd when the `send` port is closed."
  [context connect-or-bind addr send recv]
  (register-socket! context (.createSocket zmq-context ZMQ/REP) {:send send :recv recv}
                    (case connect-or-bind
                      :connect #(.connect % addr)
                      :bind #(.bind % addr))))

(defn pair-socket!
  "Creates a ZeroMQ REP socket and either connects or binds it to `addr`, then associates write-only `send` and read-only `recv` ports with it.
The `recv` port must never block writes.
The socket will be .close'd when the `send` port is closed."
  [context connect-or-bind addr send recv]
  (register-socket! context (.createSocket zmq-context ZMQ/PAIR) {:send send :recv recv}
                    (case connect-or-bind
                      :connect #(.connect % addr)
                      :bind #(.bind % addr))))




(comment
  (require '[clojure.pprint :refer [pprint]]
           '[clojure.stacktrace :refer [e]]
           '[clojure.tools.namespace.repl :refer [refresh refresh-all]])
  (clojure.tools.namespace.repl/refresh)


  )
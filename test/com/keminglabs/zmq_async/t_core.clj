(ns com.keminglabs.zmq-async.t-core
  (:require [com.keminglabs.zmq-async.core :refer :all]
            [clojure.core.async :refer [go close! >!! <!! chan timeout alts!!]]
            [midje.sweet :refer :all])
  (:import org.zeromq.ZMQ))

(let [context (create-context)]
  (fact "Poller selects correct socket"
    (with-open [sock-A (doto (.createSocket (context :zcontext) ZMQ/PULL)
                         (.bind "inproc://A"))
                sock-B (doto (.createSocket (context :zcontext) ZMQ/PULL)
                         (.bind "inproc://B"))]

      (doto (.createSocket (context :zcontext) ZMQ/PUSH)
        (.connect "inproc://A")
        (.send "A message"))

      (let [[val sock] (poll [sock-A sock-B])]
        sock => sock-A
        ;;Need to use this awkward seq stuff here to compare byte arrays by value
        (seq val) => (seq (.getBytes "A message"))))))

(fact "ZMQ looper"
  (with-state-changes [(around :facts
                               (let [{:keys [zmq-thread addr sock-server sock-client async-control-chan queue]
                                      :as context} (create-context)
                                      _      (do
                                               (.bind sock-server addr)
                                               (.start zmq-thread))
                                      zcontrol (doto sock-client
                                                 (.connect addr))]

                                 (try
                                   ?form
                                   (finally
                                     (send! zcontrol "shutdown")
                                     (.join zmq-thread 100)
                                     (assert (not (.isAlive zmq-thread)))

                                     ;;Close any hanging ZeroMQ sockets.
                                     (doseq [s (.getSockets (context :zcontext))]
                                       (.close s))))))]

    ;;TODO: rearchitect so that one concern can be tested at a time?
    ;;Then the zmq looper would need to use accessible mutable state instead of loop/recur...
    (fact "Opens sockets, conveys messages between sockets and async control channel"
      (let [test-addr "inproc://open-test"
            test-id "open-test"
            test-sock (doto (.createSocket (context :zcontext) ZMQ/PAIR)
                        (.bind test-addr))]

        (command-zmq-thread! zcontrol queue
                             [:register test-id test-sock])

        (with-open [sock (.createSocket (context :zcontext) ZMQ/PAIR)]
          (.connect sock test-addr)

          ;;passes along received messages
          (let [test-msg "hihi"]
            (.send sock test-msg)

            (let [[id msg] (<!! async-control-chan)]
              id => test-id
              (seq msg) => (seq (.getBytes test-msg))))

          ;;including multipart messages
          (let [test-msg ["yo" "what's" "up?"]]
            (.send sock "yo" ZMQ/SNDMORE)
            (.send sock "what's" ZMQ/SNDMORE)
            (.send sock "up?")

            (let [[id msg] (<!! async-control-chan)]
              id => test-id
              (map #(String. %) msg) => test-msg))

          ;;sends messages when asked to
          (let [test-msg "heyo"]
            (command-zmq-thread! zcontrol queue
                                 [test-id test-msg])
            (Thread/sleep 50)
            (.recvStr sock ZMQ/NOBLOCK) => test-msg))))))

(fact "core.async looper"
  (with-state-changes [(around :facts
                               (let [context (create-context)
                                     {:keys [zmq-thread addr sock-server sock-client async-control-chan async-thread queue]} context
                                     acontrol async-control-chan
                                     zcontrol (doto sock-server
                                                (.bind addr))]

                                 (.connect sock-client addr)
                                 (.start async-thread)
                                 (try
                                   ?form
                                   (finally
                                     (close! acontrol)
                                     (.join async-thread 100)
                                     (assert (not (.isAlive async-thread)))

                                     ;;Close any hanging ZeroMQ sockets.
                                     (doseq [s (.getSockets (context :zcontext))]
                                       (.close s))))))]

    (fact "Tells ZMQ looper to shutdown when the async thread's control channel is closed"
      (close! acontrol)
      (Thread/sleep 50)
      (.recvStr zcontrol ZMQ/NOBLOCK) => "shutdown")

    (fact "Closes all open sockets when the async thread's control channel is closed"

      ;;register test socket
      (register-socket! {:context context :send (chan) :recv (chan)
                         :socket-type :req :configurator #(.connect % "ipc://test-addr")})

      (Thread/sleep 50)
      (.recvStr zcontrol ZMQ/NOBLOCK) => "sentinel"

      (let [[cmd sock-id _] (.take queue)]
        cmd => :register
        ;;Okay, now to actually test what we care about...
        ;;close the control socket
        (close! acontrol)
        (Thread/sleep 50)

        ;;the ZMQ thread was told to close the socket we opened earlier
        (.recvStr zcontrol ZMQ/NOBLOCK) => "sentinel"
        (.take queue) => [:close sock-id]
        (.recvStr zcontrol ZMQ/NOBLOCK) => "shutdown"))

    (fact "Forwards messages recieved from ZeroMQ thread to appropriate core.async channel."
      (let [send (chan) recv (chan)]

        ;;register test socket
        (register-socket! {:context context :send send :recv recv
                           :socket-type :req :configurator #(.bind % "ipc://test-addr")})

        (Thread/sleep 50)
        (.recvStr zcontrol ZMQ/NOBLOCK) => "sentinel"
        (let [[cmd sock-id _] (.take queue)]
          cmd => :register
          ;;Okay, now to actually test what we care about...
          (let [test-msg "hihi"]
            ;;pretend the zeromq thread got a message from the socket...
            (>!! acontrol [sock-id test-msg])

            ;;and it should get forwarded the the recv port.
            (<!! recv) => test-msg))))

    (fact "Forwards messages recieved from core.async 'send' channel to ZeroMQ thread."
      (let [send (chan) recv (chan)]

        ;;register test socket
        (register-socket! {:context context :send send :recv recv
                           :socket-type :req :configurator #(.bind % "ipc://test-addr")})

        (Thread/sleep 50)
        (.recvStr zcontrol ZMQ/NOBLOCK) => "sentinel"
        (let [[cmd sock-id _] (.take queue)]
          cmd => :register

          ;;Okay, now to actually test what we care about...
          (let [test-msg "hihi"]
            (>!! send test-msg)
            (Thread/sleep 50)
            (.recvStr zcontrol ZMQ/NOBLOCK) => "sentinel"
            (.take queue) => [sock-id test-msg]))))))


(fact "Integration"
  (with-state-changes [(around :facts
                               (let [context (doto (create-context)
                                               (initialize!))
                                     {:keys [async-thread zmq-thread]} context]

                                 (try
                                   ?form
                                   (finally
                                     ((:shutdown context))
                                     (.join async-thread 100)
                                     (assert (not (.isAlive async-thread)))

                                     (.join zmq-thread 100)
                                     (assert (not (.isAlive zmq-thread)))

                                     ;;Close any hanging ZeroMQ sockets.
                                     (doseq [s (.getSockets (context :zcontext))]
                                       (.close s))))))]

    (fact "raw->wrapped"
      (let [addr "inproc://test-addr" test-msg "hihi"
            send (chan) recv (chan)]

        (register-socket! {:context context :send send :recv recv
                           :socket-type :pair :configurator #(.bind % addr)})

        (.send (doto (.createSocket (context :zcontext) ZMQ/PAIR)
                 (.connect addr))
               test-msg)
        (String. (<!! recv)) => test-msg))

    (fact "wrapped->raw"
      (let [addr "inproc://test-addr" test-msg "hihi"
            send (chan) recv (chan)]

        (register-socket! {:context context :send send :recv recv
                           :socket-type :pair :configurator #(.bind % addr)})

        (let [raw (doto (.createSocket (context :zcontext) ZMQ/PAIR)
                    (.connect addr))]
          (>!! send test-msg)

          (Thread/sleep 50) ;;gross!
          (.recvStr raw ZMQ/NOBLOCK)) => test-msg))


    (fact "wrapped pair -> wrapped pair"
      (let [addr "inproc://test-addr" test-msg "hihi"
            [s-send s-recv c-send c-recv] (repeatedly 4 chan)]

        (register-socket! {:context context :send s-send :recv s-recv
                           :socket-type :pair :configurator #(.bind % addr)})
        (register-socket! {:context context :send c-send :recv c-recv
                           :socket-type :pair :configurator #(.connect % addr)})

        (>!! c-send test-msg)
        (String. (<!! s-recv)) => test-msg))

    (fact "wrapped pair -> wrapped pair w/ multipart message"
      (let [addr "inproc://test-addr" test-msg ["hihi" "what's" "up?"]
            [s-send s-recv c-send c-recv] (repeatedly 4 chan)]

        (register-socket! {:context context :send s-send :recv s-recv
                           :socket-type :pair :configurator #(.bind % addr)})
        (register-socket! {:context context :send c-send :recv c-recv
                           :socket-type :pair :configurator #(.connect % addr)})

        (>!! c-send test-msg)
        (map #(String. %) (<!! s-recv)) => test-msg))


    (fact "wrapped req <-> wrapped rep, go/future"
      (let [addr "inproc://test-addr"
            [s-send s-recv c-send c-recv] (repeatedly 4 chan)
            n 5
            server (go
                     (dotimes [_ n]
                       (assert (= "ping" (String. (<! s-recv)))
                               "server did not receive ping")
                       (>! s-send "pong"))
                     :success)

            client (future
                     (dotimes [_ n]
                       (>!! c-send "ping")
                       (assert (= "pong" (String. (<!! c-recv)))
                               "client did not receive pong"))
                     :success)]

        (register-socket! {:context context :send s-send :recv s-recv
                           :socket-type :rep :configurator #(.bind % addr)})
        (register-socket! {:context context :send c-send :recv c-recv
                           :socket-type :req :configurator #(.connect % addr)})

        (deref client 500 :fail) => :success
        (close! c-send)
        (close! s-send)
        (close! server)
        (<!! server) => :success))

    (fact "wrapped req <-> wrapped rep, go/go"
      (let [addr "inproc://test-addr"
            [s-send s-recv c-send c-recv] (repeatedly 4 chan)
            n 5
            server (go
                     (dotimes [_ n]
                       (assert (= "ping" (String. (<! s-recv)))
                               "server did not receive ping")
                       (>! s-send "pong"))
                     :success)

            client (go
                     (dotimes [_ n]
                       (>! c-send "ping")
                       (assert (= "pong" (String. (<! c-recv)))
                               "client did not receive pong"))
                     :success)]

        (register-socket! {:context context :send s-send :recv s-recv
                           :socket-type :rep :configurator #(.bind % addr)})
        (register-socket! {:context context :send c-send :recv c-recv
                           :socket-type :req :configurator #(.connect % addr)})

        ;;TODO: (<!! client 500 :fail) would be cooler
        (let [[val c] (alts!! [client (timeout 500)])]
          (if (= c client) val :fail)) => :success
          (close! c-send)
          (close! s-send)
          (close! server)
          (<!! server) => :success))))

(fact "Register-socket! throws errors when given invalid optmaps"
  (register-socket! {}) => (throws IllegalArgumentException))

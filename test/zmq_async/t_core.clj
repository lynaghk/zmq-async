(ns zmq-async.t-core
  (:require [zmq-async.core :refer :all]
            [clojure.core.async :refer [go close! >!! <!! chan timeout alts!!]]
            [midje.sweet :refer :all])
  (:import org.zeromq.ZMQ))


(fact "Poller selects correct socket"
      (with-open [sock-A (doto (.createSocket zmq-context ZMQ/PULL)
                           (.bind "inproc://A"))
                  sock-B (doto (.createSocket zmq-context ZMQ/PULL)
                           (.bind "inproc://B"))]

        (doto (.createSocket zmq-context ZMQ/PUSH)
          (.connect "inproc://A")
          (.send "A message"))

        (let [[val sock] (poll [sock-A sock-B])]
          sock => sock-A
          ;;Need to use this awkward seq stuff here to compare byte arrays by value
          (seq val) => (seq (.getBytes "A message")))))

(fact "ZMQ looper"
      (with-state-changes [(around :facts
                                   (let [{:keys [zmq-thread addr sock-server sock-client async-control-chan queue]} (create-context)
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
                                         (doseq [s (.getSockets zmq-context)]
                                           (.close s))))))]

        ;;TODO: rearchitect so that one concern can be tested at a time?
        ;;Then the zmq looper would need to use accessible mutable state instead of loop/recur...
        (fact "Opens sockets, conveys messages between sockets and async control channel"
              (let [test-addr "inproc://open-test"
                    test-id "open-test"
                    test-msg "hihi"
                    test-sock (doto (.createSocket zmq-context ZMQ/PAIR)
                                (.bind test-addr))]

                (command-zmq-thread! zcontrol queue
                                     [:register test-id test-sock])

                (with-open [sock (.createSocket zmq-context ZMQ/PAIR)]
                  (.connect sock test-addr)
                  (.send sock test-msg)

                  ;;passes along recieved messages
                  (let [[id msg] (<!! async-control-chan)]
                    id => test-id
                    (seq msg) => (seq (.getBytes test-msg)))

                  ;;sends messages when asked to
                  (command-zmq-thread! zcontrol queue
                                       [test-id test-msg])
                  (Thread/sleep 50)
                  (.recvStr sock ZMQ/NOBLOCK) => test-msg)))))

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
                                         (doseq [s (.getSockets zmq-context)]
                                           (.close s))))))]

        (fact "Tells ZMQ looper to shutdown when the async thread's control channel is closed"
              (close! acontrol)
              (Thread/sleep 50)
              (.recvStr zcontrol ZMQ/NOBLOCK) => "shutdown")

        (fact "Closes all open sockets when the async thread's control channel is closed"

              ;;register test socket
              (request-socket! context :connect "ipc://test-addr" (chan) (chan))
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
              (let [test-msg "hihi" send (chan) recv (chan)]

                ;;register test socket
                (request-socket! context :bind "ipc://test-addr" send recv)
                (Thread/sleep 50)
                (.recvStr zcontrol ZMQ/NOBLOCK) => "sentinel"
                (let [[cmd sock-id _] (.take queue)]
                  cmd => :register

                  ;;Okay, now to actually test what we care about...
                  ;;pretend the zeromq thread got a message from the socket...
                  (>!! acontrol [sock-id test-msg])

                  ;;and it should get forwarded the the recv port.
                  (<!! recv) => test-msg)))

        (fact "Forwards messages recieved from core.async 'send' channel to ZeroMQ thread."
              (let [test-msg "hihi" send (chan) recv (chan)]

                ;;register test socket
                (request-socket! context :bind "ipc://test-addr" send recv)
                (Thread/sleep 50)
                (.recvStr zcontrol ZMQ/NOBLOCK) => "sentinel"
                (let [[cmd sock-id _] (.take queue)]
                  cmd => :register

                  ;;Okay, now to actually test what we care about...
                  (>!! send test-msg)
                  (Thread/sleep 50)
                  (.recvStr zcontrol ZMQ/NOBLOCK) => "sentinel"
                  (.take queue) => [sock-id test-msg])))))





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
                                         (doseq [s (.getSockets zmq-context)]
                                           (.close s))))))]

        (fact "raw->wrapped"
              (let [addr "inproc://test-addr" test-msg "hihi"
                    send (chan) recv (chan)]

                (pair-socket! context :bind addr send recv)
                (.send (doto (.createSocket zmq-context ZMQ/PAIR)
                         (.connect addr))
                       test-msg)
                (String. (<!! recv)) => test-msg))

        (fact "wrapped->raw"
              (let [addr "inproc://test-addr" test-msg "hihi"
                    send (chan) recv (chan)]

                (pair-socket! context :bind addr send recv)

                (let [raw (doto (.createSocket zmq-context ZMQ/PAIR)
                            (.connect addr))]
                  (>!! send test-msg)

                  (Thread/sleep 50) ;;gross!
                  (.recvStr raw ZMQ/NOBLOCK)) => test-msg))


        (fact "wrapped pair -> wrapped pair"
              (let [addr "inproc://test-addr" test-msg "hihi"
                    [s-send s-recv c-send c-recv] (repeatedly 4 chan)]

                (pair-socket! context :bind addr s-send s-recv)
                (pair-socket! context :connect addr c-send c-recv)
                (>!! c-send test-msg)
                (String. (<!! s-recv)) => test-msg))

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

                (reply-socket! context :bind addr s-send s-recv)
                (request-socket! context :connect addr c-send c-recv)

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

                (reply-socket! context :bind addr s-send s-recv)
                (request-socket! context :connect addr c-send c-recv)


                ;;TODO: (<!! client 500 :fail) would be cooler
                (let [[val c] (alts!! [client (timeout 500)])]
                  (if (= c client) val :fail)) => :success
                  (close! c-send)
                  (close! s-send)
                  (close! server)
                  (<!! server) => :success))

        ))

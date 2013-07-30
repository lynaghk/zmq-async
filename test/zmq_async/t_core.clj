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

        (poll [sock-A sock-B]) => ["A message" sock-A]))

(fact "ZMQ looper"
      (with-state-changes [(around :facts
                                   (let [{:keys [zmq-thread addr sock-server sock-client async-control-chan]} (create-context)
                                         _      (do
                                                  (.bind sock-server addr)
                                                  (.start zmq-thread))
                                         zcontrol (doto sock-client
                                                    (.connect addr))]

                                     ?form
                                     (send! zcontrol (pr-str :shutdown))
                                     (.join zmq-thread 100)
                                     (assert (not (.isAlive zmq-thread)))

                                     ;;Close any hanging ZeroMQ sockets.
                                     (doseq [s (.getSockets zmq-context)]
                                       (.close s))))]

        ;;TODO: rearchitect so that one concern can be tested at a time?
        ;;Then the zmq looper would need to use accessible mutable state instead of loop/recur...
        (fact "Opens sockets, conveys messages between sockets and async control channel"
              (let [test-addr "inproc://open-test"
                    test-id "open-test"
                    test-msg "hihi"]

                (send! zcontrol (pr-str [:open test-addr ZMQ/PAIR :bind test-id]))
                ;;TODO: this sleep is gross; how to wait for ZMQ socket to open?
                (Thread/sleep 50)

                (with-open [sock (.createSocket zmq-context ZMQ/PAIR)]
                  (.connect sock test-addr)
                  (.send sock test-msg)

                  ;;passes along recieved messages
                  (<!! async-control-chan) => [test-id test-msg]

                  ;;sends messages when asked to
                  (send! zcontrol (pr-str [test-id test-msg]))
                  (Thread/sleep 50)
                  (.recvStr sock ZMQ/NOBLOCK) => test-msg)))))

(fact "core.async looper"
      (with-state-changes [(around :facts
                                   (let [context (create-context)
                                         {:keys [zmq-thread addr sock-server sock-client async-control-chan async-thread]} context
                                         acontrol async-control-chan
                                         zcontrol (doto sock-server
                                                    (.bind addr))]

                                     (.connect sock-client addr)
                                     (.start async-thread)

                                     ?form

                                     (close! acontrol)
                                     (.join async-thread 100)
                                     (assert (not (.isAlive async-thread)))

                                     ;;Close any hanging ZeroMQ sockets.
                                     (doseq [s (.getSockets zmq-context)]
                                       (.close s))))]

        (fact "Tells ZMQ looper to shutdown when the async thread's control channel is closed"
              (close! acontrol)
              (read-string (.recvStr zcontrol)) => :shutdown)

        (fact "Closes all open sockets when the async thread's control channel is closed"
              (let [test-addr "ipc://test-addr"
                    [send recv] (request-socket context test-addr :bind)
                    [_ addr _ _ sock-id] (read-string (.recvStr zcontrol))]

                addr => test-addr

                ;;close the control socket
                (close! acontrol)
                ;;the ZMQ thread was told to close the socket we opened earlier
                (read-string (.recvStr zcontrol)) => [:close sock-id]))

        (fact "Forwards messages recieved from ZeroMQ thread to appropriate core.async channel."
              (let [test-msg "hihi"
                    [send recv] (request-socket context "ipc://test-addr" :bind)
                    [_ _ _ _ sock-id] (read-string (.recvStr zcontrol))]

                (>!! acontrol [sock-id test-msg])
                (<!! recv) => test-msg))

        (fact "Forwards messages recieved from core.async 'send' channel to ZeroMQ thread."
              (let [test-msg "hihi"
                    [send recv] (request-socket context "ipc://test-addr" :bind)
                    [_ _ _ _ sock-id] (read-string (.recvStr zcontrol))]
                (>!! send test-msg)
                (read-string (.recvStr zcontrol)) => [sock-id test-msg]))))





(fact "Integration"
      (with-state-changes [(around :facts
                                   (let [context (initialize-context)
                                         {:keys [async-thread zmq-thread]} context]

                                     ?form

                                     ((:shutdown context))
                                     (.join async-thread 100)
                                     (assert (not (.isAlive async-thread)))

                                     (.join zmq-thread 100)
                                     (assert (not (.isAlive zmq-thread)))

                                     ;;Close any hanging ZeroMQ sockets.
                                     (doseq [s (.getSockets zmq-context)]
                                       (.close s))))]

        (fact "raw->wrapped"
              (let [addr "inproc://test-addr"
                    test-msg "hihi"
                    [send recv] (pair-socket context addr :bind)
                    _ (Thread/sleep 50) ;;gross!
                    raw (doto (.createSocket zmq-context ZMQ/PAIR)
                          (.connect addr))]

                (.send raw test-msg)
                (<!! recv) => test-msg))

        (fact "wrapped->raw"
              (let [addr "inproc://test-addr"
                    test-msg "hihi"
                    [send recv] (pair-socket context addr :bind)
                    _ (Thread/sleep 50) ;;gross!
                    raw (doto (.createSocket zmq-context ZMQ/PAIR)
                          (.connect addr))]
                (>!! send test-msg)
                (Thread/sleep 50) ;;gross!
                (.recvStr raw ZMQ/NOBLOCK) => test-msg))

        (fact "wrapped pair -> wrapped pair"
              (let [addr "inproc://test-addr"
                    test-msg "hihi"
                    [s-send s-recv] (pair-socket context addr :bind)
                    [c-send c-recv] (pair-socket context addr :connect)]
                (Thread/sleep 50)
                (>!! c-send test-msg)
                (<!! s-recv) => test-msg))

        (fact "wrapped req <-> wrapped rep, go/future"
              (let [addr "inproc://test-addr"
                    [s-send s-recieve] (reply-socket context addr :bind)
                    [c-send c-recieve] (request-socket context addr :connect)
                    n 5
                    server (go
                             (dotimes [_ n]
                               (assert (= "ping" (<! s-recieve))
                                       "server did not receive ping")
                               (>! s-send "pong"))
                             :success)

                    client (future
                             (dotimes [_ n]
                               (>!! c-send "ping")
                               (assert (= "pong" (<!! c-recieve))
                                       "client did not receive pong"))
                             :success)]

                (deref client 500 :fail) => :success
                (close! c-send)
                (close! s-send)
                (close! server)
                (<!! server) => :success))

        (fact "wrapped req <-> wrapped rep, go/go"
              (let [addr "inproc://test-addr"
                    [s-send s-recieve] (reply-socket context addr :bind)
                    [c-send c-recieve] (request-socket context addr :connect)
                    n 5
                    server (go
                             (dotimes [_ n]
                               (assert (= "ping" (<! s-recieve))
                                       "server did not receive ping")
                               (>! s-send "pong"))
                             :success)

                    client (go
                             (dotimes [_ n]
                               (>! c-send "ping")
                               (assert (= "pong" (<! c-recieve))
                                       "client did not receive pong"))
                             :success)]

                ;;TODO: (<!! client 500 :fail) would be cooler
                (let [[val c] (alts!! [client (timeout 500)])]
                  (if (= c client) val :fail)) => :success
                  (close! c-send)
                  (close! s-send)
                  (close! server)
                  (<!! server) => :success))))

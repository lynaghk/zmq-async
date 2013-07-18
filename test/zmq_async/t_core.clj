(ns zmq-async.t-core
  (:require [zmq-async.core :refer :all]
            [clojure.core.async :refer [go close! >!! <!! chan]]
            [midje.sweet :refer :all])
  (:import org.zeromq.ZMQ))


(fact "Poller selects correct socket"
      (with-open [sock-A (doto (.createSocket context ZMQ/PULL)
                           (.bind "inproc://A"))
                  sock-B (doto (.createSocket context ZMQ/PULL)
                           (.bind "inproc://B"))]

        (doto (.createSocket context ZMQ/PUSH)
          (.connect "inproc://A")
          (.send "A message"))

        (poll [sock-A sock-B]) => ["A message" sock-A]))



(fact "ZMQ looper"
      (with-state-changes [(around :facts
                                   (let [acontrol (chan)
                                         zmq-control-addr "inproc://test-control"
                                         zmq-looper (doto (Thread. (zmq-looper (doto (.createSocket context ZMQ/PAIR)
                                                                                 (.bind zmq-control-addr))
                                                                               acontrol))
                                                      (.start))]
                                     (with-open [zcontrol (doto (.createSocket context ZMQ/PAIR)
                                                            (.connect zmq-control-addr))]
                                       ?form
                                       (send! zcontrol (pr-str :shutdown))
                                       (.join zmq-looper 100)
                                       (assert (not (.isAlive zmq-looper)))

                                       ;;Close any hanging ZeroMQ sockets.
                                       (doseq [s (.getSockets context)]
                                         (.close s)))))]

        ;;TODO: rearchitect so that one concern can be tested at a time?
        ;;Then the zmq looper would need to use accessible mutable state instead of loop/recur...
        (fact "Opens sockets and forwards recieved messages to async channel"
              (let [test-addr "inproc://open-test"
                    test-id "open-test"
                    test-msg "hihi"]

                (send! zcontrol (pr-str [:open test-addr ZMQ/PAIR test-id]))
                ;;TODO: this sleep is gross; how to wait for ZMQ socket to open?
                (Thread/sleep 50)

                (doto (.createSocket context ZMQ/PAIR)
                  (.connect test-addr)
                  (.send test-msg)
                  (.close))

                (<!! acontrol) => [test-id test-msg]))))





;; (let [[send recv] (pair-socket "inproc://test")]
;;     (with-open [sock (doto (.createSocket context ZMQ/PAIR)
;;                        (.connect "inproc://test"))]
;;       (fact "Raw -> Wrapped "
;;             (>!! send "hihi")
;;             (.recvStr sock) => "hihi")))




;; (def addr "ipc://test_socket.ipc")

;; (let [[c-send c-recieve] (request-socket addr)
;;       [s-send s-recieve] (reply-socket addr)
;;       n 5]

;;   (fact "REQ/REP ping pong"
;;         (let [server (go
;;                        (dotimes [_ n]
;;                          (assert (= "ping" (<! s-recieve)))
;;                          (>! s-send "pong"))
;;                        :success)

;;               client (future
;;                        (dotimes [_ n]
;;                          (>!! c-send "ping")
;;                          (assert (= "pong" (<!! c-recieve))))
;;                        :success)]

;;           (deref client 500 :fail) => :success
;;           (close! c-send)
;;           (close! s-send)
;;           (close! server)
;;           ;;(<!! server) => :success
;;           )))

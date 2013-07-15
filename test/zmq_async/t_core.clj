(ns zmq-async.t-core
  (:require [zmq-async.core :refer [request-socket reply-socket]]
            [clojure.core.async :refer [go close! >!! <!!]]
            [midje.sweet :refer :all]))

(def addr "ipc://test_socket.ipc")

(let [[c-send c-recieve] (request-socket addr)
      [s-send s-recieve] (reply-socket addr)
      n 5]

  (fact "REQ/REP ping pong"
        (let [server (go
                       (dotimes [_ n]
                         (assert (= "ping" (<! s-recieve)))
                         (>! s-send "pong"))
                       :success)

              client (future
                       (dotimes [_ n]
                         (>!! c-send "ping")
                         (assert (= "pong" (<!! c-recieve))))
                       :success)]

          (deref client 500 :fail) => :success
          (close! c-send)
          (close! s-send)
          (close! server)
          ;;(<!! server) => :success
          )))

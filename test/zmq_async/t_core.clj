(ns zmq-async.t-core
  (:require [zmq-async.core :refer [request-socket reply-socket]]
            [clojure.core.async :refer [go close! >!! <!!]]
            [midje.sweet :refer :all]))

(def addr "ipc://test_socket.ipc")

(let [[client-in client-out] (request-socket addr)
      [server-in server-out] (reply-socket addr)
      n 5]

  (fact "REQ/REP ping pong"
        (let [server (go
                       (dotimes [_ n]
                         (assert (= "ping" (<! server-in)))
                         (>! server-out "pong"))
                       :success)

              client (future
                       (dotimes [_ n]
                         (>!! client-out "ping")
                         (assert (= "pong" (<!! client-in))))
                       :success)]

          (deref client 500 :fail) => :success
          (close! client-out)
          (close! server-out)

          (close! server)
          (<!! server) => :success)))

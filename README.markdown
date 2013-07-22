# ZeroMQ Async

ZeroMQ is a message-oriented socket system that supports many communication styles (request/reply, publish/subscribe, fan-out, &c.) on top of many transport layers with bindings to many languages.
This is a Clojure ZeroMQ interface built on core.async channels.

## Quick start

Since core.async isn't available on Maven, this library is not yet available on Clojars.
Install core.async to your local machine, then open up a REPL manually.

```clojure
(require '[zmq-async.core :refer [request-socket reply-socket]])

(let [addr "inproc://ping-pong"
      [s-send s-recv] (reply-socket addr :bind)
      [c-send c-recv] (request-socket addr :connect)
      n 3]
      
  (go (dotimes [_ n]
        (println (<! s-recv))
        (>! s-send "pong")))

  (go (dotimes [_ n]
        (>! c-send "ping")
        (println (<! c-recv)))))
```

## Motivation

ZeroMQ sockets are not thread safe, so to support concurrent usage you have to resort to locks or dedicated threads that read/write sockets and expose them to the rest of your application via queues.
This library does that behind the scenes for you so you don't have to think about it.

## Architecture

Under the hood, this library uses two threads:

+ One thread manages ZeroMQ sockets and conveys incoming values to the application via a core.async channel (the "ZeroMQ thread")
+ One thread manages core.async channels and writes to a ZeroMQ control socket (the "core.async thread")

Each thread blocks with the appropriate selection construct (`zmq_poll` and `alts!!`, respectively) rather than an explicit polling loop.
Thus, each thread must communicate with the other via the other's transport.
The core.async thread writes a `pr-str`'d command to the ZeroMQ thread's in-process control socket when it wants to:

+ write a value out to a ZeroMQ socket, `[sock-id val]`,
+ open a new socket, `[:open addr zmq-type :bind-or-:connect sock-id]`,
+ or close a socket, `[:close sock-id]`.

The ZeroMQ thread writes `[sock-id val]` to the core.async thread's control channel when it receives value `val` from the socket with identifier `sock-id`.

Sockets are closed when their corresponding core.async send channel(s) are closed.
The returned core.async channels are unbuffered since 1) ZeroMQ sockets already have internal buffering and 2) if channel buffering is desired it can be added by wrapping the unbuffered channel.

## TODO (?)

+ This library needs a better name.
+ Automatic fan-out from a single ZeroMQ socket to multiple core.async channels; should we try to "do the right thing" when clients ask for a new socket on an address that a socket is already bound to, or should we just let ZeroMQ's behavior (the newer socket object takes over) bleed through?
+ Error handling; do we close the recv channel when a socket cannot bind/connect or otherwise blows up? Should we wait until a socket is successfully bound before returning a pair of channels?
+ Allow user to set ZeroMQ socket options (see: http://zeromq.github.io/jzmq/javadocs/org/zeromq/ZMQ.Socket.html); on creation only? Or should we provide a third control channel that can be used to twiddle ZeroMQ sockets afterwards? This would be useful in particular for long-lived pubsub sockets where the client wants to add/remove subscriptions over the life of the connection.
+ How to handle case where ZeroMQ sends block and ties up the entire ZeroMQ thread?

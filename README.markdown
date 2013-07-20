# ZeroMQ Async

ZeroMQ is a message-oriented socket system that supports many communication styles (request/response, publish/subscribe, fan-out, &c.) on top of many transport layers with bindings to many languages.
This is a Clojure ZeroMQ interface built on core.async channels.

## Quick start

## Motivation

ZeroMQ sockets are not thread safe, so to support concurrent usage you have to resort to locking or dedicated threads that read/write sockets and expose them to the rest of your application via queues.
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





## TODO (?)

+ Automatic fan-out from a single ZeroMQ socket to multiple core.async channels


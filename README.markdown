# ZeroMQ Async

ZeroMQ is a message-oriented socket system that supports many communication styles (request/response, publish/subscribe, fan-out, &c.) on top of many transport layers with bindings to many languages.
This is a Clojure ZeroMQ interface built on core.async channels.

## Quick start

## Motivation

ZeroMQ sockets are not thread safe, so to support concurrent usage you have to resort to locking or dedicated threads that read/write sockets and expose them to the rest of your application via queues.
This library does that behind the scenes for you so you don't have to think about it.


## Architecture

Under the hood, this library uses two threads:

+ One thread reads from ZeroMQ sockets and writes to core.async channels (the "ZeroMQ thread")
+ One thread reads from core.async channels and writes to ZeroMQ sockets (the "core.async" thread)

Each thread can block with the appropriate selection construct (`zmq_poll` and `alts!!`, respectively), which means we don't need explicit polling loop.
Sockets are closed when their corresponding core.async channel(s) are closed.
The core.async thread communicates this fact to the ZeroMQ thread via an in-process ZeroMQ socket, which then disconnnects/unbinds and closes the socket.


## TODO (?)

+ Automatic fan-out from a single ZeroMQ socket to multiple core.async channels


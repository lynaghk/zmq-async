# ZeroMQ Async

ZeroMQ is a message-oriented socket system that supports many communication styles (request/response, publish/subscribe, fan-out, &c.) on top of many transport layers with bindings to many languages.
This is a Clojure ZeroMQ interface built on core.async channels.

## Quick start

## Motivation

ZeroMQ sockets are not thread safe.
To support concurrent usage from Clojure, you need to spin up a dedicated thread to read/write from a socket and expose the socket to the rest of your system via, e.g., `java.util.concurrent` queues.
This library does that behind the scenes for you so you don't have to think about it.

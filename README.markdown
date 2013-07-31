# ZeroMQ Async

ZeroMQ is a message-oriented socket system that supports many communication styles (request/reply, publish/subscribe, fan-out, &c.) on top of many transport layers with bindings to many languages.
This is a Clojure ZeroMQ interface built on core.async channels.

ZeroMQ sockets are not thread safe, so to support concurrent usage one typically resorts to locks or dedicated threads that expose sockets to the rest of your application via queues.
This library does that behind the scenes for you so you don't have to think about it, associating ZeroMQ sockets with thread-safe core.async channels.

## Quick start

This library is not yet available on Clojars; clone the repo to your local machine and open up a REPL manually.
Your system should have ZeroMQ 3.2 installed:

    brew install zeromq

or

    apt-get install libzmq3
    

There are two interfaces to this library, an easy one and a simple one.
The easy interface creates and binds/connects ZeroMQ sockets for you, associating them with the send and receive ports you provide:

```clojure
(require '[zmq-async.core :refer [request-socket! reply-socket! create-context initialize!]]
         '[clojure.core.async :refer [>! <! go chan sliding-buffer close!]])

(let [context (doto (create-context) (initialize!))
      n 3, addr "inproc://ping-pong"
      [s-send s-recv c-send c-recv] (repeatedly 4 #(chan (sliding-buffer 64)))]
  
  (reply-socket! context :bind addr s-send s-recv)
  (request-socket! context :connect addr c-send c-recv)
      
  (go (dotimes [_ n]
        (println (<! s-recv))
        (>! s-send "pong"))
      (close! s-send))

  (go (dotimes [_ n]
        (>! c-send "ping")
        (println (<! c-recv)))
      (close! c-send)))
```

The simple interface accepts a ZeroMQ socket that has already been configured and bound/connected, associating it with the provided send and receive ports:

```clojure
(require '[zmq-async.core :refer [register-socket! create-context initialize!]]
         '[clojure.core.async :refer [>! <! go chan sliding-buffer close!]])
(import '(org.zeromq ZMQ ZContext))

(let [zmq-context (ZContext.)
      context (doto (create-context) (initialize!))
      n 3, addr "inproc://ping-pong" 
      [s-send s-recv c-send c-recv] (repeatedly 4 #(chan (sliding-buffer 10)))
      server-sock (doto (.createSocket zmq-context ZMQ/PAIR)
                    ;;twiddle ZeroMQ socket options here...
                    (.bind addr))
      client-sock (doto (.createSocket zmq-context ZMQ/PAIR)
                    ;;twiddle ZeroMQ socket options here...
                    (.connect addr))]

  (register-socket! context server-sock {:send s-send :recv s-recv})
  (register-socket! context client-sock {:send c-send :recv c-recv})
  
  (go (dotimes [_ n]
        (println (<! s-recv))
        (>! s-send "pong"))
      (close! s-send))

  (go (dotimes [_ n]
        (>! c-send "ping")
        (println (<! c-recv)))
      (close! c-send)))
```

## Architecture

![Architecture Diagram](architecture.png)

All sockets are associated with a context map, which consists of two threads:

+ One thread manages ZeroMQ sockets and conveys incoming values to the application via a core.async channel (the "ZeroMQ thread")
+ One thread manages core.async channels and writes to a ZeroMQ control socket (the "core.async thread")

Each thread blocks with the appropriate selection construct (`zmq_poll` and `alts!!`, respectively) rather than an explicit polling loop.
Thus, each thread must initially communicate with the other via the other's transport.
The core.async thread notifies the ZeroMQ thread that it needs to do something by writing to an in-process control socket ("the ZeroMQ control socket").
However, since Java objects cannot be serialized over ZeroMQ, the core.async thread communicates "out-of-band" to the ZeroMQ thread via a java.util.concurrent queue (basically just yelling on the ZeroMQ control socket "yo, I just put something on the queue for you to handle").
Depending on what the queue references, the ZeroMQ thread will either:

+ write a value out to a ZeroMQ socket, `[sock-id val]`, where `val` can be a string (TODO: ByteArray, or ByteBuffer)
+ register a new socket, `[:register sock-id sock]`, where `sock-id` is a string and `sock` is a ZeroMQ socket object that is ready to be read from or written to (i.e., it has already been bound or connected).
+ close a socket, `[:close sock-id]`.

The ZeroMQ thread writes `[sock-id val]` to the core.async thread's control channel when it receives value `val` from the socket with identifier `sock-id`.

Sockets are closed when their corresponding core.async send channel(s) are closed.
The returned core.async channels are unbuffered since 1) ZeroMQ sockets already have internal buffering and 2) if channel buffering is desired it can be added by wrapping the unbuffered channel.

## Thanks

Thanks to @brandonbloom for the initial architecture idea, @zachallaun for pair programming/debugging, @ztellman for advice on error handling and all of the non-happy code paths, @puredanger for suggestions about naming and daemonizing the Java threads, @richhickey for the suggestions to explicitly handle all blocking combinations in a matrix, require explicit buffering semantics from consumers, and to accept byte buffers instead of just arrays, and @halgari for requesting multiple message pump pairs to avoid large-data reads from blocking potentially high-priority smaller messages.


## TODO (?)

+ This library needs a better name.
+ How to handle case where ZeroMQ send blocks the entire ZeroMQ thread? Can use ZMQ_NOBLOCK when writing and then dance back/forth to convey that to the consumer.
+ Handle ByteBuffers in addition to just strings.
+ Automatic fan-out from a single ZeroMQ socket to multiple core.async channels; should we try to "do the right thing" when clients ask for a new socket on an address that a socket is already bound to, or should we just let ZeroMQ's behavior (the newer socket object takes over) bleed through?
+ Implement core.async protocols to make a "spliced channel" and/or "channel pairs" that can be read and written instead of returning a pair of plain core.async unbuffered channels.

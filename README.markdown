# ZeroMQ Async

ZeroMQ is a message-oriented socket system that supports many communication styles (request/reply, publish/subscribe, fan-out, &c.) on top of many transport layers with bindings to many languages.
However, ZeroMQ sockets are not thread safe---concurrent usage typically requires explicit locking or dedicated threads and queues.
This library handles all of that for you, taking your ZeroMQ sockets and hiding them behind thread safe core.async channels.

[Quick start](#quick-start) | [Caveats](#caveats) | [Architecture](#architecture) | [Thanks!](#thanks) | [Other Clojure ZMQ libs](#other-clojure-zeromq-libraries)

## Quick start

Add to your `project.clj`:

    [com.keminglabs/zmq-async "0.1.0-SNAPSHOT"]
    
Your system should have ZeroMQ 3.2 installed:

    brew install zeromq

or

    apt-get install libzmq3
    
This library provides one function, `register-socket!`, which associates a ZeroMQ socket with core.async channel(s) `in` (into which you can write strings or byte arrays) and/or `out` (whence byte arrays).
Writing a Clojure collection of strings and/or byte arrays sends a multipart message; received multipart messages are put on core.async channels as a vector of byte arrays.

The easiest way to get started is to have zmq-async create sockets and the backing message pumps automagically for you:

```clojure
(require '[com.keminglabs.zmq-async.core :refer [register-socket!]]
         '[clojure.core.async :refer [>! <! go chan sliding-buffer close!]])

(let [n 3, addr "inproc://ping-pong"
      [s-in s-out c-in c-out] (repeatedly 4 #(chan (sliding-buffer 64)))]

  (register-socket! {:in s-in :out s-out :socket-type :rep
                     :configurator (fn [socket] (.bind socket addr))})
  (register-socket! {:in c-in :out c-out :socket-type :req
                     :configurator (fn [socket] (.connect socket addr))})

  (go (dotimes [_ n]
        (println (String. (<! s-out)))
        (>! s-in "pong"))
    (close! s-in))

  (go (dotimes [_ n]
        (>! c-in "ping")
        (println (String. (<! c-out))))
    (close! c-in)))
```

Note that you must provide a `:configurator` function to setup the newly instantiated socket, including binding/connecting it to addresses.
Take a look at the [jzmq javadocs](http://zeromq.github.io/jzmq/javadocs/) for more info on configuring ZeroMQ sockets.

Closing the core.async in channel associated with a socket closes the socket.

If you already have ZeroMQ sockets in hand, you can give them directly to this library:

```clojure
(import '(org.zeromq ZMQ ZContext))
(let [my-sock (doto (.createSocket (ZContext.) ZMQ/PAIR)
                ;;twiddle ZeroMQ socket options here...
                (.bind addr))]
  
  (register-socket! {:socket my-sock :in in :out out}))
```
(Of course, after you've created a ZeroMQ socket and handed it off to the library, you shouldn't read/write against it since the sockets aren't thread safe and doing so may crash your JVM.)

The implicit context supporting `register-socket!` can only handle one incoming/outgoing message at a time.
If you need sockets to work in parallel (i.e., you don't want to miss a small control message just because you're slurping in a 10GB message on another socket), then you'll need multiple zmq-async contexts.
Contexts accept an optional name to aid in debugging/profiling:

```clojure
(require '[zmq-async.core :refer [context initialize! register-socket!]])

(def my-context
  (doto (context "My Context")
    (initialize!)))

(register-socket! {:context my-context :in in :out out :socket my-sock})
```

Each context has an associated shutdown function, which will close all ZeroMQ sockets and core.async channels associated with the context and stop both threads:

```clojure
((:shutdown my-context))
```

## Caveats

+ The `out` ports provided to the library should never block writes, otherwise the async message pump thread will block and no messages will be able to go through that context in either direction.
  This may be enforced in the future with an exception (once core.async provides a mechanism for checking if a port can ever block writes).
+ The ZeroMQ thread will drop messages on the floor rather than blocking trying to hand it off to a socket.


## Architecture

![Architecture Diagram](architecture.png)

All sockets are associated with a context of two "message pump" threads:

+ One thread manages ZeroMQ sockets and conveys incoming values to the application via a core.async channel (the "ZeroMQ thread")
+ One thread manages core.async channels and writes to a ZeroMQ control socket (the "core.async thread")

Each thread blocks with the appropriate selection construct (`zmq_poll` and `alts!!`, respectively) rather than an explicit polling loop.
Thus, each thread must initially communicate with the other via the other's transport.
The core.async thread notifies the ZeroMQ thread that it needs to do something by writing to an in-process control socket ("the ZeroMQ control socket").
However, since Java objects cannot be serialized over ZeroMQ, the core.async thread communicates out-of-band to the ZeroMQ thread via a java.util.concurrent queue (basically just yelling on the ZeroMQ control socket "Unblock yo, I just put something on the queue for you to handle").
The ZeroMQ thread will then take from the queue and:

+ write a value out to a ZeroMQ socket, `[sock-id val]`, where `val` can be a string or byte array.
+ register a new socket, `[:register sock-id sock]`, where `sock-id` is a string and `sock` is a ZeroMQ socket object that is ready to be read from or written to (i.e., it has already been bound or connected).
+ close a socket, `[:close sock-id]`.

The ZeroMQ thread writes `[sock-id val]` to the core.async thread's control channel when it receives value `val` from the socket with identifier `sock-id`.

Sockets are closed when their corresponding core.async in channel is closed.

Now, you may be wondering: why not just create two new threads for each ZeroMQ socket (one blocking on incoming messages from the socket and another blocking on outgoing messages from the core.async channel).
Conceptually, this is much simpler and would be reflected in the codebase.
However, the theory upon which this library rests is that ZeroMQ communications are IO-bound, not CPU-bound.
A single pair of threads should be capable of handling all traffic, and thus are preferred over a thread pair for each ZeroMQ socket if only because library consumers may want to handle hundreds of ZeroMQ socket connections and spinning up a pair of threads for each would be gratuitous.
Your use case may vary, in which case you should benchmark.


## Thanks

Thanks to @brandonbloom for the initial architecture idea, @zachallaun for pair programming/debugging, @ztellman for advice on error handling and all of the non-happy code paths, @puredanger for suggestions about naming and daemonizing the Java threads, @richhickey for the suggestions to explicitly handle all blocking combinations in a matrix, require explicit buffering semantics from consumers, and to accept byte buffers instead of just arrays, and @halgari for requesting multiple message pump pairs to avoid large-data reads from blocking potentially high-priority smaller messages.


## Other Clojure ZeroMQ libraries

I looked at several ZeroMQ/Clojure bindings before writing this one: [Zilch](https://github.com/dysinger/zilch), [clj-0MQ](https://github.com/AndreasKostler/clj-0MQ), and [ezmq](https://github.com/tel/ezmq) haven't been updated in the past two years and don't offer much more than a thin layer of Clojure over native Java interop calls.

After I started work on this library, an [official ZeroMQ Clojure binding](https://github.com/zeromq/cljzmq) was released, but it also seems like just a thin layer of Clojure over [jzmq](https://github.com/zeromq/jzmq) (the underlying ZeroMQ Java binding that zmq-async also uses) and doesn't seem to offer any help for using ZeroMQ sockets concurrently.

Finally, this library ships with native Linux 64 and OS X 64 compiled bindings to ZeroMQ 3.2.
As long as you're on x64 Linux or OS X, you don't have to manually compile and install jzmq.
See the [project.clj](project.clj) for the SHA of the jzmq commit compiled into this library.


## TODO (?)

+ Handle ByteBuffers in addition to just strings and byte arrays.
+ Enforce that provided ports never block and/or are read/write only as appropriate.
+ Add [shutdown hooks](http://docs.oracle.com/javase/7/docs/api/java/lang/Runtime.html#addShutdownHook(java.lang.Thread)) to context objects?

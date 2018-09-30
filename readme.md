[![Build Status](https://travis-ci.org/vodori/reactors.svg?branch=develop)](https://travis-ci.org/vodori/reactors) [![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/vodori/reactors/maven-metadata.xml.svg)](https://mvnrepository.com/artifact/com.vodori/reactors)

### Reactors

A Clojure library to provide structure around shared in-memory state, incorporating 
live changes, and broadcasting change deltas to observers. State is maintained using 
clojure agents and supports a configurable crash recovery strategy.

### Rationale

Reactors provide leverage when implementing server-heavy collaborative processes 
by reigning in the independent sources of change and then broadcasting zero or more 
messages to the subscribers based on differences between the old and new state.

While you can certainly create lots of similar things yourself, 
reactors implements a couple of key ideas that we value:

* A unified way to communicate state to new subscribers and existing subscribers as things change.
* Recovery of errors that might have tainted the accumulated view of the current state. 

### Stability

We use reactors in a production capacity. We think the abstractions are simple 
but useful and so are unlikely to change.


### Core Abstractions:

A reactor consists of some state _(initializer)_, sources of change _(publishers)_, 
observers of state _(subscribers)_, a function for incorporating change _(reducer)_, 
and a function for describing changes in state to observers _(emitter)_.

___


#### Initializer

A function of no arguments that is used to "boot" the reactor. Also used for any "reboots" 
after a crash. It's just a function that should return the initial state to be contained in
the reactor before starting to incorporate events from publishers. This function is free to 
do dangerous things. If anything fails it will be retried according to the recovery strategy.

```clojure
(defn initializer []
  {})
```


#### Publishers

A map of opaque identifiers to core.async channels representing sources of change. 
Publishers can be added to and removed from a reactor at any time.

```clojure
(def publishers 
 {::database (get-database-change-stream-chan)
  ::webhooks (get-incoming-webhook-events-chan)})
```

#### Subscribers

A map of opaque identifiers to core.async channels representing observers of state. 
Subscribers can be added to and removed from a reactor at any time. 

```clojure
(def subscribers 
 {::paul (username->websocket-chan "paul@example.com")
  ::eric (username->websocket-chan "eric@example.com")})
```

#### Reducer

A function for incorporating change into current state. It's okay if this
function does things like making database calls to gather additional information 
because it runs on the agent which can be restarted if it crashes. The first argument
is the current state contained by the reactor and the second argument is a tuple of 
the publisher identity and the event itself.

```clojure
(defn reducer [state [publisher event]]
  (case (:kind event)
    :insert (assoc state [(:id event)] (:data event))
    :delete (dissoc state (:id event))
    state))
```

#### Emitter

A function for deciding what changes in state should be broadcast to subscribers. This
function receives the old state and new state (after a successful run of the reducer)
and should return a sequence of messages to be broadcast to subscribers. Your emitter must
be a pure function and should not make assumptions about the way in which state is known to
change in your application (it should detect them instead).

```clojure 
(defn emitter [old-state new-state]
  (let [[added removed] (data/diff (keys new-state) (keys old-state))]
    (cond-> []
      (not-empty added) (conj {:event :added :data (mapv new-state added)})
      (not-empty removed) (conj {:event :removed :data (mapv old-state removed)})))
```

___

### Install

``` 
[com.vodori/reactors "0.1.0"]
```
___


### Usage

```clojure
; wait 10 millis to start
; double the wait every time
; only restart up to 10 times (then implode!)
(def recovery-policy
  (take 10 (iterate (partial * 2) 10)))

; create the reactor
(def reactor 
  (-> {:reducer reducer 
       :emitter emitter 
       :backoff recovery-policy
       :initializer initializer}
       
      (reactors/create-reactor)
      
      ; you can also do this any time throughout the
      ; life of the reactor (as users come and go)
      (reactors/add-publishers publishers)
      (reactors/add-subscribers subscribers)
      
      ; start listening to events from publishers
      ; and broadcasting incorporated changes to any
      ; subscribers
      (reactors/start!)))
      
```

___ 

### License
This project is licensed under [MIT license](http://opensource.org/licenses/MIT).
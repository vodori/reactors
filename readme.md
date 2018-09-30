[![Build Status](https://travis-ci.org/vodori/reactors.svg?branch=develop)](https://travis-ci.org/vodori/reactors) [![Maven metadata URL](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/vodori/reactors/maven-metadata.xml.svg)](https://mvnrepository.com/artifact/com.vodori/reactors)

### Reactors

A Clojure library to provide structure around shared in-memory state, incorporating 
live changes, and broadcasting change deltas to observers. State is maintained using 
clojure agents and supports a configurable crash recovery strategy.


___

### Install

``` 
[com.vodori/reactors "0.1.0"]
```

___

### Rationale

Reactors is meant to produce leverage when implementing server-heavy collaborative processes 
by serializing the independent sources of change and broadcasting zero or more messages to the
subscribers based on differences between the old and new state.

While you can certainly create lots of similar things using clojure primitives, 
reactors has a couple key ideas:

* A unified approach for "new subscriber who knows nothing about this yet" and "existing subscriber knows all but the latest"
  * Going from `state(t0)` to `state(tn)` should follow the same mechanism as going from `state(tn-1)` to `state(tn)`.
  * We implement this by calling the emitter with an empty map as the old state (`state(t0)`)
* Recovery of errors that might have caused the "accumulated view of the current state" to no longer be valid. 
  * We do this by rebooting the reactor (à la OTP) and having subscribers discard what they had known.

___

### Stability

We use reactors in a production capacity. Our application supports hundreds of reactors
at a time, each one siphoning relevant events off of a change stream from our database and supporting 
clusters of users collaborating on the same resource (via channels that feed to their websocket 
connection).

___


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

core.async channels representing sources of change. Publishers can be added
to and removed from a reactor at any time.


Example:

```clojure
(def publishers 
 {::database (get-database-change-stream-chan)
  ::webhooks (get-incoming-webhook-events-chan)})
```

#### Subscribers

core.async channels representing observers of change. Subscribers can be added
to and removed from a reactor at any time.

```clojure
(def subscribers 
 {::paul (username->websocket-chan "paul@example.com")
  ::eric (username->websocket-chan "eric@example.com")})
```

#### Reducer

A function for incorporating change into current state. It's okay if this
function does things like making database calls to gather additional information 
because it runs on the agent which can be restarted if it crashes.

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
      (reactors/create-reactor)))
      
(reactors/add-publishers reactor publishers)

; you can do this any time throughout the life
; of the reactor (as users come and go, etc)
(reactors/add-subscribers reactor subscribers)

; it's now listening to events from publishers
; and broadcasting incorporated changes to any
; subscribers
(reactors/start! reactor)
```

___ 

### License
This project is licensed under [MIT license](http://opensource.org/licenses/MIT).
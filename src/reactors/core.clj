(ns reactors.core
  (:require [clojure.set :as sets]
            [clojure.core.async :as async]
            [taoensso.timbre :as logger])
  (:import (clojure.lang Agent)
           (java.util UUID)))

(defmacro quietly [& body]
  `(try ~@body (catch Exception e# nil)))

(defn attach-channel-cleanup [chan f]
  (add-watch
    (.closed chan)
    (str "cleanup." (UUID/randomUUID))
    (fn [_ _ old-state new-state]
      (when (and (and (not old-state) new-state))
        (quietly (f)))))
  chan)

(defn changed?
  ([key old-state new-state]
   (changed? key old-state new-state nil))
  ([key old-state new-state default]
   (not= (or (get old-state key) default)
         (or (get new-state key) default))))

(defn get-added [key o n]
  (->> (sets/difference
         (set (keys (get n key {})))
         (set (keys (get o key {}))))
       (select-keys (get n key {}))))

(defn get-removed [key o n]
  (get-added key n o))

(defn current-reactor []
  (some->> 'clojure.core/*agent* find-var var-get deref :self deref))

(defn send-messages [messages subscribers]
  (when (not-empty messages)
    (->> (for [[_ chan] (or subscribers {})]
           (async/onto-chan chan messages false))
         (doall)
         (run! async/<!!))))

(defn started? [o n]
  (and (not (:started o)) (:started n)))

(defn running? [o n]
  (and (:started o) (:started n)))

(defn publishers-on-start [k r o {:keys [reducer publishers] :as n}]
  (when (started? o n)
    (doseq [[ident chan] publishers]
      (async/go-loop [msg (async/<! chan)]
                     (when (some? msg)
                       (try (send-off r update :state reducer [ident msg])
                            (catch Exception e))
                       (recur (async/<! chan)))))))

(defn publishers-on-change [k r o {:keys [reducer] :as n}]
  (when (running? o n)
    (run! async/close! (vals (get-removed :publishers o n)))
    (doseq [[ident chan] (get-added :publishers o n)]
      (async/go-loop [msg (async/<! chan)]
                     (when (some? msg)
                       (try (send-off r update :state reducer [ident msg])
                            (catch Exception e))
                       (recur (async/<! chan)))))))

(defn subscribers-on-start [k r o {:keys [emitter subscribers] :as n}]
  (when (and (started? o n) (not-empty subscribers))
    (send-messages (emitter {} (:state n)) subscribers)))

(defn subscribers-on-change [k r o {:keys [emitter] :as n}]
  (when (and (running? o n) (changed? :subscribers o n {}))
    (run! async/close! (vals (get-removed :subscribers o n)))
    (let [new-subscribers (get-added :subscribers o n)]
      (when (not-empty new-subscribers)
        (send-messages (emitter {} (:state n)) new-subscribers)))))

(defn implode! [{:keys [subscribers publishers destructors]}]
  (run! async/close! (vals (or subscribers {})))
  (run! async/close! (vals (or publishers {})))
  (run! #(quietly (%)) (vals (into (sorted-map) (or destructors {})))))

(defn all-subscribers-removed [k r o n]
  (when (and (running? o n)
             (empty? (:subscribers n))
             (not-empty (:subscribers o)))
    (implode! n)))

(defn state-changed [k r o {:keys [emitter subscribers] :as n}]
  (when (and (running? o n) (changed? :state o n {}) (not-empty subscribers))
    (send-messages (emitter (:state o) (:state n)) subscribers)))

(defn reboot-agent! [agent]
  (let [{:keys [backoff restarts] :as a-state} @agent]
    (if-some [millis (first backoff)]
      (do
        (logger/info (format "Attempting reboot %d of reactor after sleeping %d millis" (inc restarts) millis))
        (Thread/sleep millis)
        (let [changes {:state {} :restarts (inc restarts) :backoff (rest backoff)}]
          (restart-agent agent (merge a-state changes) :clear-actions true))
        (send-off agent
          (fn [{:keys [initializer] :as a}]
            (assoc a :state (initializer)))))
      (do (logger/error "Reached end of reboot attempts. Imploding!")
          (implode! a-state)))))

(defn crashed [agent error]
  (logger/error error "Reactor crashed!")
  (future (reboot-agent! agent)))

(defn recursive-await
  "Like await, except it waits on actions dispatched by actions."
  [^Agent a]
  (loop [size (.getQueueCount a)]
    (if (zero? size)
      a
      (do (await a)
          (recur (.getQueueCount a))))))

(defprotocol Reactor
  (start! [this]
    "Starts the reactor. This emits the initial state to any subscribers.")
  (update! [this f]
    "Change the state independently of a publisher.")
  (update-blocking! [this f]
    "Change the state independently of a publisher with a function that may block.")
  (await! [this]
    "Blocks the thread until the message queue for the reactor is empty.")
  (reboot! [this]
    "Crashes the reactor so that it reboots itself. Counts against the backoff policy.")
  (get-state [this]
    "Returns the current state of the reactor.")
  (get-agent [this]
    "Returns the agent that is used to implement the reactor.")
  (get-publishers [this]
    "Returns the idents of the publishers feeding to the reactor.")
  (get-subscribers [this]
    "Returns the idents of the subscribers watching the reactor.")
  (set-reducer! [this reducer]
    "Sets the function for performing reductions of change into the reactor state.")
  (set-emitter! [this emitter]
    "Sets the function for emitting events based on changes in the reactor state.")
  (set-backoff! [this backoff]
    "Backoff policy represented by a sequence (potentially infinite) of milliseconds to wait between reboots. Once empty, the reactor will implode.")
  (set-initializer! [this initializer]
    "Sets the function for populating the initial state of the reactor, also used to repopulate after a crash.")
  (add-publishers! [this publishers]
    "Adds publishers (a map of idents to core.async channels delivering changes).")
  (remove-publishers! [this publishers]
    "Removes publishers by their idents. A removed publisher is closed.")
  (add-subscribers! [this subscribers]
    "Adds subscribers (a map of idents to core.async channels listening for changes).")
  (remove-subscribers! [this publishers]
    "Removes subscribers by their idents. A removed subscriber is closed.")
  (add-destructors! [this destructors]
    "Adds destructors (a map of idents to functions to call when the reactor is destroyed).")
  (remove-destructors! [this destructors]
    "Removes destructors by their idents."))

(defn create-reactor
  ([] (create-reactor {}))
  ([{:keys [backoff emitter reducer initializer] :as opts}]
   (let [self    (atom nil)
         reactor (agent (merge
                          {:backoff     (take 8 (iterate (partial * 2) 500))
                           :emitter     (fn [_ _] [])
                           :reducer     (fn [state _] state)
                           :initializer (constantly {})}
                          opts
                          {:publishers  {}
                           :subscribers {}
                           :destructors {}
                           :state       {}
                           :self        self
                           :started     false
                           :restarts    0}))]

     (set-error-handler! reactor crashed)
     (add-watch reactor :PUBLISHERS_ON_START publishers-on-start)
     (add-watch reactor :PUBLISHERS_ON_CHANGE publishers-on-change)
     (add-watch reactor :SUBSCRIBERS_ON_START subscribers-on-start)
     (add-watch reactor :SUBSCRIBERS_ON_CHANGE subscribers-on-change)
     (add-watch reactor :ALL_SUBSCRIBERS_REMOVED all-subscribers-removed)
     (add-watch reactor :STATE_CHANGE state-changed)

     (->>
       (reify Reactor

         (start! [this]
           (send-off reactor
             (fn [{:keys [initializer] :as a}]
               (assoc a :state (initializer) :started true)))
           (await! this))

         (await! [this]
           (recursive-await reactor)
           this)

         (get-agent [this]
           reactor)

         (get-state [this]
           (get @reactor :state))

         (get-publishers [this]
           (set (keys (get @reactor :publishers))))

         (get-subscribers [this]
           (set (keys (get @reactor :subscribers))))

         (reboot! [this]
           (send reactor (fn [_] (throw (ex-info "Reactor reboot requested!" {}))))
           this)

         (update! [this f]
           (send reactor update :state f)
           this)

         (update-blocking! [this f]
           (send-off reactor update :state f)
           this)

         (set-reducer! [this reducer]
           (send reactor assoc :reducer reducer)
           this)

         (set-emitter! [this emitter]
           (send reactor assoc :emitter emitter)
           this)

         (set-backoff! [this backoff]
           (send reactor assoc :backoff backoff)
           this)

         (set-initializer! [this initializer]
           (send reactor assoc :initializer initializer)
           this)

         (add-publishers! [this publishers]
           (let [watched-publishers
                 (->
                   (fn [agg [k v]]
                     (assoc agg k
                       (attach-channel-cleanup v (partial remove-publishers! this #{k}))))
                   (reduce {} publishers))]
             (send reactor update :publishers merge watched-publishers)
             this))

         (remove-publishers! [this publishers]
           (send reactor update :publishers #(apply dissoc % publishers))
           this)

         (add-subscribers! [this subscribers]
           (let [watched-subscribers
                 (->
                   (fn [agg [k v]]
                     (assoc agg k
                       (attach-channel-cleanup v (partial remove-subscribers! this #{k}))))
                   (reduce {} subscribers))]
             (send reactor update :subscribers merge watched-subscribers)
             this))

         (remove-subscribers! [this subscribers]
           (send reactor update :subscribers #(apply dissoc % subscribers))
           this)

         (add-destructors! [this destructors]
           (send reactor update :destructors merge destructors)
           this)

         (remove-destructors! [this destructors]
           (send reactor update :destructors #(apply dissoc % destructors))
           this))

       (reset! self)))))
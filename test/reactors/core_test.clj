(ns reactors.core-test
  (:require [clojure.test :refer :all]
            [reactors.core :refer :all]
            [clojure.core.async :as async]
            [clojure.set :as sets]))


(deftest create-reactor-test
  (let [destroyed   (atom false)
        subscriber1 (async/chan 10)
        subscriber2 (async/chan 10)
        reactor     (->
                      (create-reactor)
                      (set-emitter! (fn [o n] [n]))
                      (set-initializer! (constantly {:count 1}))
                      (add-destructors! {:? #(reset! destroyed true)})
                      (add-subscribers! {:subscriber1 subscriber1})
                      (start!))]
    (is (= {:count 1} (async/<!! subscriber1)))
    (-> reactor (add-subscribers! {:subscriber2 subscriber2}) (await!))
    (is (= {:count 1} (async/<!! subscriber2)))
    (-> reactor (remove-subscribers! #{:subscriber1}) (await!))
    (is (false? @destroyed))
    (-> reactor (remove-subscribers! #{:subscriber2}) (await!))
    (is (true? @destroyed))))

(defn lift-mode [f]
  (let [mode (atom true)]
    [mode (fn [& args]
            (if @mode
              (apply f args)
              (throw (ex-info "Crash!" {}))))]))

(defn emission [old new]
  (let [keys (sets/difference (set (keys new)) (set (keys old)))]
    (if-not (empty? keys) [keys] [])))

(defn reduction [state [publisher change]]
  (merge state change))

(defn toggle! [a]
  (swap! a not))

(deftest reducer-crash-recovery
  (let [[mode reducer] (lift-mode reduction)
        publisher  (async/chan 10)
        subscriber (async/chan 10)
        reactor    (->
                     (create-reactor)
                     (set-reducer! reducer)
                     (set-emitter! emission)
                     (set-initializer! (constantly {:zero 0}))
                     (add-publishers! {:publisher publisher})
                     (add-subscribers! {:subscriber subscriber})
                     (start!))]

    (async/>!! publisher {:one 1})

    ; initial state emitted on start
    (is (= #{:zero} (async/<!! subscriber)))

    ; emitted after change from publisher
    (is (= #{:one} (async/<!! subscriber)))

    ; show nothing else was queued
    (is (nil? (async/poll! subscriber)))

    ; failure mode
    (toggle! mode)

    ; cause a reactor crash
    (async/>!! publisher {:two 1})

    ; re-emits the full current state after recovery
    (is (= #{:zero} (async/<!! subscriber)))

    ; show nothing else was queued
    (is (nil? (async/poll! subscriber)))

    ; crash again
    (async/>!! publisher {:two 1})

    ; re-emit the full current state after recovery
    (is (= #{:zero} (async/<!! subscriber)))

    ; show nothing else was queued
    (is (nil? (async/poll! subscriber)))

    ; stable mode again
    (toggle! mode)

    ; should get reduced
    (async/>!! publisher {:two 1})

    ; should receive the emission
    (is (= #{:two} (async/<!! subscriber)))

    ; show nothing else was queued
    (is (nil? (async/poll! subscriber)))))
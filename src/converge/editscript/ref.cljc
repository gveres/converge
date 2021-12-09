;; Copyright 2020 Evident Systems LLC

;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at

;;     http://www.apache.org/licenses/LICENSE-2.0

;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns converge.editscript.ref
  (:require [clojure.data.avl :as avl]
            [editscript.core :as e]
            [converge.core :as core]
            [converge.util :as util]
            [converge.editscript.ops :as ops])
  #?(:clj (:import [clojure.lang IAtom IReference IRef])))

#?(:clj  (set! *warn-on-reflection* true)
   :cljs (set! *warn-on-infer* true))

(defn apply-patch
  [value {:keys [action data]}]
  (condp = action
    ops/EDIT
    (e/patch value (e/edits->script (:edits data)))

    value))

(defn value-from-ops
  [ops]
  (reduce apply-patch nil (vals ops)))

(deftype EditscriptConvergentRef #?(:clj  [^:volatile-mutable actor
                                           ^:volatile-mutable state
                                           ^:volatile-mutable patches
                                           ^:volatile-mutable meta
                                           ^:volatile-mutable validator
                                           ^:volatile-mutable watches]
                                    :cljs [^:mutable actor
                                           ^:mutable state
                                           ^:mutable patches
                                           meta
                                           validator
                                           ^:mutable watches])

  core/ConvergentRef
  (-actor [_] actor)
  (-state [_] state)
  (-set-actor! [_ new-actor] (set! actor new-actor))
  (-log [_] (:log state))
  (-apply-state! [this new-state]
    (let [old-value (:value state)]
      (set! state new-state)
      (core/notify-w this watches old-value (:value new-state))))
  (-make-patch
    [_ new-value]
    (when (ifn? validator)
      (assert (validator new-value) "Validator rejected reference state"))
    (let [edits (e/get-edits (e/diff (:value state) new-value {:str-diff? true}))]
      (when (pos? (count edits))
        (core/->Patch (-> state :log core/ref-root-data-from-log :id)
                      (avl/sorted-map
                       (core/next-id (:log state) actor)
                       (ops/edit edits))))))
  (-state-from-patch [_ patch]
    (if (core/patch? patch)
      (let [{:keys [ops]}
            patch

            new-log
            (into (:log state) ops)]
        (core/->ConvergentState new-log
                                nil
                                (value-from-ops new-log)
                                false))
      state))
  (-peek-patches [_] (peek patches))
  (-pop-patches! [_] (set! patches (pop patches)))
  (-value-from-ops [_ ops] (value-from-ops ops))

  #?@(:clj
      [IAtom
       (reset
        [this new-value]
        (let [patch     (core/-make-patch this new-value)
              new-state (core/-state-from-patch this patch)]
          (core/validate-reset (:value state) new-value new-state patch)
          (when patch (set! patches (conj patches patch)))
          (core/-apply-state! this new-state)
          (:value new-state)))
       (swap [this f]          (.reset this (f (:value state))))
       (swap [this f a]        (.reset this (f (:value state) a)))
       (swap [this f a b]      (.reset this (f (:value state) a b)))
       (swap [this f a b args] (.reset this (apply f (:value state) a b args)))
       (compareAndSet
        [this old-value new-value]
        (if (= (.deref this) old-value)
          (do (.reset this new-value) true)
          false))

       IReference
       (meta [_] meta)
       (alterMeta [this f args] (.resetMeta this (apply f meta args)))
       (resetMeta [_ new-meta]  (set! meta new-meta))

       IRef
       ;; TODO: refactor this out, and only do these impls in one place
       (deref
        [_]
        (let [{:keys [log value dirty?] :as s}
              state]
          (if dirty?
            (let [new-value (value-from-ops log)]
              (set! state
                    (assoc s
                           :value  new-value
                           :dirty? false))
              new-value)
            value)))
       (setValidator
        [_ f]
        (when (ifn? f)
          (assert (f (:value state)) "Validator rejected reference state")
          (set! validator f)))
       (getValidator [_] validator)
       (getWatches   [_] watches)
       (addWatch
        [this k callback]
        (set! watches (assoc watches k callback))
        this)
       (removeWatch
        [this k]
        (set! watches (dissoc watches k))
        this)]

      :cljs
      [IAtom

       IEquiv
       (-equiv [this other] (identical? this other))

       IDeref
       ;; TODO: refactor this out, and only do these impls in one place
       (-deref
        [_]
        (let [{:keys [log value dirty?] :as s}
              state]
          (if dirty?
            (let [new-value (value-from-ops log)]
              (set! state
                    (assoc s
                           :value  new-value
                           :dirty? false))
              new-value)
            value)))

       IReset
       (-reset!
        [this new-value]
        (let [patch     (core/-make-patch this new-value)
              new-state (core/-state-from-patch this patch)]
          (core/validate-reset (:value state) new-value new-state patch)
          (when patch (set! patches (conj patches patch)))
          (core/-apply-state! this new-state)
          (:value new-state)))

       ISwap
       (-swap! [this f]          (-reset! this (f (:value state))))
       (-swap! [this f a]        (-reset! this (f (:value state) a)))
       (-swap! [this f a b]      (-reset! this (f (:value state) a b)))
       (-swap! [this f a b args] (-reset! this (apply f (:value state) a b args)))

       IWithMeta
       (-with-meta [_ new-meta] (EditscriptConvergentRef. actor state patches new-meta validator watches))

       IMeta
       (-meta [_] meta)

       IPrintWithWriter
       (-pr-writer [this writer opts]
                   (-write writer "#object[converge.editscript.ref.EditscriptConvergentRef ")
                   (pr-writer {:val (-deref this)} writer opts)
                   (-write writer "]"))

       IWatchable
       (-notify-watches
        [this old-value new-value]
        (core/notify-w this watches old-value new-value))
       (-add-watch
        [this k callback]
        (set! watches (assoc watches k callback))
        this)
       (-remove-watch
        [this k]
        (set! watches (dissoc watches k))
        this)

       IHash
       (-hash [this] (goog/getUid this))]))

(defmethod core/make-ref :editscript
  [{:keys [log initial-value actor meta validator]}]
  (let [r (->EditscriptConvergentRef actor
                                     (core/->ConvergentState log nil nil true)
                                     (util/queue)
                                     meta
                                     validator
                                     nil)]
    (reset! r initial-value)
    r))

(defmethod core/make-ref-from-ops :editscript
  [{:keys [ops actor meta validator]}]
  (->EditscriptConvergentRef actor
                             (core/->ConvergentState ops nil nil true)
                             (util/queue)
                             meta
                             validator
                             nil))

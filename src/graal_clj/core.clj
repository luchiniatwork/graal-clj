(ns graal-clj.core
  (:refer-clojure :exclude [promise resolve eval])
  (:require [clojure.java.io :as io])
  (:import (clojure.lang IFn)
           (java.util.function Consumer)
           (org.graalvm.polyglot Context
                                 Context$Builder
                                 Value
                                 Source)
           (org.graalvm.polyglot.proxy ProxyArray
                                       ProxyExecutable
                                       ProxyObject)))

(set! *warn-on-reflection* true)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Core functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn clj->params [v]
  (cond
    (or (sequential? v) (set? v))
    (->> v
         (map clj->params)
         object-array
         ProxyArray/fromArray)

    (map? v)
    (ProxyObject/fromMap (reduce-kv (fn [m k v]
                                      (assoc m
                                             (clj->params k)
                                             (clj->params v)))
                                    {} v))

    (or (keyword? v) (symbol? v))
    (let [k-ns (namespace v)
          k (name v)]
      (if k-ns
        (str k-ns "/" k)
        k))
    
    :else v))

(defn ^:private execute
  [^Value execable & args]
  (.execute execable (object-array (map clj->params args))))

(declare value->clj)

(defmacro ^:private reify-ifn
  "Convenience macro for reifying IFn for executable polyglot Values."
  [v]
  (let [invoke-arity
        (fn [n]
          (let [args (map #(symbol (str "arg" (inc %))) (range n))]
            (if (seq args)
              ;; TODO test edge case for final `invoke` arity w/varargs
              `(~'invoke [this# ~@args] (value->clj (execute ~v ~@args)))
              `(~'invoke [this#] (value->clj (execute ~v))))))]
    `(reify IFn
       ~@(map invoke-arity (range 22))
       (~'applyTo [this# args#] (value->clj (apply execute ~v args#))))))

(defn proxy-fn
  "Returns a ProxyExecutable instance for given function, allowing it to be
   invoked from polyglot contexts."
  [f]
  (reify ProxyExecutable
    (execute [_this args]
      (apply f (map value->clj args)))))

(definterface IThenable
  (then [^org.graalvm.polyglot.Value resolve
         ^org.graalvm.polyglot.Value reject]))

(defn async-fn [f]
  (reify
    IThenable
    (then [_ resolve reject]
      (apply f [(value->clj resolve)
                (value->clj reject)]))))

(defn value->clj
  "Returns a Clojure (or Java) value for given polyglot Value if possible,
   otherwise throws."
  [^Value v]
  (cond
    (.isNull v) nil
    (.isHostObject v) (.asHostObject v)
    (.isBoolean v) (.asBoolean v)
    (.isString v) (.asString v)
    (.isNumber v) (.as v Number)
    (.canExecute v) (reify-ifn v)
    (.hasArrayElements v) (into []
                                (for [i (range (.getArraySize v))]
                                  (value->clj (.getArrayElement v i))))
    (.hasMembers v) (into {}
                          (for [k (.getMemberKeys v)]
                            [k (value->clj (.getMember v k))]))
    :else (throw (Exception. "Unsupported value"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Context functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-options
  {"js.interop-complete-promises" "true"
   "js.commonjs-require"          "true"
   "js.commonjs-require-cwd"      "."})

(defn context-from-builder [^Context$Builder builder]
  ^Context (.build builder))

(defn apply-builder-defaults
  ([^Context$Builder builder]
   ^Context$Builder (apply-builder-defaults builder nil))
  ([^Context$Builder builder options]
   ^Context$Builder (let [options' (merge default-options options)]
                      (-> builder
                          (.allowExperimentalOptions true)
                          (.allowAllAccess true))
                      (doseq [[k v] options']
                        (.option builder k v)))))

(defn create-context
  ([langs]
   ^Context (create-context langs nil))
  ([langs options]
   ^Context (let [langs' (if (seq? langs) langs [langs])
                  builder (Context/newBuilder (into-array langs'))]
              (apply-builder-defaults builder options)
              (context-from-builder builder))))

(defn close-context [^Context ctx]
  (.close ctx))

(defmacro with-context [bindings & body]
  `(with-open ~bindings ~@body))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Bindings and members functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-bindings [^Context ctx ^String lang]
  ^Value (.getBindings ctx lang))

(defn has-members? [^Value v]
  (.hasMembers v))

(defn has-member? [^Value v ^String id]
  (.hasMember v id))

(defn member-keys [^Value v]
  (vec (.getMemberKeys v)))

(defn get-member
  [^Value v ^String id]
  ^Value (.getMember v id))

(defn get-member-in [^Value v coll]
  (reduce (fn [a i]
            (get-member a i))
          v coll))

(defn put-member [^Value v ^String id x]
  (.putMember v id x))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Eval functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn eval
  ([^Context ctx ^Source src]
   ^Value (.eval ctx src))
  ([^Context ctx ^String lang ^String code]
   ^Value (.eval ctx lang code)))

(defn eval-parse
  ([^Context ctx ^Source src]
   (value->clj (eval ctx src)))
  ([^Context ctx ^String lang ^String code]
   (value->clj (eval ctx lang code))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Source functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn source
  ([f]
   ^Source (if-let [lang (-> f io/file Source/findLanguage)]
             (source f lang)
             (throw (str "Trouble detecting language for file " f))))
  ([f ^String lang]
   ^Source (->> f
                io/file
                (Source/newBuilder lang)
                .build)))

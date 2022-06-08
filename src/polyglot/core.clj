(ns polyglot.core
  (:refer-clojure :exclude [promise resolve])
  (:import (clojure.lang IFn)
           (java.util.function Consumer)
           (org.graalvm.polyglot Context
                                 Value)
           (org.graalvm.polyglot.proxy ProxyArray
                                       ProxyExecutable
                                       ProxyObject)))

(set! *warn-on-reflection* true)

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

(defn- execute
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
#_(macroexpand '(reify-ifn v))

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

(defn create-context []
  ^Context (-> (Context/newBuilder (into-array ["js"]))
               (.allowExperimentalOptions true)
               (.option "js.interop-complete-promises" "true")
               (.allowAllAccess true)
               .build))

(defn close-context [^Context ctx]
  (.close ctx))

;; TODO
#_(macro with-context [])

(defn get-member [^Context ctx ^String id]
  ^Value (.getMember (.getBindings ctx "js") id))

(defn put-member [^Context ctx ^String id x]
  (.putMember (.getBindings ctx "js") id x))

(defn eval-js [ctx code]
  ^Value (.eval ^Context ctx "js" code))




(comment

  (def context (create-context))

  (def js->clj (comp value->clj
                     (partial eval-js context)))

  (js->clj "[{}]")
                                        ;=> [{}]

  (js->clj "false")
                                        ;=> false
  (js->clj "3 / 3.33")
                                        ;=> 0.9009009009009009
  (js->clj "123123123123123123123123123123123")
                                        ;=> 1.2312312312312312E32

  (def doubler (js->clj "(n) => {return n * 2;}"))
  (doubler 2)
                                        ;=> 4

  (js->clj "m = {foo: 1, bar: '2', baz: {0: false}};")
                                        ;=> {"foo" 1, "bar" "2", "baz" {"0" false}}

  (def factorial
    (eval-js context "
      var m = [];
      function factorial (n) {
        if (n == 0 || n == 1) return 1;
        if (m[n] > 0) return m[n];
        return m[n] = factorial(n - 1) * n;
      }
      x = {fn: factorial, memos: m};"))
  ((get (value->clj factorial) "fn") 12)
                                        ;=> 479001600
  (get (value->clj factorial) "memos")
                                        ;=> [nil nil 2 6 24 120 720 5040 40320 362880 3628800 39916800 479001600]
  ((get (value->clj factorial) "fn") 24)
                                        ;=> 6.204484017332394E23
  (get (value->clj factorial) "memos")
                                        ;=> [nil nil 2 6 24 120 720 5040 40320 362880 3628800 39916800 479001600 ... truncated for brevity]

  (eval-js context "var foo = 0xFFFF")
  (eval-js context "console.log(foo);")
                                        ;=> #object[org.graalvm.polyglot.Value 0x3f9d2028 "undefined"]
                                        ;65535

  (js->clj "1 + '1'")
                                        ;=> "11"
  (js->clj "['foo', 10, 2].sort()")
                                        ;=> [10 2 "foo"]

  (def js-aset
    (js->clj "(arr, idx, val) => { arr[idx] = val; return arr; }"))
  (js-aset [1 2 3] 1 nil)
                                        ;=> [1 nil 3]

  ;; FIXME it returns differently because of keywords
  (def js-sort
    (js->clj "(...vs) => { return vs.sort(); }"))
  (apply js-sort [{:b nil} \a 1 "a" "A" #{\a} :foo -1 0 {:a nil} "bar"])
                                        ;=> [-1 0 1 "A" #{\a} :foo {:a nil} {:b nil} "a" "a" "bar"]

  ;; FIXME it returns differently because of keywords
  (def variadic-fn
    (js->clj "(x, y, ...z) => { return [x, y, z]; }"))
  (apply variadic-fn :foo :bar (range 3))
                                        ;=> [:foo :bar [0 1 2]]
  (def ->json
    (js->clj "(x) => { return JSON.stringify(x); }"))
  (->json [1 2 3])
                                        ;=> "[1,2,3]"
  (->json {"foo" 1, "bar" nil})
                                        ;=> "{\"foo\":1,\"bar\":null}"
  (def json->
    (js->clj "(x) => { return JSON.parse(x); }"))
  (json-> (->json [1 2 3]))
                                        ;=> [1 2 3]
  (json-> (->json {"foo" 1}))
                                        ;=> {"foo" 1}

  (def json-object
    (js->clj "(m) => { return m.foo + m.foo; }"))
  (json-object {"foo" 1})
                                        ;=> 2

  (def clj-lambda
    (js->clj "
    m = {foo: [1, 2, 3],
         bar: {
           baz: ['a', 'z']
         }};
    (fn) => { return fn(m); }
    "))
  (clj-lambda
   (proxy-fn #(clojure.walk/prewalk
               (fn [v] (if (and (vector? v)
                                (not (map-entry? v)))
                         (vec (reverse v))
                         v))
               %)))
                                        ;=> {"foo" [3 2 1], "bar" {"baz" ["z" "a"]}}

  (def clj->keywords
    (js->clj "(m) => console.log(JSON.stringify(m))"))
  (clj->keywords {"foo" "bar"})
  (clj->keywords {:foo 3})

  
  (def clj->callback
    (js->clj "(f, obj) => { console.log(obj); return f(obj); }"))
  (clj->callback (proxy-fn #(inc (get % "foo"))) {"foo" 55})
  

  ;; FIXME: do not work
  (def js-reduce
    (let [js-reducer (js->clj "(f, coll) => { return coll.reduce(f); }")
          js-reducer-init (js->clj "(f, coll, init) => { return coll.reduce(f, init); }")]
      (fn
        ([f coll] (js-reducer f coll))
        ([f init coll] (js-reducer-init f coll init)))))

  (js-reduce + (range 10))
                                        ;=> 45
  (js-reduce + -5.5 (range 10))
                                        ;=> 39.5
  (js-reduce (fn [acc elem]
               (assoc acc (keyword (str elem)) (doubler elem)))
             {}
             (range 5))
                                        ;=> {:0 0, :1 2, :2 4, :3 6, :4 8}

  (def log-coll
    (js->clj "(coll) => { for (i in coll) console.log(coll[i]); }"))

  (log-coll [1 2 3])
  
  (log-coll (repeatedly 3 #(do (prn 'sleeping)
                               (Thread/sleep 100)
                               (rand))))

  (log-coll (range 10))

  (close-context context)
  
  )







(comment

  (def context (create-context))

  (def js->clj (comp value->clj
                     (partial eval-js context)))

  (put-member context
              "myAsync"
              (async-fn (fn [resolve reject]
                          (println "happy")
                          (resolve [1 2 3 4 {:foo "bar"}]))))
  
  (def async-js-fn (eval-js context
                            "(async function () {
              let x = await myAsync;
              console.log(x);
            })"))

  (def t1 (value->clj async-js-fn))
  (t1)

  

  
  (put-member context
              "javaThen"
              (proxy-fn (fn [& args]
                          (println "in the proxy" args))))
  
  (def js-promise (eval-js
                   context
                   "Promise.resolve(42).then(javaThen);"))
  
  
  (close-context context))


(comment

  (def context (create-context))

  (def js->clj (comp value->clj
                     (partial eval-js context)))


  (put-member context
              "myPromise2"
              (proxy-fn (fn [resolve reject]
                          (println "chamou")
                          (Thread/sleep 500)
                          (resolve 42))))
  
  
  (def js-promise (eval-js
                   context
                   "new Promise(myPromise2).then(x => {
console.log (\"aqui\");
console.log(x);});"))


  (close-context context)


  )

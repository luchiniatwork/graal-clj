(ns graal-clj.core-test
  (:require [graal-clj.core :as core]
            [clojure.test :refer :all])
  (:import (java.io ByteArrayOutputStream)
           (org.graalvm.polyglot Context)))

(def ^:dynamic *eval-parse* (constantly true))

(def ^:dynamic *eval-parse-cap-stdout* (constantly true))

(def ^:dynamic *context* nil)

(def ^:dynamic *context-cap-stdout* nil)

(def ^:dynamic *context-stout* nil)

(defn context-fixture [f]
  (let [out-stream (ByteArrayOutputStream.)]
    (core/with-context [ctx (core/create-context "js")
                        ctx-cap-stdout (core/context-from-builder
                                        (doto (Context/newBuilder (into-array ["js"]))
                                          (.out out-stream)
                                          (core/apply-builder-defaults {"js.commonjs-require-cwd"      "test/js"})))]

      (binding [*context* ctx
                *context-cap-stdout* ctx-cap-stdout
                *context-stout* out-stream
                *eval-parse* (partial core/eval-parse ctx "js")
                *eval-parse-cap-stdout* (partial core/eval-parse ctx-cap-stdout "js")]
        (f)))))

(use-fixtures :each context-fixture)


(deftest basic-expressions
  (is [{}] (*eval-parse* "[{}]"))

  (is (= false (*eval-parse* "false")))

  (is 0.9009009009009009 (*eval-parse* "3 / 3.33"))

  (is 1.2312312312312312E32 (*eval-parse* "123123123123123123123123123123123"))

  (is 65535 (*eval-parse* "0xFFFF"))

  (is {"foo" 1
       "bar" "2"
       "baz" {"0" false}}
      (*eval-parse* "m = {foo: 1, bar: '2', baz: {0: false}};")))


(deftest stdout
  (*eval-parse* "const foo = 0xFFFF")
  (let [out (with-out-str (*eval-parse* "console.log(foo);"))]
    (is "65535" out)))


(deftest js-things
  (is "11" (*eval-parse* "1 + '1'"))

  (is [10 2 "foo"]
      (*eval-parse* "['foo', 10, 2].sort()")))


(deftest functions
  (let [doubler (*eval-parse* "(n) => {return n * 2;}")
        factorial-obj (*eval-parse* "
      var m = [];
      function factorial (n) {
        if (n == 0 || n == 1) return 1;
        if (m[n] > 0) return m[n];
        return m[n] = factorial(n - 1) * n;
      }
      x = {fn: factorial, memos: m};")]

    (is (ifn? doubler))
    (is 4 (doubler 2))

    (is (ifn? (get factorial-obj "fn")))
    (is 479001600 ((get factorial-obj "fn") 12))
    (is [nil nil 2 6 24 120 720 5040 40320 362880 3628800 39916800 479001600]
        (get factorial-obj "memos"))))


(deftest mutability
  (testing "mutating arg should not mutate it proper"
    (let [js-aset (*eval-parse* "(arr, idx, val) => { arr[idx] = val; return arr; }")
          col [1 2 3]
          out (js-aset col 1 "foo")]
      (is (= [1 2 3] col))
      (is (= [1 "foo" 3] out))))

  (testing "mutating in context binding does mutate it"
    (let [js-aset (*eval-parse* "
var myArray = [4, 5, 6];
(idx, val) => { myArray[idx] = val; return myArray; }")
          out (js-aset 1 "foo")]
      (is (= [4 "foo" 6] out))
      (js-aset 2 "bar")
      (is (= [4 "foo" "bar"] (*eval-parse* "myArray;"))))))


(deftest keywords
  (let [kfn (*eval-parse* "(m) => JSON.stringify(m);")]
    (is (= "{\"foo\":\"bar\"}"
           (kfn {"foo" "bar"})))
    (is (= "{\"foo\":\"bar\"}"
           (kfn {:foo "bar"})))
    (is (= "{\"foo/bar\":\"baz\"}"
           (kfn {:foo/bar :baz})))))

(deftest json
  (let [stringify (*eval-parse* "(x) => JSON.stringify(x);")
        objectify (*eval-parse* "(x) => JSON.parse(x);")
        doublefoo (*eval-parse* "(x) => { return x.foo * 2; }")]
    (is (= "[1,2,3]"
           (stringify [1 2 3])))
    (is (= "[1,\"foo\",\"foo/bar\",4]"
           (stringify [1 "foo" :foo/bar 4])))
    (is (= "{\"foo\":1,\"bar\":null}"
           (stringify {:foo 1 "bar" nil})))
    (is (= [1 2 3]
           (-> [1 2 3] stringify objectify)))
    (is (= [1 "foo/bar" "foo/baz" 4]
           (-> [1 :foo/bar 'foo/baz 4] stringify objectify)))
    (is (= 6 (doublefoo {:foo 3})))))

(deftest calling-clj-functions
  (let [mutate (*eval-parse* "var x = 10; (f) => { x = f(x); return x; }")]
    (is (= 11 (mutate (core/proxy-fn inc))))
    (is (= 12 (mutate (core/proxy-fn inc))))
    (is (= 15 (mutate (core/proxy-fn #(+ 3 %))))))

  (let [obj-ret (*eval-parse* "
    m = {foo: [1, 2, 3],
         bar: {
           baz: ['a', 'z']
         }};
    (fn) => { return fn(m); }
    ")]
    (is (= [1 2 3 4 5]
           (obj-ret (core/proxy-fn #(-> %
                                        (get "foo")
                                        (conj 4 5))))))
    (is (= ["a" "z" "aa" "zz"]
           (obj-ret (core/proxy-fn #(-> %
                                        (get-in ["bar" "baz"])
                                        (conj "aa" "zz")))))))

  ;; FIXME: do not work
  #_(let [js-reducer (let [js-reducer-fn (*eval-parse* "(f, coll) => { return coll.reduce(f); }")
                           js-reducer-init (*eval-parse* "(f, coll, init) => { return coll.reduce(f, init); }")]
                       (fn
                         ([f coll] (js-reducer-fn (core/proxy-fn f) coll))
                         ([f init coll] (js-reducer-init (core/proxy-fn f) coll init))))]
      (is (= 45 (js-reducer + (range 10))))))


(deftest variadic-fns
  (let [js-sort (*eval-parse* "(...vs) => { return vs.sort(); }")
        variadic-fn (*eval-parse* "(x, y, ...z) => { return [x, y, z]; }")]
    (is (= [-1 0 1 "A" {"b" nil} {"a" nil} "a" "a" ["a"] "bar" "foo"]
           (apply js-sort [{:b nil} \a 1 "a" "A" #{\a} :foo -1 0 {:a nil} "bar"])))
    (is (= ["foo/baz" "bar" [0 1 2]]
           (apply variadic-fn :foo/baz 'bar (range 3))))))


(deftest iterator
  (*eval-parse* "var myArray = new Array();")
  (let [log-coll (*eval-parse* "(coll) => { for (i in coll) myArray.push(coll[i]); }")]
    (is (= [1 2 3]
           (do (log-coll [1 2 3])
               (*eval-parse* "myArray"))))
    (is (= [1 2 3 0 1 2]
           (do (log-coll (range 3))
               (*eval-parse* "myArray"))))
    (is (= [1 2 3 0 1 2 5 5 5]
           (do (log-coll (repeatedly 3 (constantly 5)))
               (*eval-parse* "myArray"))))))


(deftest bindings-and-members
  (let [top-level (core/get-bindings *context* "js")]
    ;; no list
    (is (= []
           (core/member-keys top-level)))
    ;; a doesn't exist
    (is (= false
           (core/has-member? top-level "a")))

    ;; add a
    (*eval-parse* "const a = {\"foo\":5, \"bar\":null}; const b = 2;")

    ;; top level now has members
    (is (= true
           (core/has-members? top-level)))
    ;; member a now exists
    (is (= true
           (core/has-member? top-level "a")))
    ;; you can list a and b
    (is (= ["a" "b"]
           (core/member-keys top-level)))

    ;; a has its own submembers
    (is (= true
           (-> top-level
               (core/get-member "a")
               core/has-members?)))
    ;; a.foo is 5
    (is (= 5
           (-> top-level
               (core/get-member-in ["a" "foo"])
               core/value->clj)))

    ;; b does not have submembers
    (is (= false
           (-> top-level
               (core/get-member "b")
               core/has-members?)))

    ;; a looks like it should
    (is (= {"foo" 5 "bar" nil}
           (-> top-level
               (core/get-member "a")
               core/value->clj)))

    ;; set c
    (core/put-member top-level "c" 42)
    ;; you can list a and b and c
    (is (= ["a" "b" "c"]
           (core/member-keys top-level)))
    ;; c is 42
    (is (= 42
           (-> top-level
               (core/get-member "c")
               core/value->clj)))))


(deftest use-source-and-npm-packages
  (testing "sourcing a file"
    (let [doubler (->> "test/js/src/doubler.js"
                       core/source
                       (core/eval-parse *context*))]
      (is (= 12 (doubler 6)))))

  (testing "can see global sourced above"
    (is (= true (-> *context*
                    (core/get-bindings "js")
                    (core/has-member? "fooBar"))))
    (is (= {"foo" "bar"}
           (-> *context*
               (core/get-bindings "js")
               (core/get-member "fooBar")
               core/value->clj))))

  (testing "sourcing a bundle that also uses a library"
    (->> "test/js/dist/bundle.js"
         core/source
         (core/eval-parse *context-cap-stdout*))
    (*eval-parse-cap-stdout* "const a = 5;")
    (is (= "pending\nresolved\nHello World!\n"
           (.toString *context-stout*))))

  (testing "loading npm packages"
    (*eval-parse-cap-stdout* "
const _ = require('lodash');")
    (let [top-level (core/get-bindings *context-cap-stdout* "js")
          f (-> top-level
                (core/get-member-in ["_" "partition"])
                core/value->clj)]
      (is (= [[1 3 5 7 9] [0 2 4 6 8]]
             (f (range 10)
                (core/proxy-fn odd?)))))))


(deftest promises
  (testing "promise excuction in clj"
    (core/put-member (core/get-bindings *context-cap-stdout* "js")
                     "myPromise"
                     (core/proxy-fn (fn [resolve reject]
                                      (resolve 42))))
    (*eval-parse-cap-stdout* "new Promise(myPromise).then(x => { console.log(2 * x); })")  
    (is (= "84\n" (.toString *context-stout*))))

  (testing "promise treatment in clj"
    (let [result (atom 0)]
      (core/put-member (core/get-bindings *context-cap-stdout* "js")
                       "myThen"
                       (core/proxy-fn (fn [v] (reset! result (* 2 v)))))
      (*eval-parse-cap-stdout* "Promise.resolve(42).then(myThen);")
      (is (= 84 @result)))))


(deftest async-fn
  (testing "calling clj async function from js"
    (core/put-member (core/get-bindings *context-cap-stdout* "js")
                     "myAsync"
                     (core/async-fn (fn [resolve reject]
                                      (resolve (range 5)))))
    (let [f (*eval-parse-cap-stdout* "
(async function() {
  let x = await myAsync;
  console.log (x);
})")]
      (f))
    (is (= "0,1,2,3,4\n" (.toString *context-stout*))))

  (testing "calling js async function from clj"
    (let [f (*eval-parse-cap-stdout* "
(async function() {
  return 42;
})")]
      (is (= 42 (f))))))

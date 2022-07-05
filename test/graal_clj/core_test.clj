(ns graal-clj.core-test
  (:require [graal-clj.core :as core]
            [clojure.test :refer :all]))

(def ^:dynamic *eval-parse* (constantly true))

(def ^:dynamic *context* nil)

(defn context-fixture [f]
  (core/with-context [ctx (core/create-context "js")]
    (binding [*context* ctx
              *eval-parse* (partial core/eval-parse ctx "js")]
      (f))))

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

(ns graal-clj.buffered-out-test
  (:require [graal-clj.core :as core]
            [clojure.test :refer :all])
  (:import (org.graalvm.polyglot Context)
           (java.io ByteArrayOutputStream)))

(def ^:dynamic *eval-parse* (constantly true))

(def ^:dynamic *context* nil)

(def ^:dynamic *context-out* nil)

(defn context-fixture [f]
  (let [out-stream (ByteArrayOutputStream.)]
    (core/with-context [ctx (core/context-from-builder
                             (doto (Context/newBuilder (into-array ["js"]))
                               (.out out-stream)
                               core/apply-builder-defaults))]
      (binding [*context* ctx
                *context-out* out-stream
                *eval-parse* (partial core/eval-parse ctx "js")]
        (f)))))

(use-fixtures :each context-fixture)


(deftest promises
  (testing "promise excuction in clj"
    (core/put-member *context*
                     "js"
                     "myPromise"
                     (core/proxy-fn (fn [resolve reject]
                                      (resolve 42))))
    (*eval-parse* "new Promise(myPromise).then(x => { console.log(2 * x); })")  
    (is (= "84\n" (.toString *context-out*))))

  (testing "promise treatment in clj"
    (let [result (atom 0)]
      (core/put-member *context*
                       "js"
                       "myThen"
                       (core/proxy-fn (fn [v] (reset! result (* 2 v)))))
      (*eval-parse* "Promise.resolve(42).then(myThen);")
      (is (= 84 @result)))))


(deftest async-fn
  (testing "calling clj async function from js"
    (core/put-member *context*
                     "js"
                     "myAsync"
                     (core/async-fn (fn [resolve reject]
                                      (resolve (range 5)))))
    (let [f (*eval-parse* "
(async function() {
  let x = await myAsync;
  console.log (x);
})")]
      (f))
    (is (= "0,1,2,3,4\n" (.toString *context-out*))))

  (testing "calling js async function from clj"
    (let [f (*eval-parse* "
(async function() {
  return 42;
})")]
      (is (= 42 (f))))))

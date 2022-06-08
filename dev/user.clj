(ns user
  (:import (org.graalvm.polyglot Context
                                 Value)
           (org.graalvm.polyglot.proxy ProxyArray
                                       ProxyExecutable
                                       ProxyObject)))


#_(let [^Context ctx (Context/create (into-array ["js"]))
        ^Value log (.eval ctx "js" "console.log")]
    (.eval ctx "js" "console.log('Hello from JS')")
    (.execute log (object-array ["vish"])))

(let [js-code (slurp "js/dist/bundle.js")
      ^Context ctx (Context/create (into-array ["js"]))]
  #_(.eval ctx "js" js-code)
  (.as (.eval ctx "js" "m = { foor: 3 }") Object))


#_(System/getProperty "java.version")
#_(System/getProperty "java.vendor.version")

[polyglot]: https://www.graalvm.org/22.1/reference-manual/polyglot-programming/
[gu]: https://www.graalvm.org/22.1/reference-manual/graalvm-updater/
[core-shims]: https://github.com/parshap/node-libs-react-native#globals
[graal-vm]: https://www.graalvm.org/22.1/docs/getting-started/

[license-badge]: https://img.shields.io/badge/license-MIT-blue.svg
[license]: #license

[clojars-badge]: https://img.shields.io/clojars/v/net.clojars.luchiniatwork/graal-clj.svg
[clojars]: http://clojars.org/net.clojars.luchiniatwork/graal-clj

[ci-badge]: https://github.com/luchiniatwork/graal-clj/actions/workflows/test.yml/badge.svg
[ci]: https://github.com/luchiniatwork/graal-clj/actions/workflows/test.yml

[status-badge]: https://img.shields.io/badge/project%20status-experimental-yellow.svg

# graal-clj

[![Clojars][clojars-badge]][clojars]
[![CI Status][ci-badge]][ci]
[![License][license-badge]][license]
![Status][status-badge]

`graal-clj` is a simple wrapper and a collection of tools to make
using [Graal's Polyglot][polyglot] environment more idiomatic.

## Motivation

As a grumpy, old developer every now and again I just want to use
libraries and frameworks that feel comfortable/familiar to me. There
are very rare occasions where this might even make some business sense
if you have a super stable, super custome library in say Ruby and for
some pretty unique reason must consume it from JavaScript.

Graal's Polyglot intends to bridge that gap and make things more
portable across languages. In order to scratch my itch I had to
navigate its complexities and write tools to make interaction between
Clojure's JVM and Polyglot and this is the result.

With `graal-clj` you can consume and interact with most code,
libraries, and frameworks from languages supported by polyglot from
the comfort of Clojure.

## Getting Started

First and foremost, disclaimer! Graal is pretty solid but still
considered to be in experimental `graal-clj` is also experimental and
should not be considered production-ready.

### Dependencies

`graal-clj` depends on Graal's classes but does not carry them as
dependencies. The rational is that you can either have your Clojure
code running as on top of [Graal's VM (highly recommended)][graal-vm]
or, if you are on another JVM, you can use Graal's Polyglot as a
dependency you add to your project.

In both cases, you will need to make sure you have the runtime for the
language you intend to use. In the Graal's VM case you will use
Graal's `gu` [tool][gu] to install runtimes; in the library case you
will habe to bring the runtime as an extra dependency like you see
below on your `deps.edn` file. This would give you access to the `js`
runtime.

``` clojure
{:deps {org.graalvm.sdk/graal-sdk       {:mvn/version "22.1.0"}
        org.graalvm.truffle/truffle-api {:mvn/version "22.1.0"}
        org.graalvm.js/js               {:mvn/version "22.1.0"}}}
```

*ATT*: the above is not needed if you are using the [Graal VM][graal-vm].q

Also make sure to add `graal-clj` to your `deps.edn` file or equivalent:

``` clojure
{:deps {net.clojars.luchiniatwork/graal-clj       {:mvn/version "0.1.0"}}}
```

### Basic usage

As simple as it gets:

``` clojure
(require '[graal-clj.core :as graal])

(graal/with-context [ctx (graal/create-context "js")]
  (let [f = (graal/eval-parse ctx "js" "(x) => x * 2;")]
    (f 5))) ;; => 10
```

More details in the sections below.

## Contexts

A context can be initialized with several languages at the same time:

``` clojure
(let [ctx (graal/create-context ["js" "R"])])
```

The function will error out if the language runtime does not exist in
your system.

A context needs to be closed to free up resources at some point:

``` clojure
(let [ctx (graal/create-context ["js" "R"])]
  (graal/close-context ctx)
```

The `with-context` macro does the clean up for you for convenience.

`create-context` also accepts a third parameter which is a map of
options sent to the context builder. You can check the defaults by
inspecting `default-options`.

The default context created enables a series of experimental features
and is very Javascript-focused. If you want a more control over your
context you can use the `context-from-builder` function. It receives a
`org.graalvm.polyglot.Context.Builder` instance that you need to setup
by hand.

## Evaluating and Parsing Results

Considering you have a context at `ctx`, evaluating is as easy as:

``` clojure
(graal/eval ctx "js" "console.log('Hello World from JS');")
```

The language must be specified because your context may have several
languages at the same time. You are basically letting it know which to use.

`eval` has a one-arg option that receives a source file:

``` clojure
(graal/eval ctx (graal/source "index.js"))
```

`source` will try to detect the file's language. If you need to
overwrite or side-step the detection process just `(graal/source
"index.mjs" "js")`

`eval` returns a `org.graalvm.polyglot.Value` instance which is one of
Graal's most foundational units of work and you might need it for
advanced operations. `graal-clj` carries a function `value->clj` that
will convert the returned value into a Clojure data structure:

``` clojure
(graal/value->clj (graal/eval ctx "js" "(x) => x * 2;")) ;;=> [a callable clojure fn]
```

This is such a common pattern that `graal-clj` carries a `eval-parse`
function that does exactly that:

``` clojure
(graal/eval-parse ctx "js" "(x) => x * 2;") ;;=> [a callable clojure fn]
```

`value->clj` is an expensive and eager recursive function. You should
limit yourself to using it just when you really need it. The function
is limited to a depth of 10 nested members by default (in practice, it
will throw an exception before triggering a stack overflow
exception). You can bump this limit up to whatever you need by
re-binding `*max-parsing-depth*`.

Another useful pattern is to use `partial` if you'll be calling the
runtime a few times in your code. Full example:

``` clojure
(graal/with-context [ctx (graal/create-context "js")]
  (let [ev (partial graal/eval-parse ctx "js")
        f1 = (ev "(x) => x * 2;")
        f2 = (ev "(x, y) => x + y;")]
    [(f1 5)
     (f2 2 4)])) ;; => [10 6]
```

## Bindings and Members

To capture thre top-level bindings of a language runtime (i.e. `js`)
within a context (i.t. `ctx`) use `(graal/get-bindings ctx
"js")`. This will return a top-level value object that may contain
members that you can get and or put:

``` clojure
(let [top-level (graal/get-bindings ctx "js")]
  (graal/has-member? top-level "a")       ;; => false
  (graal/put-member top-level "a" 5)
  (graal/has-member? top-level "a")       ;; => true
  (graal/get-member top-level "a")        ;; => 5
  (graal/eval ctx "js" "console.log(a);") ;; => 5
  )
```

Which is equivalent to:

``` clojure
(let [top-level (graal/get-bindings ctx "js")]
  (graal/has-member? top-level "a")       ;; => false
  (graal/eval ctx "js" "const a = 5")
  (graal/has-member? top-level "a")       ;; => true
  (graal/get-member top-level "a")        ;; => 5
  (graal/eval ctx "js" "console.log(a);") ;; => 5
  )
```

Deep-nested members can be retrieved with `get-members-in` by sending
a vector as the identifier.

## Functions and Callbacks

You can pass Clojure functions to the runtimes either by passing them
as parameters (callback style) or by injecting them using `put-member`
(see section above). The only requirement is that you need to wrap it
with the `proxy-fn` function like this contrived example:

``` clojure
(let [mutate-x (graal/eval-parse ctx "js" "var x = 10; (f) => { x = f(x); return x; }")]
  (mutate-x (core/proxy-fn inc))       ;; => 11
  (mutate-x (core/proxy-fn inc))       ;; => 12
  (mutate-x (core/proxy-fn #(+ 3 %)))  ;; => 15
  )
```

## Promises and Async Functions

You can write promises in Clojure and inject them with `put-member`
and `proxy-fn`:

``` clojure
(graal/put-member (graal/get-bindings ctx "js")
                  "myPromise"
                  (graal/proxy-fn (fn [resolve reject]
                                    (resolve 42))))
(graal/eval "new Promise(myPromise).then(x => { console.log(2 * x); })") ;; => 84
```

Similarly, you can plug your clojure functions in promise resolutions
like this:

``` clojure
(graal/put-member (graal/get-bindings ctx "js")
                  "myThen"
                  (graal/proxy-fn (fn [v] (* 2 v))))
(graal/eval "Promise.resolve(42).then(myThen);") ;; => 84
```

JavaScript's `async/await` is fully supported. Calling an async
function is the same as calling any other function (and can be
interpreted as a promise - above).

If you want to call a Clojure function as a JavaScript async function
with `await`, you'll need to wrap the Clojure function with the
`async-fn` function. Say you have the following JavaScript function
and that `myAsync` is a Clojure function you will inject via
`put-member` (see respective section):

``` javascript
async function() {
  let x = await myAsync;
  console.log (x);
}
```

Then you wrap the clojure function up when putting it like this:

``` clojure
(graal/async-fn (fn [resolve reject]
  (resolve 42)))
```

## Using bundles and Node packages

You can source a bundle created by a bundler such as Webpack as you
would any other. Consider a `webpack.config.js` like this:

``` javascript
const path = require('path');

module.exports = {
  entry: './src/index.js',
  mode: 'production',
  output: {
    path: path.resolve(__dirname, 'dist'),
    filename: 'bundle.js',
  }
};
```

You would be able to create a `dist/bundle.js` with `$ npx webpack`
and could easily:

``` clojure
(graal/eval ctx "dist/bundle.js")
``` 

By default, `graal-clj` initializes the context with experimental
support ot CommonJS modules. In practice that means you can install
Node dependencies in the working folder with `$ npm install`. The
default assumes that `node_modules/` is on the current working folder
but can also be set with the option `js.commonjs-require-cwd`:

``` clojure
(graal/create-context "js" {"js.commonjs-require-cwd" "path/to/where/nodepackages/are"})
```

In order to load a dependency, eval `require`:

``` clojure
(graal/eval ctx "js" "const _ = require('lodash');")
```

You can get into the functionality you need by querying and wrapping
members. For instance, assuming lodash was bound to `_` (above,) we
could use its `partition` function like so:

``` clojure
(let [top-level (graal/get-bindings ctx "js")
      partition (->> ["_" "partition"]
                     (graal/get-member-in top-level)
                     graal/value->clj)]
  (partition (range 10)
             (graal/proxy-fn odd?))) ;; => [[1 3 5 7 9] [0 2 4 6 8]]
```

The interesting bit about the `parition` function is that it takes a
`pred` function to decide how to partition and we are sending
Clojure's `odd?` function to do its thing (wrapped by `proxy-fn`).

## Dealing with Node core modules

Graal's JavaScript runtime does not carry Node JS's core modules (such
as `process`, `http`, `fs`, etc.) This might make some packages
challenging to use if you don't shim those modules in.

Luckly, the JavaScript community is constantly hacking shims and mocks
for these modules. A good list can be found [here][core-shims].

Do refer to `graal-clj`'s unit tests for examples.

## Running Tests

Tests can be run with `$ clojure -X:test` for GraalVM or with `$
clojure -X:test-with-dep` for basic Java VM.

Tests depend on a few JS packages being installed and a webpack bundle
being generated. In short, you'll need to:

``` bash
cd test/js
npm install
npx webpack

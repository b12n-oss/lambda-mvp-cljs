(ns net.b12n.lambda-mvp.handler
  "Demo handler: greet, echo the raw event, and report which
  invocation this warm sandbox is on. Same field shape as the Jolt/jank
  siblings' handlers (message/runtime/request_id/warm_invocation/event)
  for a genuinely comparable response across all three -- though the
  INPUT shape differs structurally: AWS's managed nodejs runtime
  already parses the event into a JS object before calling this
  handler, unlike the Jolt/jank siblings' custom Runtime API loop,
  which hands them the raw, unparsed JSON string. So there's no
  raw-string splicing here -- `event` arrives already-parsed, gets
  `js->clj`'d, and rides back out through the ordinary return value,
  which Lambda's managed runtime JSON-serializes automatically.")

(def warm-invocation-count (atom 0))

(defn handler
  "event: the already-parsed Lambda event (a JS object). context: the
  Lambda context object -- context.awsRequestId is this invocation's
  request id, the managed-runtime equivalent of the Jolt/jank siblings'
  ctx :request-id.

  IMPORTANT: `(unchecked-get context \"awsRequestId\")`, NOT
  `(.-awsRequestId context)`. Verified directly during spec
  preparation: the dot-access form compiles cleanly with only a
  compile-time :infer-warning (easy to miss), but at runtime Google
  Closure's advanced-mode property renaming mangles the unrecognized
  property name on an object the compiler can't statically type --
  the lookup silently returns undefined, `request_id` comes back as
  an empty string, and NOTHING errors or warns at runtime. This is the
  same class of trap `js->clj` on the event object already guards
  against (per shadow-cljs-lambda-poc's own documented gotcha), just
  hitting the context object instead. `unchecked-get` performs a
  string-keyed lookup that Closure can't rename, since the key is a
  runtime string literal, not a statically-analyzed property access.

  Returns a plain JS object; Lambda's managed nodejs runtime
  serializes it to JSON automatically (no js/Promise needed -- this
  handler does no I/O)."
  [event context]
  (let [n (swap! warm-invocation-count inc)]
    (clj->js {:message "hello from cljs on lambda"
              :runtime "ClojureScript (Node.js)"
              :request_id (or (unchecked-get context "awsRequestId") "")
              :warm_invocation n
              :event (js->clj event)})))

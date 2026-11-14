# The managed runtime, and why there's no loop to write

## AWS already runs the poll/execute/respond loop for you

The Jolt sibling implements the AWS Lambda Runtime API contract directly:
`provided.al2023` hands it a bare Amazon Linux box, and its own `main.clj`
loop calls `GET /invocation/next`, runs the handler, then
`POST /invocation/{id}/response`. The jank sibling does the same thing
inside a container image, with a hand-rolled HTTP client because jank has
no HTTP library anywhere in its ecosystem yet. Both loops exist because a
custom runtime is a bare process that AWS starts and then leaves alone
until it asks for the next event.

This project targets AWS's own **managed `nodejs24.x` runtime** instead,
and a managed runtime already is that loop. AWS ships and maintains the
poll/execute/respond cycle, and it calls only one thing in your deployed
code: the function you named as the handler. There's no
`http.jank`-equivalent module here, no retry logic around the Runtime
API, and no process supervision to write.

So this repo's source tree is small: one namespace, one function.
Everything else (`script/aws_lifecycle.clj`, `script/bench_run.clj`,
`script/demo.clj`) is deployment and measurement tooling around that
function, outside the request-handling path.

## `:node-library` and `:exports`: compiling straight to `module.exports.handler`

`shadow-cljs.edn` names the build's target and its export:

```edn
{:builds {:lambda {:target :node-library
                   :output-to "out/index.js"
                   :exports {:handler net.b12n.lambda-mvp.handler/handler}}}}
```

`:target :node-library` tells shadow-cljs to compile for a Node module
rather than a browser bundle or a standalone script, and `:exports` says
which CLJS var becomes which property of `module.exports`. Here,
`net.b12n.lambda-mvp.handler/handler` becomes `module.exports.handler` in
the compiled `out/index.js`, and that's the entire wiring between this
project's ClojureScript and AWS's runtime. `bb build` runs
`shadow-cljs release lambda` with `--config-merge` pointed straight at
`dist/index.js`, copies `package.json` into `dist/` unchanged, runs
`npm install --omit=dev` there, and zips the result. `bb deploy` points
`--handler index.handler` at that zip, which tells the managed runtime
"require `index.js`, call its `handler` export." AWS parses the incoming
event into a plain JS object before the handler sees it, calls
`handler(event, context)`, and JSON-serializes whatever the handler
returns, without any of this project's code involved.

## The `unchecked-get` trap: a dot access that looks right and lies

The handler needs the current invocation's request id, which AWS puts on
`context.awsRequestId`. The obvious ClojureScript spelling is dot access:
`(.-awsRequestId context)`. It reads cleanly, matches how you'd access
any other JS property from CLJS, and compiles. `shadow-cljs release`
prints exactly one `:infer-warning` about it, because the compiler can't
prove what type `context` is (AWS's runtime constructs it, not this
project's code), so it can't confirm that `.-awsRequestId` refers to a
real field. That warning is easy to lose in a build log full of other
output, and the build still exits `0`.

The failure shows up only at runtime, and it comes from what Google
Closure Compiler's advanced-mode optimizations do to property names.
Closure renames object properties as part of its whole-program
minification pass, on the theory that if nothing in the compiled program
provably reads a property by that exact name, it's safe to rewrite. A dot
access on an untyped object gives Closure nothing to prove that the
property is read at that name, so the lookup can end up asking the
AWS-provided `context` object for a name that no longer matches what
Closure called it internally. The lookup returns `undefined`,
`(or undefined "")` turns that into an empty string, and `request_id`
comes back blank on every invocation. There's no exception and no runtime
warning, and no test fails unless one asserts the request id's value
rather than just its presence.

The fix is `unchecked-get`: `(unchecked-get context "awsRequestId")` does
a plain string-keyed property lookup, `context["awsRequestId"]` in the
compiled output, using a runtime string literal that Closure doesn't
analyze statically and so can't rename.
`shadow-cljs-lambda-poc`'s own research already ran into the same trap on
the Lambda *event* object, which is why this handler runs the incoming
event through `js->clj` rather than dot-accessing into it field by field.
This handler hits it a second time, on the `context` object.

If you extend this handler and reach for a dot on anything AWS's runtime
handed you, as opposed to something this project's code constructed,
check whether `unchecked-get` is the safer choice.

## Credit: `lambda-cljs`

This project's whole deployment shape, `:node-library` plus `:exports`,
then `shadow-cljs release` piped into `npm install --omit=dev` piped into
`zip`, follows [thheller's `lambda-cljs`](https://github.com/thheller/lambda-cljs)
directly. That's the canonical "run ClojureScript on Lambda" reference,
maintained by shadow-cljs's own author. This repo doesn't try to improve
on its shape, only to demonstrate it side by side with the Jolt and jank
siblings.

## Where the two subtler gotchas came from

Two more `:node-library` gotchas came out of an earlier internal research
spike (`shadow-cljs-lambda-poc`, a private proof-of-concept that predates
the `lambda-mvp-*` family and isn't published), before this project's
first commit:

- **Module-scope state persists across warm invocations.** A Node Lambda
  sandbox keeps its module cache between invocations that land on the
  same warm execution environment, so a top-level `(atom ...)` in a CLJS
  namespace behaves as it would in a long-running Node process: it
  survives from one invocation to the next until AWS recycles the
  sandbox. This handler's `warm-invocation-count` atom relies on that,
  which is why `bb smoke` calls the compiled handler twice and checks the
  counter went from 1 to 2 rather than checking a single call in
  isolation.
- **shadow-cljs's Node targets don't bundle npm dependencies.** With the
  default `:js-provider :require`, an npm library required from CLJS
  becomes a runtime `require()` call in the compiled output, not
  something shadow-cljs inlines into `out/index.js`. A zip built without
  also `npm install`ing that dependency into `dist/` would deploy a
  handler that throws `Cannot find module` on its first real invocation.
  This project's handler doesn't hit that: it has zero npm runtime
  dependencies (no AWS SDK calls, no request-parsing library, nothing
  beyond what `clj->js`/`js->clj` and the Google Closure runtime
  provide), so `dist/`'s `npm install --omit=dev` step has nothing to
  install. It stays in `bb build`'s pipeline for the day a future handler
  needs it.

# Changelog

All notable changes to this project are documented here.

## Unreleased

### Added
- AWS Lambda demo handler in ClojureScript, deployed as a zip to AWS's
  managed `nodejs24.x` runtime -- no custom Runtime API implementation,
  since the managed runtime already provides one.
- `handler.cljs`: the demo handler, same
  message/runtime/request_id/warm_invocation/event field shape as the
  Jolt/jank siblings, for a genuinely comparable response across all
  three.
- `bb build`/`bb smoke`/`bb test`/`bb deploy`/`bb invoke`/`bb bench`/
  `bb demo`/`bb teardown`/`bb clean` task suite.
- Cold/warm boot-time benchmarking (`bb bench`), reusing the Jolt/jank
  siblings' `script/bench.clj` parsing/formatting logic verbatim.
- `docs/guide/managed-runtime.md` and `docs/guide/cold-warm-boot.md`:
  the architecture writeup and the real, same-account/same-region/
  same-day three-way comparison across all three `lambda-mvp-*`
  siblings.

### Not included (see README's Extension points)
- A custom Runtime API implementation in cljs/Node, for a stricter
  "same contract, three languages" comparison.
- A function URL / public HTTP endpoint.

### Known limits
- No offline Runtime-API-loop equivalent to the siblings' `bb probe`.
  `bb smoke` invokes the compiled handler directly with a canned event
  object, which is weaker: it doesn't exercise AWS's own event/context
  marshaling.
- The `unchecked-get`-vs-dot-access gotcha on `context.awsRequestId`:
  dot access compiles with only an easy-to-miss `:infer-warning` and
  silently returns an empty `request_id` at runtime under Closure's
  advanced-mode property renaming. See
  `docs/guide/managed-runtime.md` for the full writeup.
- `bb deploy`'s IAM role-propagation wait is a hardcoded 10 seconds,
  inherited verbatim from the Jolt sibling's own `aws_lifecycle.clj`.
  AWS's own eventual consistency for a freshly created role can
  occasionally exceed that window, surfacing as "The role defined for
  the function cannot be assumed by Lambda" on a fresh `bb deploy`. A
  bare re-run self-heals via the idempotent role-exists branch, and
  nothing is left orphaned.

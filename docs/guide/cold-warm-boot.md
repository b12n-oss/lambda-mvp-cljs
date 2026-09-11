# Cold vs. warm boot, all three siblings side by side

Every `lambda-mvp-*` sibling ships a `bb bench` task that changes the
deployed function's memory size (which forces a fresh execution
environment on the next invoke), measures one cold sample, then measures
several warm samples back to back, and prints a table built from
CloudWatch's own `REPORT` line. This project's own `bb bench` is
`script/bench_run.clj`, and it reuses `script/bench.clj`'s parsing and
formatting logic verbatim from the jank sibling, which reused it verbatim
from the Jolt sibling in turn. Same parser, three different deployments
underneath it.

## The comparison

Measured against the same AWS account, the same region
(`ap-southeast-2`), the same day (2026-09-12), with the default
2048/3008 MB memory tiers and 5 warm samples per tier:

| Metric | Jolt 2048 MB | Jolt 3008 MB | jank 2048 MB | jank 3008 MB | cljs 2048 MB | cljs 3008 MB |
|---|---|---|---|---|---|---|
| Cold Init Duration | 786.9 ms | 599.8 ms | 510.3 ms | 64.0 ms | 104.6 ms | 109.0 ms |
| Cold Duration | 2.4 ms | 2.0 ms | 1.7 ms | 1.5 ms | 4.1 ms | 4.4 ms |
| Warm Duration (median) | 2.0 ms | 1.9 ms | 1.4 ms | 1.3 ms | 1.7 ms | 1.7 ms |
| Max Memory Used | 317 MB | 317 MB | 26 MB | 26 MB | 81 MB | 83 MB |

Read this as a real, honest comparison, not a controlled experiment. All
six columns come from a genuine account, region, and day, but they're
three genuinely different deployment models sitting next to each other:
Jolt is a zip-based custom runtime on `provided.al2023`, jank is a
container image with its own custom runtime, and this project is a zip on
AWS's managed `nodejs24.x` runtime. A difference between any two columns
could be the language, the deployment model, or both at once, and this
table on its own can't separate those out.

## The order-effect caveat, checked against this project's own numbers

Both siblings' own guides already flag this: a memory-size change forces
a fresh execution *sandbox* on the next invoke, but it doesn't force AWS
to re-fetch the deployment artifact from scratch. Whichever tier runs
first in a `bb bench` session pays the genuinely cold artifact fetch;
whichever tier runs second can end up benefiting from caching the first
invocation already paid for. jank's own guide found this directly: its
3008 MB tier's Cold Init Duration (64.0 ms) came in far below its 2048 MB
tier's (510.3 ms), the opposite of what more memory buying a faster cold
start would predict, and traced it to ECR image-layer caching from the
2048 MB tier running first in the same session.

This project's own two numbers don't show that pattern. The 3008 MB
tier's Cold Init Duration (109.0 ms) is very slightly higher than the
2048 MB tier's (104.6 ms), not lower, so whatever caching benefit exists
between tiers here, if any, isn't large enough to flip the ordering the
way it did for jank's container image. A plausible reason for the
difference: a zip deployed to a managed runtime is a much smaller
artifact to fetch than a multi-layer container image built on a full
`ubuntu:24.04` base, so there's less caching benefit available in the
first place. That's a reasonable read, not a confirmed one. Nobody forced
a fresh function name or a fresh zip build between tiers here to isolate
artifact-fetch cost from memory-size effects on its own.

## What the numbers say once you look past the confounds

- **Cold Duration lands notably higher than Warm Duration for this
  project (4.1-4.4 ms cold vs. 1.7 ms warm), a bigger cold/warm gap than
  either Jolt (2.4 vs. 2.0/1.9 ms) or jank (1.7 vs. 1.4/1.3 ms) shows.**
  Both siblings are ahead-of-time compiled: a Chez Scheme boot image for
  Jolt, native machine code via LLVM/Clang for jank. Neither has anything
  left to compile once the process is running. Node's V8 engine, by
  contrast, JIT-compiles as it goes, and a handler's very first real call
  is also the first time V8 has ever executed that specific code path,
  tiering it up from an interpreted or lightly-optimized form. A few
  extra milliseconds on that first invocation, on top of the sandbox's
  own Init Duration, is consistent with that first-call JIT cost. This is
  a plausible read of the shape of the numbers, not something isolated by
  a separate microbenchmark here.
- **Max Memory Used (81/83 MB) sits between jank's 26 MB and Jolt's
  317 MB, and it tells its own story rather than blending either
  sibling's.** Jolt's number reflects Chez Scheme's own heap floor, a
  fixed cost that shows up regardless of how small the actual program is.
  jank's reflects a native binary with no managed runtime underneath it
  at all. This project's number sits in between because it's a different
  kind of thing: V8's own baseline heap plus Node's module-loading
  machinery plus the shadow-cljs-compiled bundle, running on a managed
  high-level VM rather than either a from-scratch native binary or a
  general-purpose Scheme runtime. 81-83 MB is a real, structural cost of
  running on Node at all, not a defect in this handler, which does
  nothing heavier than string-building and one atom `swap!`.

## Reproducing a run

```sh
AWS_PROFILE=... AWS_REGION=... bb build
AWS_PROFILE=... AWS_REGION=... bb deploy
AWS_PROFILE=... AWS_REGION=... bb bench
AWS_PROFILE=... AWS_REGION=... bb teardown   # when you're done
```

`BENCH_MEMORY_TIERS` (default `2048,3008`) and `BENCH_WARM_SAMPLES`
(default `5`) are overridable, the same convention both siblings use.
Numbers from your own account and region will differ from the table
above. Treat that table as illustrative, not a live guarantee, same as
both siblings' own published numbers.

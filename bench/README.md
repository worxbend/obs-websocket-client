# Benchmarks: measured evidence for the UTF-8 counting and JsonValue decode changes

JMH microbenchmarks comparing the current implementations against verbatim private copies of the
legacy code they replaced (recovered from commit `7185fb1`). Development-only module: not
published, not in `coverage.measured`, not in any CI compile list.

## How to run

```bash
./mill bench.listJmhBenchmarks          # list detected benchmarks
./mill bench.runJmh                     # all benchmarks, annotation defaults (3 forks × 3 warmup × 5 measurement × 1 s)
./mill bench.runJmh Utf8Bench           # only one class (regex)
./mill bench.runJmh -prof gc -rf json -rff out/jmh-results.json
```

`bench` is a `ScalaModule with JmhModule` (mill-contrib-jmh 1.1.10, JMH 1.37). The mixin order
matters: `JmhModule` alone extends only `JavaModule`, which silently filters `.scala` files out of
`sources` — see the comment in `build.mill`.

## Environment

- Machine: AMD Ryzen 7 5700X (8 cores / 16 threads), 60 GB RAM, Linux 7.0.0-34-generic x86_64
- JVM: OpenJDK Temurin 25.0.3+9 (the `./mill` wrapper JVM), JMH 1.37
- Run settings: `-f 3 -wi 3 -i 5 -r 1s -w 1s` (from `@Fork`/`@Warmup`/`@Measurement` annotations),
  throughput mode, `-prof gc` for allocation rates
- Load note: other build processes were active on the machine during the run (load average ~5).
  In the UTF-8 comparisons (§1), 99.9% error bars are 0.4–3.5% of the score and never overlap
  between NEW and LEGACY, so the load does not change those conclusions. The JsonValue comparisons
  (§2) are noisier at medium size — the pre-fix regression was nonetheless reproduced by a second
  isolated run, and the post-fix medium-size runs have overlapping confidence intervals by design
  (that is what "parity" looks like); both are called out where they appear.

## 1. UTF-8 byte counting (`Utf8Bench`)

NEW: `core.util.Utf8.encodedLength` (zero-allocation arithmetic scan).
LEGACY-A: `String.getBytes(UTF_8).length` (used for stats counting).
LEGACY-B: per-call `CharsetEncoder` + 4 KiB scratch full-encode (used for byte-limit checks in the
old `SttpTransport`; verbatim copy in the benchmark).

Payloads: `shortAscii` 53 B · `obsMessage` 1 232 B · `largeAscii` 65 694 B · `cjkEmoji` 1 293
chars / 4 045 B (CJK + emoji surrogate pairs).

Throughput, ops/s (higher is better), ± 99.9% error:

| payload | NEW arithmetic | LEGACY-A getBytes | LEGACY-B encoder+scratch | NEW vs B |
|---|---:|---:|---:|---:|
| shortAscii | 104 888 088 ± 464 968 | 174 140 830 ± 6 005 944 | 2 645 510 ± 31 114 | **39.7×** |
| obsMessage | 6 191 100 ± 54 502 | 9 433 380 ± 231 535 | 593 425 ± 5 785 | **10.4×** |
| largeAscii | 122 890 ± 1 571 | 169 389 ± 3 513 | 11 394 ± 93 | **10.8×** |
| cjkEmoji | 984 256 ± 23 971 | 404 505 ± 2 285 | 140 972 ± 2 041 | **7.0×** |

Allocation, B/op (`gc.alloc.rate.norm`):

| payload | NEW arithmetic | LEGACY-A getBytes | LEGACY-B encoder+scratch |
|---|---:|---:|---:|
| shortAscii | ≈ 0 | 72 | 4 320 |
| obsMessage | ≈ 0 | 1 248 | 4 320 |
| largeAscii | ≈ 0 | 65 712 | 4 321 |
| cjkEmoji | ≈ 0 | 8 536 | 4 344 |

Reading:

- Against the implementation it actually replaced in `SttpTransport` (LEGACY-B, per-call encoder +
  scratch allocation), the arithmetic scan is **7–40× faster** and reduces allocation from a
  constant ~4.3 KB/op to zero. On the OBS message path (one count per inbound fragment and per
  outbound send) this is an unambiguous win.
- Against plain `getBytes().length` (LEGACY-A) the tradeoff is real: JDK 25 encodes ASCII-compact
  strings via intrinsics, so `getBytes` is **1.4–1.7× faster in raw throughput on ASCII** — at the
  price of materializing the full encoded array per call (up to 64 KiB/op here, i.e. sustained
  ~11 GB/s at largeAscii rates: 169 389 ops/s × 65 712 B/op ≈ 11.1 GB/s, consistent with JMH's
  directly measured `gc.alloc.rate` of 10.6 GB/s). On multi-byte text the arithmetic scan wins on
  throughput too: **2.4×** on `cjkEmoji`, where `getBytes` also allocates twice the encoded length.
- Correctness was sanity-checked per fork: all three implementations return identical counts on
  every payload (asserted in `@Setup`, including surrogate-pair handling).

## 2. JsonValue decode+encode round-trip (`JsonValueBench`)

NEW: current `protocol.JsonValue` codec (`Map.newBuilder`/`Vector.newBuilder` accumulation).
LEGACY: verbatim copy of the previous codec (per-key `Map.updated`, per-element `Vector :+`,
duplicate detection via `contains` on the accumulating map). Encoding is identical in both, so the
delta isolates decode. Documents: `small` 686 B, `medium` 3 213 B, `large` 115 289 B, all nested
OBS-shaped JSON.

Two states of the new codec were measured; see the dated subsections.

### 2026-10-05 (current): after the duplicate-detection fix — parity restored

The 2026-10-04 regression below was fixed by replacing the immutable per-key `Set` rebuild with a
`mutable.HashSet` whose `add()` Boolean reports duplicates. Two full runs of this benchmark with
the settings above (`out/jmh-results-jsonvalue-fix.json`, `-fix-rerun.json`), ops/s:

| document | NEW run 1 | LEGACY run 1 | ratio | NEW run 2 | LEGACY run 2 | ratio |
|---|---:|---:|---:|---:|---:|---:|
| small | 186 403 ± 1 422 | 185 854 ± 2 676 | 1.00× | 187 606 ± 1 291 | 180 362 ± 6 214 | 1.04× |
| medium | 35 736 ± 516 | 36 719 ± 2 187 | 0.97× | 35 973 ± 1 049 | 36 362 ± 670 | 0.99× |
| large | 946.3 ± 25.4 | 935.4 ± 34.7 | 1.01× | 953.8 ± 22.0 | 937.2 ± 37.3 | 1.02× |

Allocation, B/op (`gc.alloc.rate.norm`), run 1 / run 2:

| document | NEW | LEGACY | delta |
|---|---:|---:|---:|
| small | 17 352 / 17 352 | 16 344 / 16 317 | +6.2% / +6.3% |
| medium | 87 251 / 86 366 | 81 272 / 81 262 | +7.4% / +6.3% |
| large | 3 247 474 / 3 243 250 | 3 137 141 / 3 128 810 | +3.5% / +3.7% |

**Current verdict: throughput parity-or-better versus legacy across all document sizes** (run-1
medium at 0.97× has overlapping confidence intervals — noise; run 2 medium is 0.99×). A residual
~3.5–7.4% allocation overhead from the `HashSet` backing table remains and is accepted: duplicate
detection runs before each value is decoded, so a duplicate key is rejected without paying for the
rest of the object, and the existing error precedence is preserved.

### 2026-10-04 (historical): original builder rewrite measured as a regression

Kept for the record — this finding caught a real regression and triggered the fix above; that is
the measurement process working as intended. The first builder-based decoder additionally tracked
duplicates with an immutable per-key `Set` (`keys = keys + key`):

| document | NEW (builder + immutable key Set) | LEGACY (updated / :+) | NEW vs LEGACY |
|---|---:|---:|---:|
| small | 142 402 ± 2 838 | 183 595 ± 4 213 | **0.78× (slower)** |
| medium | 29 501 ± 762 | 35 751 ± 1 240 | **0.83× (slower)** |
| large | 758.6 ± 11.9 | 938.4 ± 22.3 | **0.81× (slower)** |

Allocation, B/op:

| document | NEW | LEGACY |
|---|---:|---:|
| small | 19 763 | 16 344 |
| medium | 92 179 | 81 267 |
| large | 3 603 844 | 3 129 138 |

Reading (as written on 2026-10-04):

- **As measured, the decode was ~17–22% slower and allocated ~13–21% more than the legacy one on
  every document size.** Error bars did not overlap, and a second isolated run reproduced the same
  direction and magnitude (current ≈ 0.79–0.85× legacy), ruling out machine-load noise.
- The cause was the per-key immutable `Set` rebuild for duplicate detection on top of the builder,
  while the legacy code checked `contains` on the map it was already building. The suggested
  follow-up — a `mutable.Set` — is exactly what the 2026-10-05 fix implemented, restoring parity.

## Limitations

- Microbenchmarks with synthetic payloads on one machine/JVM; absolute numbers do not transfer.
  OBS message rates (tens of messages/s) mean even the legacy paths were never a visible
  bottleneck in production terms — these measurements quantify the change, not a user-facing
  problem.
- The JsonValue benchmark includes jsoniter parsing and encoding work common to both variants;
  the relative delta is the signal, not the absolute ops/s.
- `legacyGetBytes` benefits from JDK 25 compact-string intrinsics; the gap may differ on other
  JDKs.
- Not measured here: the "one actor ask per inbound frame instead of two" change (requires a
  running session; out of microbenchmark scope).

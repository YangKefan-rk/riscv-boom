# BOOM V4 Difftest observations

`BoomCoreParams(enableDifftest = true)` adds private observation ports to
`BoomTileModuleImp`. The default is false. These bundles are BOOM-local Scala
records, not the common transport ABI. The embedding simulator converts them
through the BOOM host adapter in Difftest-S.

The harness drives `difftest_control.epoch` and `cycle`. Epoch identifies a
reset/session; UID and physical-slot generations remain attached to the
original request through the ROB, LDQ/STQ, cache and MSHR queues. All tiles in a
supported profile must share the source time domain. The probes do not assign
cross-hart ranks or close producer watermarks.

## Ports and lifecycle

| Port | Meaning |
| --- | --- |
| `difftest_core.allocate` | A real dispatch handshake, including ordinary ALU instructions and instructions writing x0. |
| `difftest_core.retire` | Physical ROB retirement; `architectural` distinguishes executed instructions from predicated-off SFB uops. |
| `difftest_core.squash` | Each valid ROB entry actually killed by branch recovery or rollback. |
| `difftest_core.trap` | The ROB's architectural exception/interrupt notification, excluding mini replay exceptions. |
| `difftest_lsu.allocate` | Same-dispatch LDQ/STQ allocation with its independent physical-slot generation. |
| `difftest_lsu.begin` | A load issue/translation opportunity, including faulting or missed attempts and attempts which can finish solely by STQ forwarding. |
| `difftest_lsu.cancel` / `nack` | Cancellation of that execution attempt; a ROB squash is a separate lifecycle action. |
| `difftest_lsu.complete` | The actually selected cache or STQ result, with its original source metadata. |
| `difftest_lsu.retire_load` / `retire_load_data` | The final attempt and independent LDQ retirement value, joined with the core retirement identity. |
| `difftest_lsu.local_store` | A real architectural store commit, before global publication. |
| `difftest_lsu.store_ack` / `store_release` | Irrevocable cache acceptance and physical STQ release, respectively; neither is publication. |
| `difftest_cache.store_applied` | The exclusive cache data-write handshake with the instruction's byte mask. |

Join core and LSU allocation records by epoch/UID/ROB generation before claiming
physical queue slots in the host adapter. Core ROB retirement retains the
allocation identity; load retirement must obtain the final attempt from
`retire_load`, not assume attempt zero. Queue indices on memory records are
physical indices without the circular pointer's wrap bit.

SFB physical-only retirements and ROB squashes require whole-instruction
cancellation support from the public consumer. They must never be translated
into fabricated architectural retirements or mere load-attempt cancellation.

## Source sampling

- SRAM hit: an explicitly qualified duplicated-array simulation profile uses
  the S1 outer data-register capture edge. The S0 edge registers the read
  address; S2 performs way selection and response delivery. The capture-cycle
  register shares the data register's S1 edge, and its valid follows the actual
  data-array arbiter read, not a request that may have lost arbitration.
  Preserve the selected raw word before LoadGen extension.
- Store-pipeline bypass: use the actual S2 bypass read cycle. Only the current
  bypass store's written bytes have an observed writer; older bytes in its
  merged word remain unknown rather than inheriting that store's identity.
- STQ forwarding: sample only when forwarding wins the functional response
  arbitration. If another response wins after the cache candidate was killed,
  cancel the abandoned forwarding attempt.
- MSHR line buffer: retain the first valid response's sample under backpressure
  and carry it unchanged through the response queue. Its time is the per-load
  buffer read, not the line's earlier GrantData arrival.
- Uncached response: retain its D-channel data-capture time and source kind.
  Capturing this fact does not enable MMIO checking in the scalar consumer.

`BoomCoreParams(difftestSramCaptureBoundary = true)` explicitly selects the
SRAM observation profile proven for the current generated duplicated array:
registered read address, asynchronous macro output, then the existing outer
capture register. Enable it only when the embedding simulation uses that
validated macro and a common ungated source clock. With read launch at T, a
same-row write at T is visible to the captured response; a write at T+1 is
excluded by the pre-write capture at T+1. The source is therefore T+1, even
though the response is delivered at T+2. No host timestamp arithmetic is
required, and this field does not itself assign a cross-producer phase.

The profile is disabled by default. An unqualified SRAM sample keeps
`source_valid = false`; banked and single-ported arrays also remain unqualified
regardless of the flag. Different SRAM read-under-write semantics need their
own capture-boundary proof before enabling this profile. This observation
restriction does not disable instructions or change functional cache data.
Store-pipeline bypass still uses its actual selection cycle, and MSHR line-buffer
responses retain their original sampling cycle under backpressure.

Cache miss store ACKs can precede the actual data write and STQ reuse. The uop
identity therefore travels in each RPQ entry and its replay; a later cache write
must never look up its writer in the current STQ slot. Cache-applied records
preserve the instruction byte mask even though the SRAM writes a merged word.
The shared rank resolver still needs qualified coherence/clock ordering; arrival
order, hart ID and arbitrary same-cycle phase values are not that proof.

## Completion and supported scope

`difftest_cache.idle` includes S0/S1/S2/S3 requests, MSHRs, response queues, the
prober and writeback unit. `difftest_lsu.queues_empty` is a separate queue-drain
fact. Neither signal substitutes for lossless event-delivery acknowledgement or
producer watermarks. Pending load begins conservatively bound the oldest
undelivered sample; MSHR stalls must not be hidden by a fixed cycle offset.

The initial host consumer covers scalar integer accesses within one word, raw
instruction dependency propagation and independent REF store audits. Atomic,
LR/SC, floating/vector memory, split accesses, uncached IO and trap/whole-hart
completion need the corresponding public consumer capabilities. The RTL keeps
these identities and observations; a transport must report unsupported or
incomplete coverage rather than dropping them to produce a pass.

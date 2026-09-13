# Recent openScale Scale-Support PR Review Patterns

Reviewed 2026-09-14. The sample is the five most recently merged PRs that added
support for a new scale handler, rather than every Bluetooth-related PR.

| PR | Scale | Added test scope | Notable review outcome |
| --- | --- | --- | --- |
| [#1521](https://github.com/oliexdev/openScale/pull/1521) | FITINDEX FT-26R-W | 11 tests, including captured advertisements and a real `ScanRecord` path | Parser moved into the handler; catalog remarks were reduced; a one-capture protocol gate was kept conservative and explicitly documented. |
| [#1512](https://github.com/oliexdev/openScale/pull/1512) | PICOOC Mini Lite | Parser, algorithm, learner, and handler tests across three test files | Shared-infrastructure plumbing was reverted; generic measurement identities were preferred; BMI was not written because openScale derives it; callback failures were changed from throws to skips. |
| [#1505](https://github.com/oliexdev/openScale/pull/1505) | A&D UC-352BLE | 144 lines covering matching and captured standard indications | The PR supplied a traffic capture and physical-device validation; no substantive maintainer change was requested. |
| [#1495](https://github.com/oliexdev/openScale/pull/1495) | AiLink broadcast body-fat scale | 19 tests across protocol and handler test files, with synthetic encrypted vectors | Rebase and provenance wording were requested; unknown fields and unsupported units were left unhandled; redundant null checking was removed. |
| [#1487](https://github.com/oliexdev/openScale/pull/1487) | Healthkeep 280 | 56 lines covering final-frame extraction | The PR supplied a protocol capture and physical-device validation; no substantive maintainer change was requested. |

## Recurring maintainer expectations

### 1. Evidence before generalisation

Decode only fields and states demonstrated by captures. A matcher or protocol
rule based on one device must not silently broaden support for an entire OEM
family. When evidence is incomplete, the conservative behavior should be
documented rather than hidden behind a speculative fallback.

### 2. Keep the handler boundary narrow

The parser belongs in the handler file when it has one consumer. A `libs/` file
is appropriate for a genuinely reusable, Android-free algorithm. Changes to
shared adapters, `ScaleUser`, or other handlers need an independent reason and
should not be pulled in only to support one scale.

### 3. Match precisely and respect registry order

Use the strongest reliable advertisement identity available and test near
collisions. `ScaleFactory` is first-match-wins, so a broad name prefix or
service-only matcher can steal a device from another handler. The catalog
fixture and factory-order test are part of the support change, not optional
documentation.

### 4. Publish measurement semantics, not guesses

Publish values the scale really provides. Do not write values that openScale
already derives, such as BMI. Vendor-specific values may be added only when
their semantics and identity are established. Invalid or unavailable fields
should remain absent instead of being stored as zero or a guessed value.

### 5. Make the callback path tolerant

Malformed, incomplete, duplicate, or sentinel frames should be ignored or
handled safely inside the Bluetooth callback. A bad packet must not abort the
measurement flow. Tests should exercise the production parser directly and
cover the final-record/fallback behavior that prevents duplicate history rows.

### 6. Keep the PR easy to merge and audit

Rebase onto the current `master` before asking for review, especially when
touching `ScaleFactory.kt` or `ScaleCatalog.kt`. Keep catalog remarks useful to
end users; put byte offsets and protocol details in KDoc or tests. Describe
working and unsupported features in the PR body and include a sanitized capture
or hardware-validation note when available.

### 7. Record provenance accurately

When protocol behavior was learned from a vendor app, describe the result as
derived from observed behavior and state that the implementation is original.
Avoid wording that could imply copied decompiler output. Do not commit official
app binaries, private captures, MAC addresses, or personal measurements.

## Effect on the ICOMON BF-L303B change

The current feature branch follows these points:

- `IcomonBodyScaleHandler` keeps its parser local; no shared Bluetooth code or
  one-off library was added.
- Matching is the exact `Body scale` name, case-insensitive. The verified
  `0xFFB0` service is not required in the advertisement because this device was
  observed without it, and the handler is registered before generic `MGBHandler`.
- The handler publishes only observed final-frame values: weight, optional heart
  rate, and impedance. `BODY_COMPOSITION` is declared as a device capability,
  but is not listed as implemented because the observed frame has no such
  fields and no independent formula is being guessed.
- Tests use independent, synthetic 20-byte hex fixtures and cover A2/A3 decode,
  checksum/fragment/length rejection, B0/B1 construction, A0/A1/A3 ACK behavior,
  stable-weight fallback, duplicate suppression, matching, and registry/catalog
  ownership.
- No personal `.pklg`, MAC address, vendor binary, or app asset is in the
  feature diff.

The remaining gap is hardware BLE execution in CI: an emulator can run the
unit tests and app smoke check but cannot reproduce the user's physical scale.
The PR description should distinguish unit/emulator validation from physical
device validation and should not claim a BF-L303B hardware run until one is
confirmed, without adding a private capture to the repository.

## References

- [How to support a new scale](https://github.com/oliexdev/openScale/wiki/How-to-support-a-new-scale)
- [Supported scales in openScale](https://github.com/oliexdev/openScale/wiki/supported-scales-in-openscale)

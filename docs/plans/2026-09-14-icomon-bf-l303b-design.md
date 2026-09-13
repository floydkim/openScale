# ICOMON BF-L303B Support Design


## Goal

Add support for the ICOMON BF-L303B body scale, observed as `Body scale` with
model string `FI2019LB-B`, to openScale's Kotlin GATT handler registry.

The handler must support the behavior verified from the device and its MyFit
Pro-compatible protocol:

- connect to the `0xFFB0` GATT service;
- subscribe to both the `0xFFB2` notification and `0xFFB3` indication;
- send the user's height, age-derived profile timestamp, and gender;
- decode live weight frames;
- decode the final weight, heart rate, and impedance frame;
- calculate the WLA07 body-composition values already used by openScale's
  Active Era driver.

History download, scale clock synchronization, unit configuration, and battery
reporting are explicitly out of scope. The device was observed not to support
those operations.

## Architecture

Add one device-specific handler and one small shared WLA07 calculator extracted
from `ActiveEraBF06Handler`:

```text
FFB2 notify  ─┐
              ├─ IcomonBodyScaleHandler ── ScaleMeasurement ── openScale
FFB3 indicate ┘             │
                            └─ IcomonWla07BodyComposition
```

The handler will use the existing `ScaleDeviceHandler` transport helpers and
will not change the shared GATT adapter. `FFB3` is subscribed through the same
`setNotifyOn` API used by existing indication-based handlers; the adapter owns
the CCCD details.

## Device matching and registry order

`IcomonBodyScaleHandler.supportFor` will require the advertised name to be
exactly `Body scale`, ignoring case. The scale may omit `0xFFB0` from its
advertisement, so requiring that service during scanning would prevent a
valid device from being selected. The handler uses the verified `0xFFB0`
service and characteristics after connecting.

It will report `ICOMON BF-L303B` and `CONNECT_GATT`. The handler will be
registered immediately before `MGBHandler`, whose service-only match otherwise
claims every unrecognised `0xFFB0` device. The existing generic ICOMON/swan/YG
claims will remain unchanged.

Declared and implemented capabilities:

- `LIVE_WEIGHT_STREAM`
- `BODY_COMPOSITION`
- `USER_SYNC`

No capability will be declared for history, time sync, unit configuration, or
battery level.

## Wire protocol

All observed frames are 20 bytes:

```text
[sequence][payload length][fragment][payload ...][checksum]
```

The checksum is the unsigned sum of bytes 3 through 18, masked with `0x1F`.
Only a complete frame with a valid checksum is accepted.

Payloads used by this handler:

| Payload | Meaning | Fields |
| --- | --- | --- |
| `A2` | live weight | state at payload byte 1; 24-bit big-endian grams at bytes 3–5 |
| `A3` | final result | 24-bit big-endian grams at bytes 2–4; heart rate at byte 5; impedance at bytes 6–7, big-endian ohms |
| `A0`, `A1`, `A3` | incoming frames requiring acknowledgement | send `B0` with the incoming sequence |
| `B1` | profile | current epoch seconds, height, and gender encoding observed from the app |

The final `A3` frame is authoritative and produces the stored measurement. A
stable live `A2` value is retained as a weight-only fallback if the scale
disconnects before sending `A3`; it is not published immediately, which avoids
creating a duplicate record when `A3` follows.

## Measurement mapping

The final frame always contributes:

- weight in kilograms;
- heart rate when non-zero;
- raw impedance in ohms.

When the user profile has a valid height and the impedance is positive, the
shared WLA07 calculator contributes the values represented by openScale's
existing measurement keys: body-fat percentage, muscle percentage, visceral-fat
index, bone mass, water percentage, lean body mass, basal metabolic rate, and
protein percentage. The raw impedance is retained so openScale can recalculate
derived values later where supported.

The calculator is extracted from the existing openScale WLA07 implementation,
not copied from the vendor application. Its existing coefficient table,
clamping, and rounding behavior remain unchanged. The PR will identify the
algorithm as reverse-engineered/observed and will not present the BIA estimates
as medical measurements.

## Validation

Tests will cover:

1. valid and invalid frame checksums and lengths;
2. A2 weight decoding and A3 weight/heart-rate/impedance decoding;
3. B0 acknowledgement and B1 profile frame construction;
4. WLA07 reference outputs for both sexes and boundary inputs;
5. strict `Body scale` matching with and without an advertised `0xFFB0` service;
6. registry ordering ahead of `MGBHandler` and catalog ownership.

Fixtures will be synthetic and contain no personal measurements, MAC addresses,
PacketLogger files, or official-app binaries. After unit tests pass, the fork
will be built and the handler will be checked with the user's BF-L303B on a
real Android device. The PR will target the upstream `master` branch.

## Non-goals

- decoding unsupported history, time, unit, or battery operations;
- changing the existing `MGBHandler` protocol;
- adding a server, login, or cloud synchronization;
- including proprietary app assets or raw personal captures.

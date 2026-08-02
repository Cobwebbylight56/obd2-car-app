# OpenOBD

[![Build APK](https://github.com/Cobwebbylight56/obd2-car-app/actions/workflows/build.yml/badge.svg)](https://github.com/Cobwebbylight56/obd2-car-app/actions/workflows/build.yml)

An Android OBD-II scanner. Plugs into any ELM327-compatible adapter and gives you live
engine data, fault codes with plain-English explanations, emissions readiness, freeze
frame, on-board test results and trip logging.

Built because the scanner app I was using stopped being supported.

---

## Getting the app onto your phone

You don't need Android Studio. Every push to this repository builds a working APK.

1. Go to the [**Actions** tab](https://github.com/Cobwebbylight56/obd2-car-app/actions/workflows/build.yml).
2. Click the most recent green **Build APK** run.
3. Scroll to **Artifacts** and download `OpenOBD-debug-apk`.
4. Unzip it, copy `app-debug.apk` to your phone, and open it.
5. Android will ask you to allow installing from that source — that's expected for an app
   that isn't from the Play Store.

For a permanent download link instead of a 90-day artifact, push a tag:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

That publishes the APK to the repository's Releases page.

### Or build it yourself

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and the Android SDK (API 35). Minimum supported phone is Android 8.0.

---

## Connecting to the car

1. Plug the adapter into the OBD-II socket. It's within about two feet of the steering
   wheel on any car sold since 2001 in Europe or 1996 in the US — usually under the dash
   on the driver's side, sometimes behind a small flap.
2. Turn the ignition to position II, so the dashboard lights come on. The adapter has no
   power otherwise. The engine doesn't need to be running for most functions.
3. Open the app and pick your adapter from the list.

**Bluetooth LE adapters** (Vgate iCar Pro, OBDLink CX, LELink, most modern ones) just
appear in the scan list — no pairing needed.

**Classic Bluetooth adapters** (the cheap blue ELM327 dongles) must be paired in Android's
own Bluetooth settings first. The PIN is almost always `1234` or `0000`. After that they
show up in the app.

**Wi-Fi adapters** host their own network. Join it in Android's Wi-Fi settings, then use
*Other ways to connect → Wi-Fi adapter*. The default `192.168.0.10:35000` is right for
most of them.

**No adapter yet?** *Demo mode* runs the whole app against a simulated ELM327 and a
simulated car, including fault codes you can read and clear. Nothing is faked at the UI
level — it's a real protocol implementation talking to a real protocol simulator.

---

## What it does

### Live data
Every parameter your car reports, decoded per SAE J1979 — around 125 PIDs including RPM,
speed, coolant and oil temperature, fuel trims, MAF, MAP, boost, lambda, all oxygen
sensors, catalyst temperatures, DPF and EGT for diesels, and the odometer where the car
exposes it.

The app asks the car which PIDs it actually supports rather than guessing, so the list you
see is exactly what your vehicle has.

Pin any parameter to the dashboard as a gauge. Selecting fewer parameters makes each one
update faster — the adapter reads them one at a time, so four selected is roughly four
times the refresh rate of sixteen.

### Fault codes
Reads all three lists the car keeps, which many cheap scanners don't:

| List | Service | What it means |
|---|---|---|
| Confirmed | 03 | The fault is committed. This is what turns the engine light on. |
| Pending | 07 | Seen once, not yet confirmed. Clears itself if it doesn't recur. |
| Permanent | 0A | Emissions codes that no scan tool can erase — only the car can clear them. |

Codes come with a description, a severity, and practical advice where there's something
useful to say. Manufacturer-specific codes are flagged as such rather than given an
invented description — the same number means different things on different marques, and a
confident wrong answer is worse than none.

Clearing codes tells you plainly what it does and doesn't do, including the part people
get caught out by: it resets every readiness monitor, so clearing codes the day before an
MOT will fail the test.

### Freeze frame
The sensor snapshot the ECU saved at the exact moment a fault was stored. Cold or hot,
idling or under load, lean or rich — usually narrows the cause far more than the code
alone.

### Emissions health
Readiness monitors, framed as "will this pass a test" rather than as raw bits, with the
drive cycle needed to complete any that haven't run.

### On-board test results (service 06)
The early-warning screen, and the reason to keep the app rather than borrow a scanner
once. Where the codes page shows what has already failed, this shows each component's
measured value against the limit it's judged by — so a catalytic converter or oxygen
sensor on the way out is visible months before it sets a code.

### Vehicle information
VIN with the standardised parts decoded and its check digit verified, ECU calibration IDs
and verification numbers, the negotiated protocol, and the full list of supported
parameters.

### Trip logging
Records everything to a CSV you can open in a spreadsheet, with distance, top speed and a
fuel economy estimate. Runs as a foreground service so the log doesn't get truncated when
the screen sleeps. Useful for chasing intermittent faults that never happen while you're
looking at the phone.

### Diagnostic report
One button on the fault codes screen captures the codes, freeze frame, readiness state
and vehicle identity into a plain-text file you can keep or send to a garage. It exists
because clearing codes destroys the only record of them — "write them down first" is the
standard advice and nobody does it. Plain text on purpose: it pastes into an email or a
message without the recipient needing this app.

### Trip review
Tap a recorded trip to plot it. Pick up to four parameters and compare their shapes over
the drive — a misfire that only appears once coolant temperature passes 90 °C is obvious
on a chart and invisible in a spreadsheet column. Each line is scaled to its own range
rather than a shared axis, because plotting a 900 °C catalyst temperature against a ±10 %
fuel trim renders the trim as a flat line; the legend states each line's range so the
scaling is never implicit.

### Offline code lookup
Search the built-in code database with no adapter and no car — for when a garage quotes
you a code over the phone. Searches by number (`P0420`, or `P03` for the whole misfire
family) or by description, since people usually remember "something about the catalytic
converter" rather than the number. A well-formed code that isn't in the database still
gets an answer from its structure: which system it belongs to, and whether it's
manufacturer-defined and therefore unknowable generically.

### Terminal
Raw AT and OBD command console for when you want to check something the app doesn't
expose, or work out why a particular adapter is misbehaving.

---

## What it doesn't do

It implements OBD-II — the emissions-related standard every car must support. It does not
do manufacturer-specific diagnostics: no ABS or airbag module codes, no key coding, no
service interval resets, no coding or adaptations. Those live outside OBD-II, use
different protocols, and differ per marque.

---

## How it's put together

```
transport/   BLE, classic Bluetooth, Wi-Fi and demo links to the adapter
elm/         ELM327 command framing, timeouts, and response parsing
obd/         PID table, DTC database, readiness, service 06, service 09
data/        Connection state, polling, trip logging, settings
ui/          Compose screens
```

The pieces worth knowing about:

- **`ObdTransport`** hides the physical link. BLE fragments responses across 20-byte
  notifications and needs a serialised write queue; RFCOMM and TCP are plain streams. The
  layers above never see the difference.
- **`BleTransport`** has no standard profile to rely on — every vendor picked their own
  GATT UUIDs. It tries a table of known ones, then falls back to "any characteristic that
  can notify plus any that can be written", which is what makes unbranded dongles work.
- **`Elm327`** enforces that the chip is strictly one-command-at-a-time, and frames
  responses on the `>` prompt rather than on newlines.
- **`ObdParser`** normalises every response shape — spaces on or off, headers on or off,
  ISO-TP multi-frame, multiple ECUs answering at once — into one hex string, then finds
  the expected service marker in it.
- **`ObdRepository`** is application-scoped and holds one mutex over the adapter, so the
  dashboard's polling loop and a one-off code read can't corrupt each other's traffic.

### Tests

```bash
./gradlew test
```

Covers the parts where a bug produces a plausible wrong number rather than a crash: the
response parser against real adapter output shapes, the PID formulas against values
computed by hand from the standard, DTC byte decoding across both protocol alignments,
readiness bit decoding, and service 06 scaling.

---

## Safety

Read the codes before you clear them — clearing deletes the only record you have. If a
code says the engine has overheated or lost oil pressure, stop and check rather than
clearing it and driving on. The app's severity ratings are a triage aid for deciding
whether something needs a garage this week or this month; they aren't a substitute for a
mechanic looking at the car.

Don't operate the app while driving.

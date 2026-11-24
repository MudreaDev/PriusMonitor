# VAG Monitor: scope and research

Independent Android application on `feature/vag-monitor`, package `ro.vagmonitor.app`.
The Prius main branch and installed Prius application are separate.

## Implemented in the first alpha

- ELM327 Bluetooth Classic and BLE transports, reusing the proven adapter transport code.
- Physical 11-bit ISO-TP CAN requests, standard OBD supported-PID discovery through page C0.
- 28 standard parameter decoders, including oil temperature (015C), fuel rail pressure,
  EGR and direct fuel rate. Only advertised parameters are polled. No gasoline MAF fuel estimate.
- Optional model-year-filtered Passat community definitions, searchable selection, maximum
  12 additional commands. Oil/DPF preset. Missing, rejected, invalid and stale values stay explicit.
- Engine stored/pending/permanent DTCs and a bounded UDS scan of six named module addresses.
- User-confirmed engine OBD or individual-module UDS DTC clearing, requiring fresh zero speed
  and zero engine RPM before sending the write. Positive acknowledgement is required.
- Read-only ECU part/software identification; shareable diagnostic report.
- Foreground connected-device service and experimental Android Auto template screen.

## Not implemented or not validated

No physical Passat/VIECAR test has been performed. A successful build is not vehicle validation.
This is not support for every sensor, every ECU, or every VAG generation. Standard OBD operation
is restricted to ISO 15765 11-bit CAN at 500 kbit/s. K-line, VW TP2.0, DoIP and SFD are absent.
Extended definitions are Passat-specific and community supplied; some units are explicitly unknown.
Do not use this catalog as a universal Audi/Skoda/SEAT/CUPRA catalog. No coding, actuator tests,
forced DPF regeneration, security-access bypass, or service/inspection writes are implemented.

Service resets need the actual instrument-cluster part/software identification and verified
adaptation channels, encoding, prerequisites and readback. The Service screen states this limitation
and offers identification. DTC clearing does not reset inspection or oil intervals.

Android Auto support is experimental connected-device telemetry using Car App Library 1.4.0.
The IOT manifest category is a development integration choice, not proof of Play category eligibility
or host approval. No navigation-category impersonation is used. Sideload visibility and physical head
unit operation remain unverified. There is no promise of Google Play approval or universal AA support.

## Primary sources reviewed on 2026-11-23

| Project/source | Findings and choice |
|---|---|
| [OBDb Volkswagen-Passat](https://github.com/OBDb/Volkswagen-Passat) | Active data source, 688 command definitions at import; use attributed, offline data, never execute repository code. |
| [DashboardX](https://github.com/stardust1420/dashboardX) | Updated 2026-10-30; useful Android Auto reference, MIT. Its README describes limited hardware testing and sideload restrictions. No code imported. |
| [AA-Dashboard](https://github.com/lucas-gaitzsch/AA-Dashboard) | Archived, last push 2020-06-16; GPL-3.0. Not adopted as the application foundation. |
| [DiVAG](https://github.com/Kaster-prg/DiVAG) | Last push 2025-02-05; ELM/VCDS tool reference, not integrated. |
| [Android supported categories](https://developer.android.com/training/cars) | No dedicated general OBD-dashboard category. Host/distribution validation is required. |
| [Host validation](https://developer.android.com/reference/androidx/car/app/CarAppService) | Release uses known host signatures; debug allows development hosts. |
| [Ross-Tech SRI](https://www.ross-tech.com/vcds/tour/sri-reset.html) | Reset depends on label/adaptation data and vehicle configuration, not a universal command. |

## Data provenance and license

`app/src/main/assets/vag/passat.json` is the unmodified `signalsets/v3/default.json` from
[OBDb/Volkswagen-Passat](https://github.com/OBDb/Volkswagen-Passat/blob/fe25013594a2a7d424706427ec4f4e3f2163d04d/signalsets/v3/default.json),
revision `fe25013594a2a7d424706427ec4f4e3f2163d04d`, retrieved 2026-11-23.
Copyright: OBDb contributors. License: [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
No endorsement by OBDb, Volkswagen or any VAG brand is implied. Application code keeps its
existing MIT license; the imported dataset retains its separate CC BY-SA 4.0 terms.

Build with JDK 17 and Android SDK 34: `cd app`, `gradlew check assembleRelease`.
Release signing uses an untracked `vag-keystore.properties`, independent from Prius signing config.

## Validation for 0.1.0-alpha.1 (2026-11-24)

- `ktlintFormat`, then `check assembleRelease assembleDebug assembleDebugAndroidTest`: passed.
- 246 debug JVM tests passed; release JVM tests also passed. Android lint: zero errors
  (warnings remain, mostly inherited code and untranslated strings; host allow-list resource
  follows the Android documentation linked above).
- Nine instrumented checks passed on Android 8/API 26: independent launcher/package, all four
  pages, supported PID discovery and oil decoding, moving-vehicle clear rejection, OBD/UDS scan,
  stationary clear acknowledgement, negative ECU response handling, visible foreground service
  and connection shutdown, removal of disconnected samples.
- Signed release APK installed/launched on API 26 and its launch screen was visually inspected.
- The API 37.1 emulator could not be started due to host RAM/free-disk constraints. No modern
  Android, real Bluetooth adapter, vehicle or Android Auto head-unit validation is claimed.

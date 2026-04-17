# Transit Presence — Smart Ride Assistant
### Android App · Central Florida · HandleIT.Online

---

## Overview

An Android application that eliminates missed buses and missed stops for Central Florida transit riders using **inference-based, multi-sensor state tracking**. No agency integration. No hardware. Just GTFS data + phone sensors + a deterministic FSM.

---

## Architecture

```
app/
├── core/
│   ├── fsm/            # Finite state machine (RideState, RideEvent, RideFsmEngine)
│   ├── fusion/         # Sensor fusion engine (BayesianFusionEngine, RouteAlignmentEngine)
│   └── model/          # Domain models (Stop, Route, Trip, VehiclePosition, etc.)
├── data/
│   ├── gtfs/           # GTFS static parser + Room DB (stops, routes, trips, shapes)
│   └── gtfsrt/         # GTFS-RT client (VehiclePositions, TripUpdates)
├── location/           # LocationModule, RideOrchestrator, GeofenceBroadcastReceiver
├── notification/       # NotificationEngine, TransitTrackingService
├── ui/
│   ├── screens/        # Compose screens (Idle, Waiting, Approaching, OnBus, Exit, Complete)
│   ├── theme/          # Material3 dark theme + transit color tokens
│   └── MainViewModel   # MVI ViewModel
├── di/                 # Hilt AppModule
└── util/               # MockDataProvider (testing without live feeds)
```

---

## FSM States

| State | Trigger |
|---|---|
| `IDLE` | App launched, no active trip |
| `WAITING_AT_STOP` | User entered stop geofence + route selected |
| `BUS_APPROACHING` | ETA < T-5min on matched route |
| `BOARDING_WINDOW` | ETA < T-90sec — escalating alert |
| `ON_BUS` | Fusion confidence > 0.85 |
| `APPROACHING_EXIT_STOP` | 2–3 stops from destination |
| `EXIT_WINDOW` | 1 stop from destination — pull cord alert |
| `TRIP_COMPLETE` | Exit confirmed or destination reached |

---

## Sensor Fusion Weights

| Signal | Weight | Notes |
|---|---|---|
| GTFS-RT Trip Match | **0.40** | Highest — direct vehicle ID correlation |
| Movement Speed | **0.25** | > walking threshold (1.39 m/s) |
| Route Shape Alignment | **0.20** | Polyline proximity score |
| Wi-Fi SSID Detection | **0.10** | Optional; gracefully disabled |
| Vehicle Motion Pattern | **0.05** | Accel/gyro classification |

Weights renormalize automatically when signals are unavailable.

---

## Quick Start

### 1. GTFS Feed Configuration
In `app/build.gradle.kts`, set your feed URLs:
```kotlin
buildConfigField("String", "GTFS_RT_VEHICLE_POSITIONS_URL", "\"https://your-agency/gtfs-rt/vehicle-positions\"")
buildConfigField("String", "GTFS_RT_TRIP_UPDATES_URL",      "\"https://your-agency/gtfs-rt/trip-updates\"")
```

For Central Florida (LYNX):
- Vehicle Positions: `https://s3.amazonaws.com/lynx-gtfs-rt/vehicle-positions.pb`
- Trip Updates: `https://s3.amazonaws.com/lynx-gtfs-rt/trip-updates.pb`

### 2. GTFS Static Data
Place your `google_transit.zip` in `app/src/main/assets/` and call:
```kotlin
gtfsStaticParser.parseZip(assets.open("google_transit.zip"))
```

### 3. Mock Mode (development)
Enable in debug build — no live feeds required:
```kotlin
UserPreferences(mockModeEnabled = true)
```
Uses `MockDataProvider` with sample LYNX stops/routes.

### 4. Google Maps API Key
Add to `local.properties` (not committed):
```
MAPS_API_KEY=your_key_here
```

---

## Key Implementation Notes

### ON_BUS Confidence Threshold
Default: **0.85**. Tunable via `UserPreferences.onBusConfidenceThreshold`. Wire to a debug settings screen during development to calibrate against real ride data before hardcoding.

### LYNX GTFS-RT
LYNX vehicle position feed refresh rate is inconsistent (~15–30s in practice). Treat 5s poll as aspirational — implement exponential backoff on feed errors.

### Wi-Fi SSID Detection
LYNX buses do not broadcast consistent passenger SSIDs. Treat `wifiSsidMatchConfidence` as a bonus signal only. The fusion weight of 0.10 is intentionally conservative.

### Geofence Radius
Default: **50m**. Increase to 60–75m for high-traffic stops (busy intersections cause GPS drift). Tunable per stop via `UserPreferences.geofenceRadiusMeters`.

---

## Running Tests

```bash
./gradlew test                  # Unit tests (FSM + Fusion engine)
./gradlew connectedAndroidTest  # Instrumented tests (requires device/emulator)
```

---

## Dependencies

| Library | Purpose |
|---|---|
| Jetpack Compose + Material3 | UI |
| Hilt | Dependency injection |
| Room | GTFS static data cache |
| Retrofit + OkHttp | GTFS-RT HTTP polling |
| Protobuf-lite | GTFS-RT feed decoding |
| Google Maps SDK / Compose | Map rendering |
| FusedLocationProvider | GPS + geofencing |
| Coroutines + Flow | Async streams |
| Timber | Logging |

---

## Future Enhancements

- [ ] BLE beacon integration at stops
- [ ] Missed bus risk scoring (predictive ML)
- [ ] Adaptive alert timing model trained on ride history
- [ ] Agency analytics dashboard
- [ ] Crowd density estimation
- [ ] SunRail integration (different GTFS feed)

---

*Built by HandleIT.Online — inference-based, advisory-only, explainable.*

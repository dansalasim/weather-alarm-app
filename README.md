# Weather Alarm PH

An Android weather app for students, teachers and workers in the Philippines:
a 12-hour day (6 AM–6 PM) and night (6 PM–6 AM) forecast built from several free
sources, with **PAGASA prevailing** when sources disagree, practical advice
(umbrella, water, heat, wind) in English and Filipino, and a full-screen alarm.

## Project layout
| Folder | What's in it |
|---|---|
| `core/` | Pure Kotlin forecast engine: day/night windows, source consolidation, PAGASA rules, recommendations, voice script, premium plans, source readers. See `core/ENGINE.md`. |
| `app/` | The Android app (Jetpack Compose). |
| `.github/workflows/build.yml` | Builds a test APK in the cloud on every push. |
| `PRIVACY.md` | Privacy policy (linked in the app and for the Play Store listing). |
| `keystore/debug.keystore` | Fixed **debug-only** key so test APKs install over each other. Never used for the Play Store. |

## Getting the test APK
1. Open the repository's **Actions** tab.
2. Tap the latest green **Build APK** run.
3. Under **Artifacts**, download `weather-alarm-apk-N`, unzip it and open `app-debug.apk` on your phone.

## Status
- [x] Forecast engine, PAGASA rules, recommendations (EN/FIL), voice script
- [x] Readers: Open-Meteo, MET Norway
- [x] v0.1 app: day/night forecast for City of Isabela
- [ ] PAGASA feed in the app
- [ ] GPS location
- [ ] Alarms (full-screen + morning mini alarm)
- [ ] Voice readout, premium (Google Play Billing)

## Data sources and terms
- **Open-Meteo:** the free API is for non-commercial use. The paid app will need their commercial plan.
- **MET Norway:** free under CC BY 4.0; attribution and an identifying User-Agent are required.
- **DOST-PAGASA:** regional forecast, warnings and iHeatMAP. Seek PAGASA's permission before commercial use.

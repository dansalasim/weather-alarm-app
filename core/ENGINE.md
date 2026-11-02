# Weather Alarm App: Core Logic (v0.1)

Pure Kotlin with no Android dependencies, so it drops straight into the Android Studio project later as the `core` module.

## Files
| File | What it does |
|---|---|
| `model/Models.kt` | Data shapes: hourly points from each source, PAGASA advisory, day/night window, final window forecast |
| `engine/Windows.kt` | DAY 6 AM–6 PM, NIGHT 6 PM–6 AM (Asia/Manila). Decides which two windows the alarm card shows |
| `engine/Consolidator.kt` | Combines sources by median, rates agreement (High/Medium/Low), applies PAGASA rules, computes heat index |
| `engine/Recommender.kt` | Advice in English and Filipino (umbrella, water, heat, wind, flood, Signal) |
| `engine/VoiceScript.kt` | The sentence TextToSpeech reads at the alarm (premium) |
| `premium/Plans.kt` | ₱99/month, ₱949/year with 7-day trial; free vs premium features |
| `test/.../CoreChecks.kt` | 24 checks covering all of the above |

## PAGASA rules
1. **Warnings always win**, setting minimum values:
   - Signal No. 1–5: rain at least 80%, and wind at least the signal's lower bound (39 / 62 / 89 / 118 / 185 km/h).
   - Rainfall warning: rain at least 70% for yellow, 85% for orange, 95% for red.
   - Thunderstorm advisory: rain at least 60%.
2. **Heat index:** PAGASA's forecast replaces the computed value.
3. **When sources disagree** (Low confidence) and there's no warning, PAGASA's text forecast decides: "rain likely" raises rain to at least 60%, and "rain unlikely" caps it at 30%.
4. **When sources agree,** the median stands.

Every change PAGASA makes is recorded in `pagasaOverrides`, so the app can show something like "Adjusted per PAGASA."

## Thresholds to tune after the field test
- `Consolidator.Config`: agreement spreads (20 / 40 points for rain, 2 / 4 °C for temperature)
- `Recommender`: rain at 40% and 70%, wind at 25 and 39 km/h, cool night at 22 °C

## PAGASA layer (v0.2)
Built from the two PAGASA products CDRRMO Isabela City already uses every morning:
- **Mindanao PRSD Regional Forecast (5:00 AM):** sky condition, temperature range, winds, coastal waters, synopsis.
- **iHeatMAP (barangay level):** hourly heat index from 8 AM to 5 PM.

`pagasa/PagasaFeed.kt` defines the feed and converts PAGASA's wording into a rain outlook:
- "...heavy rains" → very likely
- "rainshowers and/or thunderstorms" → likely
- "isolated" or "localized" → possible (no override; the other sources decide)
- no rain words → unlikely

`samples/pagasa-feed-2026-10-28.json` is a real example built from the Oct 28 infographics. `test/.../PagasaChecks.kt` runs 16 checks based on the Oct 22, 28 and 30 infographics.

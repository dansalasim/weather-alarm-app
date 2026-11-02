# Weather Alarm — daily PAGASA feed

Updated every morning by the CDRRMO Isabela City scheduled tasks:
- `isabela-city-basilan/heat.json` — DOST-PAGASA iHeatMAP, Brgy. Sunrise Village (~5:50 AM)
- `isabela-city-basilan/regional.json` — DOST-PAGASA Mindanao PRSD Regional Forecast and warnings (~6:32 AM)

The app reads these files from this branch. Values are copied exactly from PAGASA; nothing is estimated.
App code lives on the `main` branch.

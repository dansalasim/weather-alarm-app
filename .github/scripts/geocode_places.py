#!/usr/bin/env python3
"""
Adds coordinates to every PSGC city/municipality (data-src/ph-lgus.json) and writes
ph-places.json for the app. Runs in GitHub Actions (needs internet).

1. Open-Meteo geocoding (GeoNames, CC BY 4.0): pick the result whose province matches.
2. Fallback: OpenStreetMap Nominatim (ODbL), 1 request/second, "name, province, Philippines".
3. Sanity: inside the Philippines and not far from the rest of its province.
Output keys are short to keep the app's file small:
  c code, n name, t c|m (city/municipality), p province, r region, la, lo, q quality
"""
import json, math, re, sys, time, unicodedata, urllib.parse, urllib.request
from statistics import median

UA = "WeatherAlarmPH/0.1 (github.com/dansalasim/weather-alarm-app; one-off place lookup)"
PH_BOX = (4.2, 21.5, 114.0, 127.5)  # lat min/max, lon min/max


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except Exception as e:
            time.sleep(2 + attempt * 3)
    return None


def norm(s):
    s = unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode().lower()
    s = re.sub(r"\(.*?\)", " ", s)
    for w in ["province of", "city of", "municipality of", " city", "capital"]:
        s = s.replace(w, " ")
    s = re.sub(r"[^a-z0-9 ]", " ", s)
    return " ".join(s.split())


WEAK = {"de", "del", "sur", "norte", "oriental", "occidental", "island", "islands", "the", "and"}
ALIASES = {  # PSGC province → older/alternate names seen in gazetteers
    "davao de oro": ["compostela valley"],
    "samar": ["western samar"],
    "cotabato": ["north cotabato"],
    "maguindanao del norte": ["maguindanao"],
    "maguindanao del sur": ["maguindanao"],
    "davao occidental": ["davao del sur"],
}


def province_score(admin, province):
    a, p = norm(admin), norm(province)
    if not a or not p:
        return 0
    names = [p] + ALIASES.get(p, [])
    if a in names:
        return 3
    if any(n in a or a in n for n in names):
        return 2
    strong = lambda x: {t for t in x.split() if t not in WEAK}
    if any(strong(a) & strong(n) for n in names):
        return 1
    return 0


def search_name(name):
    n = name
    n = re.sub(r"^City of ", "", n)
    n = re.sub(r"\s*\(.*?\)\s*", " ", n).strip()
    return n


def in_ph(lat, lon):
    return PH_BOX[0] <= lat <= PH_BOX[1] and PH_BOX[2] <= lon <= PH_BOX[3]


def km(a, b, c, d):
    r = 6371
    p1, p2 = math.radians(a), math.radians(c)
    x = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(d - b) / 2) ** 2
    return 2 * r * math.asin(math.sqrt(x))


def open_meteo(place):
    q = search_name(place["name"])
    url = "https://geocoding-api.open-meteo.com/v1/search?" + urllib.parse.urlencode(
        {"name": q, "count": 30, "language": "en", "format": "json", "countryCode": "PH"})
    res = (get(url) or {}).get("results") or []
    best, best_key = None, None
    for r in res:
        if r.get("country_code") != "PH":
            continue
        pscore = province_score(r.get("admin2", ""), place["province"]) if place["province"] else \
            max(province_score(r.get("admin1", ""), place["region"]), 1)
        name_ok = norm(r.get("name")) == norm(q) or norm(r.get("admin3", "")) == norm(q)
        fc = r.get("feature_code", "")
        fscore = {"PPLA": 4, "PPLA2": 4, "PPLA3": 3, "PPLC": 4, "ADM3": 3, "ADM2": 2, "PPL": 2}.get(fc, 1)
        key = (pscore, name_ok, fscore, r.get("population") or 0)
        if pscore > 0 and name_ok and (best_key is None or key > best_key):
            best, best_key = r, key
    if best:
        return best["latitude"], best["longitude"], f"om{best_key[0]}"
    return None


def nominatim(place):
    parts = [search_name(place["name"]) + (" City" if place["type"] == "city" and not place["name"].endswith("City") else "")]
    if place["province"] and not place["province"].startswith("NCR"):
        parts.append(place["province"])
    parts.append("Philippines")
    url = "https://nominatim.openstreetmap.org/search?" + urllib.parse.urlencode(
        {"q": ", ".join(parts), "format": "jsonv2", "countrycodes": "ph", "limit": 1})
    time.sleep(1.1)
    res = get(url) or []
    if res:
        return float(res[0]["lat"]), float(res[0]["lon"]), "osm"
    return None


def main():
    src = json.load(open(sys.argv[1]))["places"]
    out, misses = [], []
    for i, p in enumerate(src):
        hit = open_meteo(p)
        if not hit or not in_ph(hit[0], hit[1]):
            hit = nominatim(p)
        if hit and in_ph(hit[0], hit[1]):
            out.append({"c": p["code"], "n": p["name"], "t": "c" if p["type"] == "city" else "m",
                        "p": p["province"], "r": p["region"], "la": round(hit[0], 4), "lo": round(hit[1], 4), "q": hit[2]})
        else:
            misses.append(p)
        if i % 100 == 0:
            print(f"{i}/{len(src)} done, {len(misses)} missing", flush=True)

    # Outliers: more than 150 km from the median of the rest of its province → retry with OSM.
    by_prov = {}
    for o in out:
        if o["p"]:
            by_prov.setdefault(o["p"], []).append(o)
    retried = 0
    for prov, items in by_prov.items():
        if len(items) < 3:
            continue
        mla, mlo = median(i["la"] for i in items), median(i["lo"] for i in items)
        for o in items:
            if km(o["la"], o["lo"], mla, mlo) > 150 and o["q"] != "osm":
                hit = nominatim({"name": o["n"], "province": prov, "type": "city" if o["t"] == "c" else "municipality"})
                retried += 1
                if hit and in_ph(hit[0], hit[1]) and km(hit[0], hit[1], mla, mlo) < 150:
                    o["la"], o["lo"], o["q"] = round(hit[0], 4), round(hit[1], 4), "osm-fix"
                else:
                    o["q"] += "-far"

    report = {
        "total": len(src), "with_coordinates": len(out), "missing": [m["name"] + ", " + str(m["province"]) for m in misses],
        "quality": {q: sum(1 for o in out if o["q"] == q) for q in sorted({o["q"] for o in out})},
        "far": [o["n"] + ", " + str(o["p"]) for o in out if o["q"].endswith("-far")],
        "outliers_retried": retried,
    }
    json.dump({"source": "PSA PSGC (30 June 2026, CC BY 4.0); coordinates: Open-Meteo/GeoNames (CC BY 4.0) and "
                         "© OpenStreetMap contributors (ODbL)", "places": out},
              open("ph-places.json", "w"), ensure_ascii=False, separators=(",", ":"))
    json.dump(report, open("ph-places-report.json", "w"), ensure_ascii=False, indent=1)
    print(json.dumps({k: v for k, v in report.items() if k != "missing"}, indent=1))
    print("missing:", len(misses))


if __name__ == "__main__":
    main()

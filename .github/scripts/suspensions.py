#!/usr/bin/env python3
"""
Adds or lifts a class/work suspension in the feed's suspensions.json,
from the body of a "Class/work suspension" GitHub issue form.

Usage: suspensions.py <path/to/suspensions.json>
Env:   ISSUE_BODY, ISSUE_NUMBER, ISSUE_URL
Writes result.md (message for the issue comment) and prints ok=true|false.
"""
import json, os, re, sys
from datetime import datetime, timedelta, timezone

MANILA = timezone(timedelta(hours=8))
AREAS = {
    "City of Isabela (Basilan)": ("isabela-city-basilan", "City Government of Isabela"),
    "Zamboanga City": ("zamboanga-city", "City Government of Zamboanga"),
}
LEVELS = ["All levels", "Preschool", "Elementary", "High school", "College"]


def sections(body: str) -> dict:
    """Issue forms render as '### Label' followed by the answer."""
    out, label, buf = {}, None, []
    for line in body.replace("\r\n", "\n").split("\n"):
        m = re.match(r"^###\s+(.*)$", line)
        if m:
            if label is not None:
                out[label] = "\n".join(buf).strip()
            label, buf = m.group(1).strip(), []
        else:
            buf.append(line)
    if label is not None:
        out[label] = "\n".join(buf).strip()
    return {k: ("" if v == "_No response_" else v) for k, v in out.items()}


def fail(msg: str):
    open("result.md", "w").write("❌ **Not added.** " + msg + "\n\nEdit nothing here; just open a new form with the fix.")
    print("ok=false")
    sys.exit(0)


def main():
    path = sys.argv[1]
    body = os.environ.get("ISSUE_BODY", "")
    number = os.environ.get("ISSUE_NUMBER", "0")
    url = os.environ.get("ISSUE_URL", "")
    f = sections(body)

    area_label = f.get("City", "")
    if area_label not in AREAS:
        fail(f"Unknown city: '{area_label}'.")
    area_id, default_source = AREAS[area_label]

    now = datetime.now(MANILA)
    when = f.get("When", "")
    if when == "Today":
        date = now.date()
    elif when == "Tomorrow":
        date = now.date() + timedelta(days=1)
    else:
        raw = f.get("Other date", "").strip()
        try:
            date = datetime.strptime(raw, "%Y-%m-%d").date()
        except ValueError:
            fail(f"Please type the date as YYYY-MM-DD (got '{raw}').")
    if date < now.date() - timedelta(days=1):
        fail(f"{date} is in the past.")

    try:
        data = json.load(open(path))
    except (FileNotFoundError, json.JSONDecodeError):
        data = {"announcements": []}
    items = data.get("announcements", [])

    # Keep the file small: drop anything older than yesterday.
    cutoff = (now.date() - timedelta(days=1)).isoformat()
    items = [a for a in items if a.get("date", "") >= cutoff]

    action = f.get("Action", "")
    if action.startswith("Lift"):
        before = len(items)
        items = [a for a in items if not (a.get("areaId") == area_id and a.get("date") == date.isoformat())]
        removed = before - len(items)
        if removed == 0:
            fail(f"There was no suspension for {area_label} on {date} to lift.")
        msg = f"✅ **Lifted.** Removed {removed} announcement(s) for **{area_label}** on **{date:%a, %b %d}**."
    else:
        what = f.get("Suspended", "")
        classes = what in ("Classes", "Classes and work")
        work = what in ("Work", "Classes and work")
        if not (classes or work):
            fail("Choose what is suspended (classes, work, or both).")
        checked = re.findall(r"- \[[xX]\] (.+)", f.get("Levels (for classes)", ""))
        levels = [l.strip() for l in checked if l.strip() in LEVELS]
        if classes and not levels:
            levels = ["All levels"]
        if "All levels" in levels:
            levels = ["All levels"]
        link = f.get("Link to the announcement", "").strip()
        if link and not re.match(r"^https?://", link):
            fail("The link should start with https://")
        entry = {
            "id": f"{area_id}-{date.isoformat()}-{number}",
            "areaId": area_id,
            "date": date.isoformat(),
            "classes": classes,
            "work": work,
            "levels": levels if classes else [],
            "sector": f.get("Public or private", "") or "Public and private",
            "period": f.get("Hours", "") or "Whole day",
            "details": (f.get("Details (optional)", "").strip() or None),
            "source": (f.get("Source", "").strip() or default_source),
            "sourceUrl": (link or None),
            "postedAt": now.isoformat(timespec="seconds"),
            "issue": url,
        }
        items.append(entry)
        what_txt = {"Classes": "No classes", "Work": "No work", "Classes and work": "No classes and work"}[what]
        scope = (", ".join(levels) + " · " if classes else "") + entry["sector"]
        msg = (f"✅ **Added.** {what_txt} in **{area_label}** on **{date:%a, %b %d}** "
               f"({entry['period'].lower()}) — {scope}.\n\nPhones will show it on their next refresh. "
               f"To cancel, open a new form and choose *Lift (cancel) a suspension*.")

    items.sort(key=lambda a: (a.get("date", ""), a.get("areaId", "")))
    data = {"updatedAt": now.isoformat(timespec="seconds"), "announcements": items}
    with open(path, "w") as out:
        json.dump(data, out, indent=2, ensure_ascii=False)
        out.write("\n")
    open("result.md", "w").write(msg)
    print("ok=true")


if __name__ == "__main__":
    main()

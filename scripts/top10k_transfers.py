#!/usr/bin/env python3
"""Aggregate top-10k (overall league 314) transfers for the upcoming GW and POST to fpl-web.

Stdlib only. Env: FPL_WEB_URL, FPL_INGEST_TOKEN, CONCURRENCY (8), PAGES (200), MY_ENTRY (5498977), OUT_FILE (optional).
Transfers are counted NET per manager (in-then-out of the same player cancels).
"""
import json, os, random, sys, time, urllib.request, urllib.error
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

API = "https://fantasy.premierleague.com/api"
UA = "fpl-live-tracker/1.29 (+https://fpl.thewalawalas.com; daily top10k stats)"
CONC = int(os.environ.get("CONCURRENCY", "8"))
PAGES = int(os.environ.get("PAGES", "200"))
MY_ENTRY = int(os.environ.get("MY_ENTRY", "5498977"))


def get(url, tries=5):
    delay, err = 1.0, None
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                raise
            err = e
        except Exception as e:
            err = e
        if attempt < tries - 1:
            time.sleep(delay + random.random())
            delay *= 2
    raise err


def main():
    t0 = time.time()
    boot = get(f"{API}/bootstrap-static/")
    nxt = next((e for e in boot["events"] if e.get("is_next")), None)
    if not nxt:
        print("no upcoming gameweek; nothing to do")
        return 0
    gw = nxt["id"]
    cur = next((e["id"] for e in boot["events"] if e.get("is_current")), None)
    teams = {t["id"]: t["short_name"] for t in boot["teams"]}
    pos = {p["id"]: p["singular_name_short"] for p in boot["element_types"]}
    els = {e["id"]: e for e in boot["elements"]}

    def page(n):
        try:
            d = get(f"{API}/leagues-classic/314/standings/?page_standings={n}")
            return [r["entry"] for r in d["standings"]["results"]]
        except Exception as e:
            print(f"page {n} failed: {e}", file=sys.stderr)
            return []

    with ThreadPoolExecutor(CONC) as ex:
        ids = [i for p in ex.map(page, range(1, PAGES + 1)) for i in p]
    ids = list(dict.fromkeys(ids))[: PAGES * 50]
    print(f"GW{gw}: {len(ids)} entries from standings in {time.time()-t0:.0f}s", flush=True)

    # FPL's public /transfers/ endpoint hides pending moves until the deadline passes, so we
    # count both the upcoming GW and the current (just-locked) GW and fall back to the latter
    # when nobody's upcoming moves are visible yet.
    targets = [g for g in (gw, cur) if g]
    ins = {g: Counter() for g in targets}
    outs = {g: Counter() for g in targets}
    movers = {g: 0 for g in targets}
    failed = 0

    def fetch(eid):
        try:
            return get(f"{API}/entry/{eid}/transfers/")
        except Exception:
            return None

    with ThreadPoolExecutor(CONC) as ex:
        for i, tr in enumerate(ex.map(fetch, ids), 1):
            if tr is None:
                failed += 1
                continue
            for g in targets:
                net = Counter()
                for t in tr:
                    if t.get("event") == g:
                        net[t["element_in"]] += 1
                        net[t["element_out"]] -= 1
                if any(net.values()):
                    movers[g] += 1
                for el, v in net.items():
                    if v > 0:
                        ins[g][el] += 1
                    elif v < 0:
                        outs[g][el] += 1
            if i % 1000 == 0:
                print(f"  {i}/{len(ids)} fetched, failed={failed}, {time.time()-t0:.0f}s", flush=True)

    sample = len(ids) - failed
    shown = gw if movers[gw] > 0 or not cur else cur
    mode = "pre_deadline" if shown == gw else "post_deadline"
    my_squad = []
    try:
        if cur:
            my_squad = [p["element"] for p in get(f"{API}/entry/{MY_ENTRY}/event/{cur}/picks/")["picks"]]
    except Exception as e:
        print(f"my squad fetch failed: {e}", file=sys.stderr)

    def rows(c):
        out = []
        for el, n in c.most_common(10):
            e = els.get(el, {})
            out.append({
                "element": el, "web_name": e.get("web_name", str(el)),
                "team": teams.get(e.get("team"), "?"), "position": pos.get(e.get("element_type"), "?"),
                "count": n, "pct": round(100.0 * n / max(1, sample), 2),
                "overall_transfers_in": e.get("transfers_in_event", 0),
                "overall_transfers_out": e.get("transfers_out_event", 0),
                "in_my_squad": el in my_squad,
            })
        return out

    payload = {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "gw": shown, "mode": mode, "next_gw": gw, "next_deadline_time": nxt.get("deadline_time"),
        "next_gw_visible_movers": movers[gw],
        "sample_size": sample, "requested": len(ids), "failed_count": failed,
        "managers_with_transfers": movers[shown], "runtime_seconds": round(time.time() - t0, 1),
        "my_entry": MY_ENTRY, "my_squad": my_squad,
        "transfers_in": rows(ins[shown]), "transfers_out": rows(outs[shown]),
    }
    body = json.dumps(payload).encode()
    if os.environ.get("OUT_FILE"):
        with open(os.environ["OUT_FILE"], "wb") as f:
            f.write(body)
    url = os.environ.get("FPL_WEB_URL")
    if url:
        req = urllib.request.Request(url, data=body, method="POST", headers={
            "Content-Type": "application/json", "X-Ingest-Token": os.environ.get("FPL_INGEST_TOKEN", "")})
        with urllib.request.urlopen(req, timeout=30) as r:
            print("posted:", r.status, r.read().decode())
    print(f"done GW{shown} ({mode}) sample={sample} failed={failed} runtime={payload['runtime_seconds']}s")
    print("top in:", [(r["web_name"], r["count"]) for r in payload["transfers_in"][:3]])
    print("top out:", [(r["web_name"], r["count"]) for r in payload["transfers_out"][:3]])
    return 0 if sample > 0.9 * len(ids) else 1


if __name__ == "__main__":
    sys.exit(main())

# fpl_live_leagues

Kotlin CLI and Dropwizard webapp for **live FPL tracking** during a gameweek (the livefpl.net live-rank / live-leagues experience, not the rest of that product).

- Enter an FPL entry ID for live GW points (gross / net after hit), projected bonus, autosubs, chip, official overall rank, value, bank, players remaining, captain status
- Open a classic league by picker (mini leagues: `< 50` teams and no next standings page) **or** by numeric league ID
- Live league table: live rank, official rank, live total, GW net/gross, remaining, overall rank, value, chip (WC/FH/BB/TC/AM)
- Player-by-player XI + bench with minutes, raw points, projected vs confirmed bonus, C/VC, auto-sub in/out
- Live games strip (your players, C/VC, bench, minutes, contribution) and 90s auto-refresh while fixtures are in progress
- Pre-season / before kickoff still renders the table from the official FPL API (zeros, remaining = full XI)

Uses only the official FPL API. Bootstrap, fixtures, and event-live payloads are cached in memory for ~20s.

Out of scope: transfer planner, price changes, top10k EO, what-if overall rank, accounts, ads.

## Requirements

- Java 11+ (17 or 21 is fine)
- Internet access (calls the public FPL API)

## CLI

Build:

```bash
./gradlew build
```

Run:

```bash
./gradlew run --args='YOUR_TEAM_ID'
```

Example:

```bash
./gradlew run --args='1234567'
```

### Find your team ID

In the browser URL on the Fantasy Premier League site:

- `https://fantasy.premierleague.com/entry/1234567/event/1`
- The team ID is the number after `/entry/` (`1234567`).

### Navigation

- `Up/Down` arrows (or `k/j`) to move
- `Enter` to select
- `Left` or `Esc` to go back (where supported)
- `q` to quit

### Install a launcher

```bash
./gradlew installDist
./build/install/fpl_live_leagues/bin/fpl_live_leagues YOUR_TEAM_ID
```

## Webapp

Dropwizard JSON API plus a dense live-tracker UI.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/` | Live tracker UI |
| GET | `/health` | App health; also probes FPL bootstrap when cheap |
| GET | `/entries/{entryId}/live?gameweek=&autosubs=` | Home / live rank for one manager (default current GW, autosubs on) |
| GET | `/entries/{entryId}/mini-leagues` | Mini leagues for a team (`entryCount < 50 && !hasNext`) |
| GET | `/leagues/{leagueId}/live?gameweek=&autosubs=` | Live table for any classic league ID (capped at 50 with a note) |
| GET | `/entries/{entryId}/event/{gameweek}/picks?autosubs=` | Player-by-player live breakdown |

Live math:

- **GW gross** = sum of effective player points × multiplier (captain 2× / TC 3×, bench 0 unless BB)
- **GW net** = GW gross − transfer hit (`event_transfers_cost`)
- **Live total** = official season total − official GW points + GW net (avoids double-counting)
- **Bonus** = official bonus when FPL has set it; otherwise 3/2/1 projected from BPS on that fixture
- **Autosubs** = FPL `automatic_subs` when present, then prospective FPL-style replacements (GK only for GK; 3 DEF / 2 MID / 1 FWD)
- **Chips** = WC / FH / BB / TC / AM (assistant manager)

True **live overall rank** is not computed (that needs a private histogram). The UI labels FPL's official overall rank as official.

Config is Dropwizard YAML (`config.yml`). The HTTP connector listens on **8080**. Override the FPL API with `fplBaseUrl`.

Run from source:

```bash
./gradlew runWeb
```

Then open http://localhost:8080/ and http://localhost:8080/health

Fat jar:

```bash
./gradlew shadowJar
java -jar build/libs/fpl-web-1.0.jar server config.yml
```

## Tests

```bash
./gradlew test
```

Covers mini-league filtering, captain/bench multipliers, BPS bonus projection, auto-sub eligibility, GW net, and live season total (no double-count).

## Docker

Multi-stage image (Temurin 17 JDK build → Temurin 17 JRE runtime), port 8080:

```bash
docker build -t fpl-web:1.3 .
docker run --rm -p 8080:8080 fpl-web:1.3
```

`Dockerfile.runtime` is a JRE-only image if you already built `fpl-web-1.0.jar`.

## Deploy to k3s

Manifests live in `k8s/`. They create namespace `fpl`, one replica, and a NodePort **30810 → 8080**. They do not touch `movie-api`.

On a machine that can build images:

```bash
docker build -t fpl-web:1.3 .
docker save fpl-web:1.3 | ssh randomnumber01@100.67.63.33 'sudo k3s ctr images import -'
```

Apply manifests (from this repo):

```bash
ssh randomnumber01@100.67.63.33 'sudo k3s kubectl apply -f -' < k8s/namespace.yaml
ssh randomnumber01@100.67.63.33 'sudo k3s kubectl apply -f -' < k8s/deployment.yaml
ssh randomnumber01@100.67.63.33 'sudo k3s kubectl apply -f -' < k8s/service.yaml
```

Check:

```bash
ssh randomnumber01@100.67.63.33 'sudo k3s kubectl -n fpl get pods,svc'
curl http://100.67.63.33:30810/health
```

The Deployment pins the pod to `randomnumber01` so a locally imported image does not need to exist on the worker. An optional Ingress (`k8s/ingress.yaml`) exposes path `/fpl` on the Traefik LoadBalancer; the supported access path is the NodePort.

## Developer notes

- Kotlin concepts used in this project: `docs/kotlin-concepts.md`

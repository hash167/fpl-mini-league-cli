# fpl_live_leagues

Kotlin CLI and Dropwizard webapp that:

- Takes an FPL team/entry ID
- Lists mini leagues (`< 50` teams, no extra standings page)
- Shows live GW points and player-by-player contributions (captain multiplier)

The CLI and webapp share `FplApi` / `FplService` so filtering and live-points math stay in sync.

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

Dropwizard JSON API plus a small HTML UI.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/` | HTML UI (enter team ID → pick league → live table → picks) |
| GET | `/health` | App health; also probes FPL bootstrap when cheap |
| GET | `/entries/{entryId}/mini-leagues` | Mini leagues for a team |
| GET | `/leagues/{leagueId}/live?gameweek=` | Standings with live points (default: current GW) |
| GET | `/entries/{entryId}/event/{gameweek}/picks` | Player-by-player live contributions |

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

Covers mini-league filtering (`entryCount < 50` and no next page) and live-points math (captain / bench multipliers) against mocked FPL JSON.

## Docker

Multi-stage image (Temurin 17 JDK build → Temurin 17 JRE runtime), port 8080:

```bash
docker build -t fpl-web:1.0 .
docker run --rm -p 8080:8080 fpl-web:1.0
```

`Dockerfile.runtime` is a JRE-only image if you already built `fpl-web-1.0.jar`.

## Deploy to k3s

Manifests live in `k8s/`. They create namespace `fpl`, one replica, and a NodePort **30810 → 8080**. They do not touch `movie-api`.

On a machine that can build images:

```bash
docker build -t fpl-web:1.0 .
docker save fpl-web:1.0 | ssh randomnumber01@100.67.63.33 'sudo k3s ctr images import -'
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

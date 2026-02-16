# fpl_live_leagues

Kotlin CLI tool that:
- Takes an FPL team/entry ID
- Lists mini leagues (`< 50` teams)
- Lets you navigate leagues and teams
- Shows live GW points and player-by-player contributions

## Requirements

- Java 11+
- Internet access (calls FPL public API)

## Build

```bash
./gradlew build
```

## Run

```bash
./gradlew run --args='YOUR_TEAM_ID'
```

Example:

```bash
./gradlew run --args='1234567'
```

## Navigation

- `Up/Down` arrows (or `k/j`) to move
- `Enter` to select
- `Left` or `Esc` to go back (where supported)
- `q` to quit

## Create a terminal binary (launcher script)

Install distribution:

```bash
./gradlew installDist
```

Run installed launcher:

```bash
./build/install/fpl_live_leagues/bin/fpl_live_leagues YOUR_TEAM_ID
```

Optional global command:

```bash
ln -sf "$(pwd)/build/install/fpl_live_leagues/bin/fpl_live_leagues" /usr/local/bin/fpl-live-leagues
```

Then run from anywhere:

```bash
fpl-live-leagues YOUR_TEAM_ID
```

## Run tests

```bash
./gradlew test
```

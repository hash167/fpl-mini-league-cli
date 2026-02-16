# Kotlin Concepts in This Project

This note highlights core Kotlin concepts used in the FPL mini league CLI.

## Null Safety

Kotlin forces explicit handling of nullable values (`T?`) so we avoid many runtime null crashes.

Examples:
- `src/main/kotlin/FplLiveLeaguesCli.kt`: `currentGameweekId(...)` returns `Int?` because FPL data may be missing.
- `src/main/kotlin/FplLiveLeaguesCli.kt`:  
  `val currentGameweek = currentGameweekId(bootstrap) ?: throw IllegalStateException(...)`  
  uses the Elvis operator (`?:`) to fail fast if value is null.
- `src/main/kotlin/JsonExtensions.kt`: helpers like `int(key): Int?`, `str(key): String?`, `bool(key): Boolean?` return nullable types when fields are absent or malformed.

Why it matters here:
- FPL API responses are external and can change or omit fields.
- Nullable parsing + explicit checks makes the CLI resilient.

## Extension Functions

Extension functions add methods to existing types without modifying their source.

Examples:
- `src/main/kotlin/JsonExtensions.kt` defines extensions on `JsonObject`:
  - `obj(key)`
  - `arr(key)`
  - `int(key)`
  - `str(key)`
  - `bool(key)`

This allows concise JSON parsing such as:
- `response.obj("league").int("entries")`
- `standings.bool("has_next") == true`

Why it matters here:
- Removes repetitive cast/parse boilerplate.
- Keeps API parsing code in `src/main/kotlin/FplApi.kt` readable.

## Type Inference

Kotlin often infers types from the right-hand side, reducing verbosity.

Examples:
- `val api = FplApi()` infers `FplApi`.
- `val miniLeagues = leagues.mapNotNull { ... }` infers `List<LeagueSummary>`.
- `val currentGameweek = ... ?: throw ...` infers non-null `Int`.

Why it matters here:
- Keeps transformation-heavy code short and clearer.
- Still preserves strong static typing at compile time.

## Smart Casts

After Kotlin checks a type condition, it can automatically treat a value as that narrower type.

Examples:
- `src/main/kotlin/FplApi.kt`:
  - `val obj = item as? JsonObject ?: return@mapNotNull null`  
    after this, `obj` is smart-cast and used as `JsonObject`.
- `src/main/kotlin/FplLiveLeaguesCli.kt`:
  - `when (leagueSelection) { is MenuSelection.Selected -> ... }`  
    inside the branch, `leagueSelection` is smart-cast to `MenuSelection.Selected`.

Why it matters here:
- Fewer manual casts.
- Safer handling of sealed menu states and heterogeneous JSON values.

## Summary

These features work together to make this CLI safer and easier to maintain:
- Null safety for unreliable external data
- Extensions for clean parsing APIs
- Type inference for concise code
- Smart casts for safe branching and casting

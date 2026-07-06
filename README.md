# Exchange Insights — RuneLite plugin

The companion plugin for the [Exchange Insights](https://exchange-insights.gg)
dashboard. It streams data only a logged-in client can see to the dashboard's
ingest API ([API reference](https://github.com/TheSpryt/OSRS-Invest/blob/main/docs/PLUGIN-INGEST.md)):

- **GE fills** — every buy/sell you complete, so the dashboard can score
  **realized vs modeled** flip P&L (does the Flip Finder's spread actually get
  captured?).
- **Identity** — the character you're logged into (stable account hash + RSN),
  sent once per login so the dashboard links your OSRS account (and alts)
  automatically.
- **GE offer book** *(optional)* — live offer state changes.
- **Datamine** *(optional)* — a full scan of the item id space, run only when the
  game **cache revision changes** (≈ after an update) and chunked across ticks so
  it never hitches. It fingerprints each tradeable item and lets the server detect
  what's new or changed — and which tradeable items the wiki doesn't have yet
  (pre-wiki finds).

It also delivers **in-game alerts**: watchlist alerts with the RuneLite channel
selected are polled every 30 seconds while you're logged in and shown as a game
chat message plus a system notification.

## How fills are captured (and why they're accurate)

`FillTracker` tracks each GE slot's cumulative `quantitySold` and emits only the
**delta** since the last observation. The first time a slot is seen — including
the offer snapshot the client re-broadcasts on login — only establishes a
baseline, so historical fills are never replayed as new. A freshly placed offer
fires its `BUYING`/`SELLING` event at quantity 0 first, so even an instant full
fill is captured by the following increment. The server also dedupes identical
fills, so retries and reconnects are harmless. (`FillTrackerTest` covers these.)

Fills send the **listed price per item** (pre-tax); the dashboard applies GE tax
itself, keeping realized margins apples-to-apples with the modeled ones.

## Configuration

In RuneLite → plugin settings:

| Setting | Default | Notes |
|---|---|---|
| **Dashboard URL** | `https://exchange-insights.gg` | change only if you self-host |
| **Plugin token** | — | your personal token, generated on the dashboard under **Account settings** |
| **Send GE fills** | on | the core feature |
| **Send GE offer book** | off | also forward placed/cancelled offer states |
| **Datamine new items** | off | bounded forward scan of item ids on login |
| **In-game alerts** | on | watchlist alerts (RuneLite channel) as chat + notification |

Nothing is sent until both the URL and token are set. The token is per-user and
revocable from the same account page; it attributes everything this plugin sends
to your account.

## Building

This is a standard RuneLite external plugin (Gradle). A Gradle wrapper is
included, so no system Gradle install is needed:

```bash
cd plugin
./gradlew build         # downloads Gradle 8.14.3 on first run; use gradlew.bat on Windows
```

`build.gradle` pins a known-good RuneLite client version (`compileOnly`); bump it
to match your client. To run it against a local RuneLite, use the RuneLite
[external-plugin developer workflow](https://github.com/runelite/example-plugin)
(add this project's `src/main/java` to the client run configuration, or sideload
the built jar).

> The fill-tracking core (`FillTracker`) is framework-free and unit-tested; the
> rest depends on the RuneLite client API, so build against the client jar.

## Privacy

Everything is sent **only** to the URL you configure, authenticated with your own
token. There is no third-party telemetry. This plugin does not automate the game
or read anything beyond your own GE activity and the public item cache.

<p align="center"><b>Powered by Exchange-Insights.gg</b></p>

<p align="center">
  <a href="https://runelite.net/plugin-hub/show/exchange-insights"><img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fapi.runelite.net%2Fpluginhub%2Fshields%2Finstalls%2Fplugin%2Fexchange-insights&label=installs&color=brightgreen" alt="Plugin Hub installs"></a>
  <a href="https://github.com/TheSpryt/Exchange-Insights-Plugin/releases/latest"><img src="https://img.shields.io/github/v/release/TheSpryt/Exchange-Insights-Plugin?label=version&color=blue" alt="Latest release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/TheSpryt/Exchange-Insights-Plugin?label=license&color=orange" alt="License"></a>
  <a href="https://runelite.net/plugin-hub/show/exchange-insights"><img src="https://img.shields.io/badge/RuneLite-Plugin%20Hub-yellow" alt="RuneLite Plugin Hub"></a>
</p>

# Exchange Insights RuneLite plugin

The companion plugin for the Exchange Insights dashboard. It streams data only a
logged-in client can see to the dashboard's ingest API:

- **GE fills**: every buy/sell you complete, so the dashboard can score
  **realized vs modeled** flip P&L (does the Flip Finder's spread actually get
  captured?).
- **Identity**: the character you're logged into (stable account hash + RSN),
  sent once per login so the dashboard links your OSRS account (and alts)
  automatically.
- **GE offer book** *(optional)*: live offer state changes.
- **Datamine** *(optional)*: a full scan of the item id space, run only when the
  game **cache revision changes** (≈ after an update) and chunked across ticks so
  it never hitches. It fingerprints each tradeable item and lets the server detect
  what's new or changed, and which tradeable items the wiki doesn't have yet
  (pre-wiki finds).

It also delivers **in-game alerts**: watchlist alerts with the RuneLite channel
selected are polled every 30 seconds while you're logged in and shown as a game
chat message plus a system notification.

## Works without an account

The **GE offer info** needs no account, token, or setup: while you're setting up
a Grand Exchange offer, the item's description text in the offer window gains
live insta-buy/insta-sell prices, the after-tax item margin with ROI, and the
buy limit, read from Exchange Insights' public market data (download-only;
nothing about you or your account is sent). It lives in the same text block the
game writes "Actively traded price" into, so it reads as native UI; the flavour
examine text is swapped out to make room (the box is fixed-height). ("Item
margin" is the plain quote spread, as on the site's Item margins board; the
quant-adjusted Flip Finder economics stay a dashboard feature.)

## Linking your account

To unlock the two-way features (live offer board, trade history, alerts, flip
margins), tick **Link account in browser** in the plugin settings. It opens the
dashboard in your browser; approve the link while signed in and the plugin
receives its token and stores it automatically. You can also do it manually:
generate a **plugin token** on the dashboard under **Account settings** and
paste it into the plugin's **Plugin token** setting. Either way there's no
separate panel; everything is configured in the plugin settings.

## How trades are captured (and why they're accurate)

The plugin pushes its **full 8-slot offer book** (every slot, including empty
ones) to the dashboard on each offer change. Because each slot carries its
cumulative filled quantity, the server derives fills by comparing the new
quantity against the last one it stored for the same offer: the same
delta-tracking idea, but driven by a full self-healing snapshot, so a dropped
push is corrected by the next one and nothing is double-counted. First sight of
an offer only sets a baseline, so progress made before tracking is never invented
as a fill.

Prices are the **listed price per item** (pre-tax); the dashboard applies GE tax
itself, keeping realized margins apples-to-apples with the modeled ones. On the
dashboard, matched buy→sell round-trips become your verified trade history.

## Configuration

In RuneLite → plugin settings:

| Setting | Default | Notes |
|---|---|---|
| **Dashboard URL** | `https://exchange-insights.gg` | change only if you self-host |
| **Plugin token** | *(empty)* | your personal token, generated on the dashboard under **Account settings** |
| **Link account in browser** | *(button)* | tick to open the dashboard and approve; the token is fetched and stored for you |
| **Sync GE offers & trades** | on | live offer board + verified trade history (needs a token) |
| **Datamine new items** | on | bounded forward scan of item ids on login |
| **In-game alerts** | on | watchlist alerts as a chat message and/or system notification |
| **Alert delivery method** | Both | chat message, system notification, or both |
| **GE offer info** | on | live prices + margin in the offer window text; no account needed |
| **Premium: show quant flip margins** | on | premium accounts see the quant flip margin, and the info icon opens flips |
| **Offer age badges** | on | on the offers screen, flag each active slot vs the market |
| **Left-click Collect to bank** | off | make the GE Collect button send to bank by default |

Nothing about your account or activity is sent until a token is set (the GE
overlay only *downloads* public prices). The token is per-user and revocable from
the account page; it attributes everything this plugin sends to your account.

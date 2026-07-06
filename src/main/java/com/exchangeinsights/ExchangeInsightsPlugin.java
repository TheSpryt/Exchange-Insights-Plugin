/*
 * Copyright (c) 2026, Exchange Insights — BSD 2-Clause License (see LICENSE).
 */
package com.exchangeinsights;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Player;
import net.runelite.api.VarPlayer;
import net.runelite.api.Varbits;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

@Slf4j
@PluginDescriptor(
	name = "Exchange Insights",
	description = "Streams your GE fills (and optional datamine) to your Exchange Insights dashboard.",
	tags = {"ge", "grand exchange", "flipping", "prices", "investing", "economy"}
)
public class ExchangeInsightsPlugin extends Plugin
{
	private static final int GE_SLOTS = 8;

	// Datamine: a FULL scan of the item id space, but run only when the game's
	// cache revision changes (≈ after an update) so the cost is paid rarely. The
	// scan is chunked across client ticks to avoid a frame hitch, and the server
	// diffs each item against its history + the wiki catalogue, so the client just
	// reports what it sees.
	private static final int SCAN_HARD_MAX = 36000; // safely above the live max item id
	private static final int SCAN_END_FLOOR = 30000; // don't early-stop before here
	private static final int SCAN_NULL_RUN_END = 512; // consecutive nameless ids → end of items
	private static final int SCAN_CHUNK = 800; // ids probed per client tick
	private static final int SEND_BATCH = 200; // items per ingest request
	private static final String KEY_LAST_REVISION = "lastScanRevision";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ExchangeInsightsConfig config;

	@Inject
	private Notifier notifier;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ExchangeInsightsSlotOverlay slotOverlay;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ApiClient api;

	private volatile boolean scanning = false;

	// Browser device-link flow: true while a link approval is pending.
	private volatile boolean linking = false;

	// Alert infoboxes (client-thread state): the boxes shown and a lookup from each
	// box's right-click menu entry to its action (Clear / Open).
	private BufferedImage alertIcon;
	private final List<AlertInfoBox> alertBoxes = new ArrayList<>();
	private final Map<Object, Runnable> alertBoxActions = new HashMap<>();

	// Identity sync: which character this client is logged into, reported once per
	// login (RSN isn't available the instant LOGGED_IN fires, so the send is armed
	// there and completed on the first tick that has it).
	private boolean identityPending = false;
	private long identitySentHash = -1;

	// Live offer book: set on any offer change (incl. the login re-broadcast burst),
	// drained once per tick so a burst produces a single full-book push.
	private volatile boolean offersDirty = false;

	// GE offer info: quote for the item currently selected in the offer window.
	// Written by the fetch callback (HTTP thread), read on the client thread when
	// injecting the offer text - hence volatile. currentGeItem gates display to a
	// live match so a stale quote never renders against the wrong item.
	static final class GeQuote
	{
		final int itemId;
		final String itemName;
		final Long high;
		final Long low;
		final Double margin; // plain item margin (spread after tax)
		final Double roi;
		final Integer geLimit;
		final Double flipMargin; // premium: quant-adjusted flip net margin (null otherwise)
		final Double flipRoi;

		GeQuote(int itemId, String itemName, Long high, Long low, Double margin, Double roi, Integer geLimit, Double flipMargin, Double flipRoi)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.high = high;
			this.low = low;
			this.margin = margin;
			this.roi = roi;
			this.geLimit = geLimit;
			this.flipMargin = flipMargin;
			this.flipRoi = flipRoi;
		}
	}

	private static final int QUOTE_REFRESH_MS = 30_000;
	private volatile GeQuote geQuote;
	private volatile int currentGeItem = -1;
	private volatile boolean quoteInFlight = false;
	private long quoteFetchedAt = 0;
	private int quoteFetchedItem = -1;

	// Slot age badges: market quotes for the items in the player's active offers,
	// refreshed while the GE offers screen is open. Written from HTTP callbacks,
	// read by the overlay on the render thread.
	private final Map<Integer, GeQuote> slotQuotes = new ConcurrentHashMap<>();
	private final Map<Integer, Long> slotQuoteFetchedAt = new ConcurrentHashMap<>();

	// Clickable Exchange Insights icon on the offer setup/details body: opens the
	// item's margins page in the browser. Custom sprite registered under a
	// negative id (the widget engine resolves those from the overrides map).
	private static final int EI_SPRITE_ID = -20260706;
	private Widget geLinkButton;
	private volatile int geLinkItemId = -1;

	// Offer-info memo: the block is rebuilt (string formatting, regex tag-strip,
	// font measurement) only when an input actually changed; per tick the work is
	// a handful of reads and equality checks. The widget writes themselves were
	// always change-guarded - this trims the redundant recomputation feeding them.
	private GeQuote lastInjQuote;
	private int lastInjPrice = -1;
	private boolean lastInjSell;
	private int lastInjTraded = -1;
	private Integer lastInjLimit;
	private String lastInjDesired;
	private int lastInjFeeLineIdx = -1;
	private int lastInjFeeLineWidth;

	// Slot-title memo: cached verdict strings so the steady state does no string
	// building (the title widget is re-found each tick - see updateSlotTitles).
	private final Long[] lastSlotGap = new Long[8];
	private final boolean[] lastSlotBuy = new boolean[8];
	private final String[] lastSlotDesired = new String[8];

	@Provides
	ExchangeInsightsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ExchangeInsightsConfig.class);
	}

	@Override
	protected void startUp()
	{
		identityPending = client.getGameState() == GameState.LOGGED_IN;
		identitySentHash = -1;

		// Shared icon: the alert infoboxes and the clickable margins-link sprite in
		// the GE offer body (the plugin has no sidebar panel - config lives in the
		// RuneLite plugin settings).
		final BufferedImage icon = ImageUtil.loadImageResource(ExchangeInsightsPlugin.class, "panel_icon.png");
		alertIcon = icon;
		clientThread.invoke(() -> client.getSpriteOverrides().put(EI_SPRITE_ID, ImageUtil.getImageSpritePixels(icon, client)));
		overlayManager.add(slotOverlay);

		log.debug("Exchange Insights started (configured={})", api.isConfigured());
	}

	@Override
	protected void shutDown()
	{
		identityPending = false;
		identitySentHash = -1;
		linking = false;
		geQuote = null;
		currentGeItem = -1;
		slotQuotes.clear();
		slotQuoteFetchedAt.clear();
		clientThread.invoke(() ->
		{
			for (final AlertInfoBox box : new ArrayList<>(alertBoxes))
			{
				infoBoxManager.removeInfoBox(box);
			}
			alertBoxes.clear();
			alertBoxActions.clear();
		});
		overlayManager.remove(slotOverlay);
		final Widget button = geLinkButton;
		geLinkButton = null;
		geLinkItemId = -1;
		clientThread.invoke(() ->
		{
			client.getSpriteOverrides().remove(EI_SPRITE_ID);
			if (button != null)
			{
				try
				{
					button.setHidden(true);
				}
				catch (RuntimeException ignored)
				{
					// stale widget from a rebuilt interface - already gone
				}
			}
		});
	}

	/**
	 * The "Link account in browser" config item acts as a button: ticking it kicks
	 * off the device-link flow and immediately resets the tick. RuneLite config has
	 * no real button, so this is the standard toggle-as-action pattern.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (ExchangeInsightsConfig.GROUP.equals(event.getGroup())
			&& "linkAccount".equals(event.getKey())
			&& "true".equals(event.getNewValue()))
		{
			configManager.setConfiguration(ExchangeInsightsConfig.GROUP, "linkAccount", false);
			startAccountLink();
		}
	}

	/** Chat + notification status line for the link flow (there's no panel). */
	private void linkStatus(String message)
	{
		notifier.notify("Exchange Insights: " + message);
		clientThread.invokeLater(() ->
			client.addChatMessage(ChatMessageType.CONSOLE, "", "<col=b8860b>[Exchange Insights]</col> " + message, null));
	}

	/**
	 * One-click account link: mint a code pair on the dashboard, open the browser to
	 * approve it, and poll until the token arrives - then store it in config, which
	 * is all it takes to be linked. Status is reported in the game chat.
	 */
	private void startAccountLink()
	{
		if (linking)
		{
			return;
		}
		if (api.isConfigured())
		{
			linkStatus("Already linked - clear the Plugin token first if you want to re-link.");
			return;
		}
		if (!api.hasUrl())
		{
			linkStatus("Set the Dashboard URL first, then tick Link account again.");
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN || client.getAccountHash() == -1)
		{
			linkStatus("Log into OSRS first, then tick Link account - it ties this character to your account.");
			return;
		}
		final long hash = client.getAccountHash();
		final Player local = client.getLocalPlayer();
		final String rsn = local == null ? null : local.getName();
		linking = true;
		linkStatus("Opening your browser - sign in and approve the link there…");
		api.startLink(hash, rsn, start ->
		{
			if (start == null || start.deviceSecret == null || start.verificationUrl == null)
			{
				linking = false;
				linkStatus("Couldn't reach the dashboard to start linking - check the URL and try again.");
				return;
			}
			LinkBrowser.browse(start.verificationUrl);
			scheduleLinkPoll(start.deviceSecret, start.expiresAt, Math.max(2, start.pollSeconds));
		});
	}

	private void scheduleLinkPoll(String deviceSecret, long expiresAt, int intervalSeconds)
	{
		if (!linking)
		{
			return;
		}
		if (Instant.now().getEpochSecond() > expiresAt)
		{
			linking = false;
			linkStatus("Link request expired - tick Link account to try again.");
			return;
		}
		executor.schedule(() -> api.pollLink(deviceSecret, res ->
		{
			if (!linking)
			{
				return;
			}
			if (res == null || res.status == null || "pending".equals(res.status))
			{
				scheduleLinkPoll(deviceSecret, expiresAt, intervalSeconds);
				return;
			}
			linking = false;
			if ("approved".equals(res.status) && res.token != null && !res.token.isEmpty())
			{
				// Storing the token is all it takes to be linked.
				configManager.setConfiguration(ExchangeInsightsConfig.GROUP, "token", res.token);
				linkStatus("Linked! Your account is connected.");
			}
			else
			{
				linkStatus("denied".equals(res.status)
					? "Link denied in the browser."
					: "Link request expired or was already used - tick Link account to try again.");
			}
		}), intervalSeconds, TimeUnit.SECONDS);
	}

	/**
	 * Left-click Collect to bank: deprioritise "Collect to inventory" on the GE
	 * Collect button so "Collect to bank" wins the default click. Scoped to that
	 * one widget; the inventory option stays available on right-click.
	 */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!config.leftClickCollectToBank())
		{
			return;
		}
		if (event.getActionParam1() != InterfaceID.GeOffers.COLLECTALL)
		{
			return;
		}
		final String option = event.getOption();
		if (option != null && option.toLowerCase().startsWith("collect to inv"))
		{
			event.getMenuEntry().setDeprioritized(true);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		final GameState state = event.getGameState();
		if (state != GameState.LOGGED_IN)
		{
			return;
		}
		identityPending = true;
		// Push the current book on login too, in case the client's re-broadcast is
		// missed; the game fires per-slot changes right after, which also flag it.
		if (config.sendOffers())
		{
			offersDirty = true;
		}
		if (config.datamineNewItems() && api.isConfigured())
		{
			maybeDatamine();
		}
	}

	/**
	 * Report which character this client is logged into (stable account hash + RSN)
	 * so the dashboard attaches it - and any alts - to the token's owner. The RSN
	 * isn't available the instant LOGGED_IN fires, so this completes on the first
	 * tick that has both; re-sent only when the account actually changes.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		updateGeQuote();
		injectGeOfferText();
		updateGeLinkButton();
		updateSlotQuotes();
		updateSlotTitles();
		flushOfferBook();

		if (!identityPending || !api.isConfigured())
		{
			return;
		}
		final long hash = client.getAccountHash();
		final Player local = client.getLocalPlayer();
		final String rsn = local == null ? null : local.getName();
		if (hash == -1 || rsn == null || rsn.isEmpty())
		{
			return;
		}
		identityPending = false;
		if (hash != identitySentHash)
		{
			identitySentHash = hash;
			api.sendIdentity(hash, rsn);
		}
	}

	/** Market quote for a slot-badge item, or null while it hasn't arrived. */
	GeQuote getSlotQuote(int itemId)
	{
		return slotQuotes.get(itemId);
	}

	/**
	 * How an active offer sits vs the market: the signed per-item price DELTA
	 * (your price minus the fill-relevant insta price). A sell 13.7M over
	 * insta-sell reads +13.7M; a bid 2M under insta-buy reads -2M. Whether that
	 * delta is good or bad depends on the side - see {@link #classifyAge}.
	 * Null when the offer isn't active or the quote isn't in yet.
	 */
	Long offerGap(GrandExchangeOffer o)
	{
		if (o == null)
		{
			return null;
		}
		final GrandExchangeOfferState st = o.getState();
		final GeQuote q = slotQuotes.get(o.getItemId());
		if (q == null)
		{
			return null;
		}
		if (st == GrandExchangeOfferState.BUYING)
		{
			return q.high == null ? null : (long) o.getPrice() - q.high;
		}
		if (st == GrandExchangeOfferState.SELLING)
		{
			return q.low == null ? null : (long) o.getPrice() - q.low;
		}
		return null;
	}

	/** +1 = ahead of the market (fills first), 0 = exactly AT market (queued
	 *  behind same-price offers - not actually filling), -1 = market moved past. */
	static int classifyAge(boolean buy, long delta)
	{
		if (delta == 0)
		{
			return 0;
		}
		if (buy)
		{
			return delta > 0 ? 1 : -1;
		}
		return delta < 0 ? 1 : -1;
	}

	/**
	 * Fold the age gap into each slot's Buy/Sell title ("Sell -14.774M" red,
	 * "Buy +1.34M" green). The title child is found by its text rather than a
	 * child index, so an interface revision can't silently break it; the game
	 * rewrites the text on rebuild, so this re-asserts every tick (equality
	 * guard makes the steady state free).
	 */
	private void updateSlotTitles()
	{
		if (!config.geSlotBadges() || !api.hasUrl())
		{
			return;
		}
		// Titles only exist while the offers index is on screen.
		final Widget index = client.getWidget(InterfaceID.GeOffers.INDEX);
		if (index == null || index.isHidden())
		{
			return;
		}
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return;
		}
		for (int i = 0; i < offers.length && i < 8; i++)
		{
			final Long gap = offerGap(offers[i]);
			if (gap == null)
			{
				lastSlotGap[i] = null;
				lastSlotDesired[i] = null;
				continue;
			}
			final Widget slot = client.getWidget(InterfaceID.GeOffers.INDEX_0 + i);
			if (slot == null || slot.isHidden())
			{
				continue;
			}
			final boolean buy = offers[i].getState() == GrandExchangeOfferState.BUYING;
			final String base = buy ? "Buy" : "Sell";
			// Rebuild the verdict string only when the gap or side moved.
			if (lastSlotDesired[i] == null || !gap.equals(lastSlotGap[i]) || buy != lastSlotBuy[i])
			{
				lastSlotGap[i] = gap;
				lastSlotBuy[i] = buy;
				// Sign is the raw price delta vs the market; colour carries the verdict
				// (a +N sell is red: asking N over what sells). Exactly 0 = at market
				// but queued behind same-price offers, so it gets its own amber state.
				final int cls = classifyAge(buy, gap);
				final String col = cls > 0 ? COL_UP : cls < 0 ? COL_DOWN : COL_WARN;
				final int clamped = (int) Math.min(Integer.MAX_VALUE, Math.abs(gap));
				final String amount = gap == 0 ? "at market"
					: (gap > 0 ? "+" : "-") + net.runelite.client.util.QuantityFormatter.quantityToRSDecimalStack(clamped, true);
				lastSlotDesired[i] = base + " <col=" + col + ">" + amount + "</col>";
			}
			// Find the title child fresh each tick: `slot` is re-fetched per tick so
			// its children are always live. (Caching the child across ticks breaks
			// on an interface rebuild - the stale widget still reads back our text,
			// so writes land on a detached widget and the badge silently vanishes.)
			final Widget title = findSlotTitle(slot, base);
			if (title != null && !lastSlotDesired[i].equals(title.getText()))
			{
				title.setText(lastSlotDesired[i]);
			}
		}
	}

	/** The slot's title text child: the widget whose text is (or starts with) the
	 *  vanilla Buy/Sell label - including one we already rewrote. */
	private static Widget findSlotTitle(Widget slot, String base)
	{
		final Widget[][] groups = { slot.getStaticChildren(), slot.getDynamicChildren(), slot.getNestedChildren() };
		for (final Widget[] group : groups)
		{
			if (group == null)
			{
				continue;
			}
			for (final Widget c : group)
			{
				if (c == null)
				{
					continue;
				}
				final String t = c.getText();
				if (t != null && (t.equals(base) || t.startsWith(base + " ")))
				{
					return c;
				}
			}
		}
		return null;
	}

	/**
	 * Keep fresh quotes for every item in the player's ACTIVE offers while the GE
	 * offers screen is open (the only time the slot badges render). One request
	 * per distinct item per 30s, so a full board costs at most 8 requests each
	 * refresh window.
	 */
	private void updateSlotQuotes()
	{
		if ((!config.geSlotBadges() && !config.geOverlay()) || !api.hasUrl())
		{
			return;
		}
		// Refresh while either GE view that consumes these quotes is open: the
		// offers index (slot badges) or an offer's details screen (offer info).
		final Widget index = client.getWidget(InterfaceID.GeOffers.INDEX);
		final Widget details = client.getWidget(InterfaceID.GeOffers.DETAILS_DESC);
		final boolean indexOpen = index != null && !index.isHidden();
		final boolean detailsOpen = details != null && !details.isHidden();
		if (!indexOpen && !detailsOpen)
		{
			return;
		}
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return;
		}
		final long nowMs = System.currentTimeMillis();
		for (final GrandExchangeOffer o : offers)
		{
			if (o == null)
			{
				continue;
			}
			// Any occupied slot: active offers feed the badges; completed/cancelled
			// ones are still viewable on the details screen.
			if (o.getState() == GrandExchangeOfferState.EMPTY || o.getItemId() <= 0)
			{
				continue;
			}
			final int id = o.getItemId();
			final Long last = slotQuoteFetchedAt.get(id);
			if (last != null && nowMs - last < QUOTE_REFRESH_MS)
			{
				continue;
			}
			slotQuoteFetchedAt.put(id, nowMs);
			// Slot badges show the price gap, not the margin - no flip needed here.
			api.fetchQuote(id, false, quote ->
			{
				if (quote != null && quote.price != null)
				{
					slotQuotes.put(id, new GeQuote(id, "", quote.price.high, quote.price.low, quote.margin, quote.roi, null, null, null));
				}
			});
		}
	}

	/** The quote for the item currently selected in the offer window, or null when
	 *  none is selected (or the cached quote is for a different item). */
	private GeQuote activeGeQuote()
	{
		final GeQuote q = geQuote;
		return q != null && q.itemId == currentGeItem ? q : null;
	}

	private static final String COL_VALUE = "ffffff";
	private static final String COL_LABEL = "b8860b";
	private static final String COL_MUTED = "9f9f9f";
	private static final String COL_UP = "6cc071";
	private static final String COL_DOWN = "d46262";
	private static final String COL_WARN = "e0c055";
	// Per-item price being typed in the offer setup. No named constant in the API,
	// but it sits with its documented siblings: 4396 = setup quantity,
	// 4397 = Varbits.GE_OFFER_CREATION_TYPE, 4398 = setup price.
	private static final int VARBIT_GE_SETUP_PRICE = 4398;
	// Which slot the offer DETAILS screen is showing (1-based, 0 = none). Also
	// unnamed in gameval; viewedOffer() validates it against the real offer array
	// before trusting it, with an item-id fallback if it reads garbage.
	private static final int VARBIT_GE_VIEWED_SLOT = 4439;

	/**
	 * Write the live economics into the GE offer setup's item description — the
	 * same widget the game writes "Buy limit / Actively traded price" into (and
	 * that RuneLite's own GE plugin appends to, so this is a supported pattern).
	 * The game rewrites that text whenever the selected item changes, wiping our
	 * block, so it's re-asserted every tick; the string comparison makes the
	 * steady-state a no-op.
	 */
	private void injectGeOfferText()
	{
		if (!config.geOverlay())
		{
			return;
		}
		// New-offer setup screen: priced at whatever the user is entering (updates
		// every tick as they type or press the +/-% buttons).
		final Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP_DESC);
		if (setup != null && !setup.isHidden())
		{
			final GeQuote q = activeGeQuote();
			if (q != null)
			{
				final int offerPrice = client.getVarbitValue(VARBIT_GE_SETUP_PRICE);
				final boolean sellOffer = client.getVarbitValue(Varbits.GE_OFFER_CREATION_TYPE) == 1;
				injectInfo(setup, InterfaceID.GeOffers.SETUP_FEE, q, offerPrice, sellOffer);
			}
			return;
		}
		// Existing-offer details screen: the same block, priced at the OFFER's own
		// price - so an aged offer shows its real margin and how far off market it is.
		final Widget details = client.getWidget(InterfaceID.GeOffers.DETAILS_DESC);
		if (details != null && !details.isHidden())
		{
			final GrandExchangeOffer o = viewedOffer();
			if (o == null)
			{
				return;
			}
			GeQuote q = getSlotQuote(o.getItemId());
			if (q == null)
			{
				final GeQuote active = activeGeQuote();
				if (active != null && active.itemId == o.getItemId())
				{
					q = active;
				}
			}
			if (q == null)
			{
				return; // fetch is in flight (updateSlotQuotes covers the details view)
			}
			final GrandExchangeOfferState st = o.getState();
			final boolean sell = st == GrandExchangeOfferState.SELLING
				|| st == GrandExchangeOfferState.SOLD
				|| st == GrandExchangeOfferState.CANCELLED_SELL;
			injectInfo(details, InterfaceID.GeOffers.DETAILS_FEE, q, o.getPrice(), sell);
		}
	}

	/**
	 * The offer shown on the details screen. The viewed-slot varbit has no gameval
	 * name, so its value is trusted only after validating it points at a real
	 * offer; otherwise fall back to matching the details item id from the setup
	 * varp (ambiguous only when two slots hold the same item - first match wins).
	 */
	private GrandExchangeOffer viewedOffer()
	{
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return null;
		}
		final int v = client.getVarbitValue(VARBIT_GE_VIEWED_SLOT);
		if (v >= 1 && v <= offers.length)
		{
			final GrandExchangeOffer o = offers[v - 1];
			if (o != null && o.getItemId() > 0 && o.getState() != GrandExchangeOfferState.EMPTY)
			{
				return o;
			}
		}
		final int itemId = client.getVarpValue(VarPlayer.CURRENT_GE_ITEM);
		if (itemId <= 0)
		{
			return null;
		}
		for (final GrandExchangeOffer o : offers)
		{
			if (o != null && o.getItemId() == itemId && o.getState() != GrandExchangeOfferState.EMPTY)
			{
				return o;
			}
		}
		return null;
	}

	/**
	 * A small clickable Exchange Insights icon in the offer setup/details body that
	 * opens the item's margins page in the browser (works signed-out - the item
	 * detail is public). The client wipes dynamic children whenever the interface
	 * rebuilds, so the button is recreated when it's no longer among its parent's
	 * children; the click reads the CURRENT item so a stale capture can't misfire.
	 */
	private void updateGeLinkButton()
	{
		Widget desc = null;
		int itemId = -1;
		if (config.geOverlay() && api.hasUrl())
		{
			final Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP_DESC);
			final Widget details = client.getWidget(InterfaceID.GeOffers.DETAILS_DESC);
			if (setup != null && !setup.isHidden())
			{
				desc = setup;
				itemId = client.getVarpValue(VarPlayer.CURRENT_GE_ITEM);
			}
			else if (details != null && !details.isHidden())
			{
				desc = details;
				final GrandExchangeOffer o = viewedOffer();
				itemId = o != null ? o.getItemId() : -1;
			}
		}
		if (desc == null || itemId <= 0)
		{
			geLinkItemId = -1;
			if (geLinkButton != null)
			{
				try
				{
					geLinkButton.setHidden(true);
				}
				catch (RuntimeException ex)
				{
					geLinkButton = null; // interface rebuilt from under us
				}
			}
			return;
		}
		geLinkItemId = itemId;
		final Widget parent = desc.getParent();
		if (parent == null)
		{
			return;
		}
		boolean present = false;
		if (geLinkButton != null)
		{
			final Widget[] dyn = parent.getDynamicChildren();
			if (dyn != null)
			{
				for (final Widget d : dyn)
				{
					if (d == geLinkButton)
					{
						present = true;
						break;
					}
				}
			}
		}
		if (!present)
		{
			geLinkButton = parent.createChild(-1, WidgetType.GRAPHIC);
			geLinkButton.setSpriteId(EI_SPRITE_ID);
			geLinkButton.setOriginalWidth(20);
			geLinkButton.setOriginalHeight(20);
			geLinkButton.setHasListener(true);
			geLinkButton.setAction(0, "Exchange Insights margins");
			geLinkButton.setOnOpListener((JavaScriptCallback) ev -> openMarginsLink());
		}
		geLinkButton.setHidden(false);
		// Bottom-right inside the description box, clear of the text lines.
		final int x = desc.getOriginalX() + desc.getWidth() - 22;
		final int y = desc.getOriginalY() + desc.getHeight() - 20;
		if (geLinkButton.getOriginalX() != x || geLinkButton.getOriginalY() != y)
		{
			geLinkButton.setOriginalX(x);
			geLinkButton.setOriginalY(y);
			geLinkButton.revalidate();
		}
	}

	private void openMarginsLink()
	{
		final int id = geLinkItemId;
		if (id > 0 && api.hasUrl())
		{
			LinkBrowser.browse(config.baseUrl().trim().replaceAll("/+$", "") + "/#margins?item=" + id);
		}
	}

	/** Shared injection for the setup and details screens: rebuild the description
	 *  text and re-seat that screen's fee icon. */
	private void injectInfo(Widget desc, int feeComponentId, GeQuote q, int offerPrice, boolean sellOffer)
	{
		final String text = desc.getText();
		if (text == null || text.isEmpty())
		{
			return;
		}
		// Same Jagex "actively traded" feed the game (and the core GE plugin) shows.
		final int activelyTraded = itemManager.getItemPriceWithSource(q.itemId, true);
		// Buy limit: our catalogue first; RuneLite's bundled item stats fill the
		// gaps (the wiki data behind our catalogue misses limits on some items).
		Integer geLimit = q.geLimit;
		if (geLimit == null || geLimit <= 0)
		{
			final net.runelite.client.game.ItemStats stats = itemManager.getItemStats(q.itemId);
			if (stats != null && stats.getGeLimit() > 0)
			{
				geLimit = stats.getGeLimit();
			}
		}
		// Rebuild only when an input moved; a quote refresh is a new GeQuote object,
		// so reference comparison catches it.
		if (lastInjDesired == null || q != lastInjQuote || offerPrice != lastInjPrice || sellOffer != lastInjSell
			|| activelyTraded != lastInjTraded || !java.util.Objects.equals(geLimit, lastInjLimit))
		{
			lastInjQuote = q;
			lastInjPrice = offerPrice;
			lastInjSell = sellOffer;
			lastInjTraded = activelyTraded;
			lastInjLimit = geLimit;
			lastInjDesired = buildGeInfoText(q, offerPrice, sellOffer, activelyTraded, geLimit);
			// Cache the fee icon's anchor metrics (tag-strip + font measurement)
			// alongside the text they belong to.
			lastInjFeeLineIdx = -1;
			final String[] lines = lastInjDesired.split("<br>");
			for (int i = 0; i < lines.length; i++)
			{
				if (lines[i].contains("after tax"))
				{
					lastInjFeeLineIdx = i;
					final String plain = lines[i].replaceAll("<[^>]*>", "");
					final net.runelite.api.FontTypeFace font = desc.getFont();
					lastInjFeeLineWidth = font != null ? font.getTextWidth(plain) : plain.length() * 6;
					break;
				}
			}
		}
		// The game rewrites this text on interface rebuilds, so the widget read +
		// equality check must stay per-tick; the write only fires on a change.
		if (!lastInjDesired.equals(text))
		{
			desc.setText(lastInjDesired);
		}
		positionFeeIcon(desc, feeComponentId);
	}

	/**
	 * The sell setup parks a convenience-fee "(i)" info icon where the vanilla fee
	 * text used to sit; with the text rebuilt it would float mid-line. Instead of
	 * hiding it, re-seat it just after our "… after tax" note - the very text it
	 * explains. The icon and the description are siblings in the setup layer, so
	 * they share a coordinate space; the game re-creates the icon at its scripted
	 * spot on each new offer, so this re-runs every tick (no-op once placed).
	 */
	private void positionFeeIcon(Widget desc, int feeComponentId)
	{
		final Widget fee = client.getWidget(feeComponentId);
		if (fee == null)
		{
			return;
		}
		// Anchor metrics (line index + measured text width) are cached by
		// injectInfo when the block is rebuilt - nothing to recompute here.
		if (lastInjFeeLineIdx < 0)
		{
			// No tax note to annotate (e.g. one-sided market) - don't leave it floating.
			if (!fee.isHidden())
			{
				fee.setHidden(true);
			}
			return;
		}
		int lineHeight = desc.getLineHeight();
		if (lineHeight <= 0)
		{
			final net.runelite.api.FontTypeFace font = desc.getFont();
			lineHeight = font != null ? font.getBaseline() : 13;
		}
		if (fee.isHidden())
		{
			fee.setHidden(false);
		}
		// Place by CANVAS bounds delta: the icon and the text may not share a
		// parent origin, so absolute original-coords can land a line off. Instead
		// compare where the icon actually rendered with where it should sit on
		// screen and nudge by the difference - parent-agnostic, converges to a
		// stable no-op within a tick, and self-corrects if a script moves it back.
		final java.awt.Rectangle db = desc.getBounds();
		final java.awt.Rectangle fb = fee.getBounds();
		if (db == null || fb == null || db.width <= 0 || fb.width <= 0)
		{
			return; // not laid out yet; try again next tick
		}
		// The fee widget is an oversized (~40x40) click box with the small (i)
		// sprite drawn CENTRED inside it - align the box's centre to the text, not
		// its corner: sprite centre = a half-glyph past the text end, on the
		// line's vertical midline.
		final int spriteHalf = 8;
		final int centerX = db.x + lastInjFeeLineWidth + 4 + spriteHalf;
		final int centerY = db.y + lastInjFeeLineIdx * lineHeight + lineHeight / 2;
		final int targetX = Math.min(centerX - fb.width / 2, db.x + db.width - fb.width / 2 - spriteHalf);
		final int targetY = centerY - fb.height / 2;
		final int dx = targetX - fb.x;
		final int dy = targetY - fb.y;
		if (dx != 0 || dy != 0)
		{
			fee.setOriginalX(fee.getOriginalX() + dx);
			fee.setOriginalY(fee.getOriginalY() + dy);
			fee.revalidate();
		}
	}

	private static String buildGeInfoText(GeQuote q, int offerPrice, boolean sellOffer, int activelyTraded, Integer geLimit)
	{
		// The whole box is rebuilt from scratch - the flavour examine text is
		// traded for market data (the box is fixed-height; both don't fit), and the
		// buy-limit/actively-traded line is generated here so it shows whether or
		// not the core GE plugin is enabled. (The core plugin only swaps the text
		// once, via the geBuy/SellExamineText script callback at build time; this
		// per-tick rewrite replaces it a tick later, so the two never duplicate.)
		// Exact amounts, compact labels: the box wraps at roughly the width of the
		// vanilla info line, and two max-cash prices with long labels would spill
		// onto an extra line.
		final StringBuilder sb = new StringBuilder();
		if (geLimit != null && geLimit > 0)
		{
			sb.append("<col=").append(COL_LABEL).append(">Buy limit</col> ")
				.append("<col=").append(COL_VALUE).append('>').append(String.format("%,d", geLimit)).append("</col>");
		}
		if (activelyTraded > 0)
		{
			sb.append(sb.length() > 0
				? " <col=" + COL_LABEL + ">· actively traded</col> "
				: "<col=" + COL_LABEL + ">Actively traded</col> ");
			sb.append("<col=").append(COL_VALUE).append('>').append(String.format("%,d", activelyTraded)).append("</col>");
		}
		if (sb.length() > 0)
		{
			sb.append("<br>");
		}
		sb.append("<col=").append(COL_LABEL).append(">Insta-buy</col> ");
		sb.append(gp(q.high));
		sb.append(" <col=").append(COL_LABEL).append(">· sell</col> ").append(gp(q.low));
		sb.append("<br>");
		// Premium users see the Flip Finder's quant-adjusted net margin ("Flip
		// margin"); everyone else sees the plain quote spread ("Item margin"). The
		// server only returns the flip figure for a premium token.
		final boolean useFlip = q.flipMargin != null;
		final Double marginVal = useFlip ? q.flipMargin : q.margin;
		final Double roiVal = useFlip ? q.flipRoi : q.roi;
		if (marginVal != null)
		{
			final String col = marginVal >= 0 ? COL_UP : COL_DOWN;
			sb.append("<col=").append(COL_LABEL).append('>').append(useFlip ? "Flip margin" : "Item margin").append("</col> ");
			sb.append("<col=").append(col).append('>').append(gpSigned(Math.round(marginVal)));
			if (roiVal != null)
			{
				sb.append(String.format(" (%+.1f%%)", roiVal * 100));
			}
			sb.append("</col> <col=").append(COL_MUTED).append(">after tax</col>");
		}
		else
		{
			sb.append("<col=").append(COL_MUTED).append(">No two-sided market data</col>");
		}
		// Grade the entered price against the market - always shown (the default
		// price deserves a verdict too), live-updated as the user adjusts it.
		final String bidLine = offerPrice > 0
			? buildBidLine(offerPrice, sellOffer, q.high, q.low)
			: null;
		if (bidLine != null)
		{
			sb.append("<br>").append(bidLine);
		}
		return sb.toString();
	}

	/**
	 * Grade the user's entered price against the live market: how far off the
	 * relevant insta price it sits, and what that means for fill speed.
	 * Buy at/over insta-buy or sell at/under insta-sell fills instantly; between
	 * the two you queue; past the far side you're unlikely to fill soon.
	 * Returns null when the quote can't support a verdict (one-sided market).
	 */
	private static String buildBidLine(long p, boolean sell, Long high, Long low)
	{
		final String who = sell ? "Your ask" : "Your bid";
		final String rel;
		final String relCol;
		final String verdict;
		final String verdictCol;
		if (!sell)
		{
			if (high != null && p >= high)
			{
				rel = pct(p, high) + " vs insta-buy";
				relCol = p > high ? COL_DOWN : COL_VALUE; // paying a premium reads red
				verdict = "instant fill";
				verdictCol = COL_UP;
			}
			else if (low != null && p < low)
			{
				rel = pct(p, low) + " vs insta-sell";
				relCol = COL_UP; // bargain bid...
				verdict = "may not fill";
				verdictCol = COL_DOWN; // ...but slow
			}
			else if (high != null && low != null)
			{
				rel = "inside spread";
				relCol = COL_VALUE;
				verdict = "queued";
				verdictCol = COL_WARN;
			}
			else
			{
				return null;
			}
		}
		else
		{
			if (low != null && p <= low)
			{
				rel = pct(p, low) + " vs insta-sell";
				relCol = p < low ? COL_DOWN : COL_VALUE; // underselling reads red
				verdict = "instant sale";
				verdictCol = COL_UP;
			}
			else if (high != null && p > high)
			{
				rel = pct(p, high) + " vs insta-buy";
				relCol = COL_UP; // premium ask...
				verdict = "may not sell";
				verdictCol = COL_DOWN; // ...but slow
			}
			else if (high != null && low != null)
			{
				rel = "inside spread";
				relCol = COL_VALUE;
				verdict = "queued";
				verdictCol = COL_WARN;
			}
			else
			{
				return null;
			}
		}
		return "<col=" + COL_LABEL + ">" + who + "</col> <col=" + relCol + ">" + rel
			+ "</col> · <col=" + verdictCol + ">" + verdict + "</col>";
	}

	private static String pct(long p, long ref)
	{
		return String.format("%+.1f%%", (p - ref) * 100.0 / ref);
	}

	/** Exact white gp amount with thousands separators; "?" when one-sided. */
	private static String gp(Long v)
	{
		if (v == null)
		{
			return "<col=" + COL_MUTED + ">?</col>";
		}
		return "<col=" + COL_VALUE + ">" + String.format("%,d", v) + "</col>";
	}

	private static String gpSigned(long v)
	{
		return (v < 0 ? "-" : "+") + String.format("%,d", Math.abs(v)) + " gp";
	}

	/**
	 * Track the item selected in the GE offer window and keep a fresh public quote
	 * for it. Anonymous by design: needs only the dashboard URL (default), reads
	 * public market data, and sends nothing about the player.
	 */
	private void updateGeQuote()
	{
		final int itemId = client.getVarpValue(VarPlayer.CURRENT_GE_ITEM);
		currentGeItem = itemId;
		if (itemId <= 0)
		{
			return; // keep the stale quote around: re-selecting the item shows it instantly
		}
		if (!config.geOverlay() || !api.hasUrl() || quoteInFlight)
		{
			return;
		}
		final long nowMs = System.currentTimeMillis();
		if (itemId == quoteFetchedItem && nowMs - quoteFetchedAt < QUOTE_REFRESH_MS)
		{
			return;
		}
		quoteFetchedItem = itemId;
		quoteFetchedAt = nowMs;
		quoteInFlight = true;
		// Item name from the client cache (we're on the client thread here).
		String name;
		try
		{
			name = itemManager.getItemComposition(itemId).getName();
		}
		catch (RuntimeException ex)
		{
			name = "Item " + itemId;
		}
		final String itemName = name;
		// Ask for the premium flip margin only for the item being offered (bounded
		// cost); the server returns it only for a premium token.
		api.fetchQuote(itemId, config.showFlipMargin(), quote ->
		{
			quoteInFlight = false;
			if (quote == null || quote.price == null)
			{
				return; // keep whatever we had; the next refresh window retries
			}
			geQuote = new GeQuote(
				itemId,
				quote.item != null && quote.item.name != null ? quote.item.name : itemName,
				quote.price.high,
				quote.price.low,
				quote.margin,
				quote.roi,
				quote.item != null ? quote.item.geLimit : null,
				quote.flip != null ? quote.flip.margin : null,
				quote.flip != null ? quote.flip.roi : null);
		});
	}

	/**
	 * Poll the dashboard for watchlist alerts routed to the RuneLite channel. The
	 * server hands each alert out exactly once, so polling is cheap and idempotent;
	 * only polled while logged in so alerts land where the player can see them.
	 */
	@Schedule(period = 30, unit = ChronoUnit.SECONDS, asynchronous = true)
	public void pollAlerts()
	{
		if (!config.inGameAlerts() || !api.isConfigured() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		api.fetchAlerts(alerts ->
		{
			final AlertDelivery delivery = config.alertDelivery();
			for (final ApiClient.Alert alert : alerts)
			{
				final String title = alert.title == null || alert.title.isEmpty() ? "Watchlist alert" : alert.title;
				final String body = alert.body == null ? "" : alert.body;
				final String message = body.isEmpty() ? title : title + " - " + body;
				if (delivery.notification())
				{
					notifier.notify("Exchange Insights: " + message);
				}
				if (delivery.chat())
				{
					clientThread.invokeLater(() ->
						client.addChatMessage(ChatMessageType.CONSOLE, "", "<col=b8860b>[Exchange Insights]</col> " + message, null));
				}
				// An actionable infobox always accompanies the alert - right-click to
				// Clear it or Open the subject in the browser. Infobox state is only
				// touched on the client thread (add here, click handler, cleanup).
				final String link = alert.link;
				clientThread.invokeLater(() -> addAlertInfoBox(title, message, link));
			}
		});
	}

	private static final int MAX_ALERT_BOXES = 6;

	/** Add an alert infobox (capped) and register its right-click actions. */
	private void addAlertInfoBox(String title, String message, String link)
	{
		final AlertInfoBox box = new AlertInfoBox(alertIcon, this, title, message, link);
		alertBoxes.add(box);
		alertBoxActions.put(box.clearEntry, () -> removeAlertInfoBox(box));
		if (box.openEntry != null)
		{
			alertBoxActions.put(box.openEntry, () -> LinkBrowser.browse(box.link));
		}
		infoBoxManager.addInfoBox(box);
		// Keep only the most recent few so a run of alerts can't stack up forever.
		while (alertBoxes.size() > MAX_ALERT_BOXES)
		{
			removeAlertInfoBox(alertBoxes.get(0));
		}
	}

	private void removeAlertInfoBox(AlertInfoBox box)
	{
		if (box == null || !alertBoxes.remove(box))
		{
			return;
		}
		alertBoxActions.remove(box.clearEntry);
		if (box.openEntry != null)
		{
			alertBoxActions.remove(box.openEntry);
		}
		infoBoxManager.removeInfoBox(box);
	}

	/** Route right-click actions on our alert infoboxes (Clear / Open). */
	@Subscribe
	public void onOverlayMenuClicked(OverlayMenuClicked event)
	{
		final Runnable action = alertBoxActions.get(event.getEntry());
		if (action != null)
		{
			action.run();
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		if (!api.isConfigured())
		{
			return;
		}
		// The login re-broadcast fires one event per slot in a burst; flag the book
		// dirty and push the whole 8-slot snapshot once on the next tick (coalesced).
		// The dashboard derives fills server-side from that snapshot.
		if (config.sendOffers())
		{
			offersDirty = true;
		}
	}

	/**
	 * Push the full GE offer book (all 8 slots) to the dashboard so its Grand
	 * Exchange widget mirrors the in-game GE. Sending every slot each time - EMPTY
	 * included - makes the server state self-healing: a missed event is corrected
	 * by the next snapshot. Coalesced to at most one send per tick.
	 */
	private void flushOfferBook()
	{
		if (!offersDirty)
		{
			return;
		}
		offersDirty = false;
		if (!config.sendOffers() || !api.isConfigured())
		{
			return;
		}
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return;
		}
		final List<ApiClient.OfferSlot> book = new ArrayList<>();
		for (int slot = 0; slot < offers.length && slot < GE_SLOTS; slot++)
		{
			final GrandExchangeOffer o = offers[slot];
			final GrandExchangeOfferState st = o == null ? GrandExchangeOfferState.EMPTY : o.getState();
			final ApiClient.OfferSlot s = new ApiClient.OfferSlot();
			s.slot = slot;
			s.state = st.name();
			if (o != null && st != GrandExchangeOfferState.EMPTY)
			{
				s.itemId = o.getItemId();
				s.price = o.getPrice();
				s.qty = o.getTotalQuantity();
				s.filledQty = o.getQuantitySold();
				s.spent = o.getSpent();
			}
			book.add(s);
		}
		api.sendOffers(book);
	}

	/**
	 * Trigger a datamine scan only when the game cache revision differs from the
	 * last one we scanned (or we've never scanned). Between updates nothing in the
	 * item cache changes, so there is nothing to find.
	 */
	private void maybeDatamine()
	{
		if (scanning)
		{
			return;
		}
		final int revision = client.getRevision();
		if (revision == getLastScanRevision())
		{
			return;
		}
		scanning = true;
		log.debug("Exchange Insights: cache revision {} — starting datamine scan", revision);
		scanChunk(0, new ArrayList<>(), 0, revision);
	}

	/**
	 * Probe one chunk of the item id space on the client thread, then re-schedule
	 * the next chunk. Collects tradeable named items (the investing-relevant
	 * universe) with a fingerprint of their salient fields; the server decides
	 * what's new or changed. Ends at the hard cap or after a long run of nameless
	 * ids past the floor (the end of the item table).
	 */
	private void scanChunk(int start, List<ApiClient.DatamineItem> acc, int namelessRun, int revision)
	{
		clientThread.invokeLater(() ->
		{
			final int end = Math.min(start + SCAN_CHUNK, SCAN_HARD_MAX);
			int run = namelessRun;
			for (int id = start; id < end; id++)
			{
				final ItemComposition comp;
				try
				{
					comp = itemManager.getItemComposition(id);
				}
				catch (RuntimeException ex)
				{
					run++;
					continue;
				}
				final String name = comp == null ? null : comp.getName();
				final boolean named = name != null && !name.isEmpty() && !name.equalsIgnoreCase("null");
				if (!named)
				{
					run++;
					continue;
				}
				run = 0;
				if (comp.isTradeable())
				{
					acc.add(new ApiClient.DatamineItem(id, name, true, comp.isMembers(), comp.getPrice(), comp.isStackable()));
				}
			}

			final boolean done = end >= SCAN_HARD_MAX || (start > SCAN_END_FLOOR && run >= SCAN_NULL_RUN_END);
			if (done)
			{
				flushDatamine(acc);
				setLastScanRevision(revision);
				scanning = false;
				log.debug("Exchange Insights: datamine scan complete — {} tradeable items reported", acc.size());
			}
			else
			{
				scanChunk(end, acc, run, revision);
			}
		});
	}

	private void flushDatamine(List<ApiClient.DatamineItem> all)
	{
		for (int i = 0; i < all.size(); i += SEND_BATCH)
		{
			api.sendDatamine(new ArrayList<>(all.subList(i, Math.min(i + SEND_BATCH, all.size()))));
		}
	}

	private int getLastScanRevision()
	{
		final String v = configManager.getConfiguration(ExchangeInsightsConfig.GROUP, KEY_LAST_REVISION);
		if (v == null)
		{
			return Integer.MIN_VALUE;
		}
		try
		{
			return Integer.parseInt(v);
		}
		catch (NumberFormatException ex)
		{
			return Integer.MIN_VALUE;
		}
	}

	private void setLastScanRevision(int revision)
	{
		configManager.setConfiguration(ExchangeInsightsConfig.GROUP, KEY_LAST_REVISION, Integer.toString(revision));
	}
}

/*
 * Copyright (c) 2026, Exchange Insights — BSD 2-Clause License (see LICENSE).
 */
package com.exchangeinsights;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
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
import net.runelite.api.gameval.InterfaceID;
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
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
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
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ExchangeInsightsSlotOverlay slotOverlay;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ApiClient api;

	private final FillTracker fillTracker = new FillTracker(GE_SLOTS);
	private volatile boolean scanning = false;

	// Identity sync: which character this client is logged into, reported once per
	// login (RSN isn't available the instant LOGGED_IN fires, so the send is armed
	// there and completed on the first tick that has it).
	private boolean identityPending = false;
	private long identitySentHash = -1;

	// Sidebar panel: session counters live here, the panel just renders them.
	private ExchangeInsightsPanel panel;
	private NavigationButton navButton;
	private int fillsSent = 0;
	private int alertsShown = 0;

	// Device-link flow: true while a browser approval is pending.
	private volatile boolean linking = false;

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
		final Double margin;
		final Double roi;
		final Integer geLimit;

		GeQuote(int itemId, String itemName, Long high, Long low, Double margin, Double roi, Integer geLimit)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.high = high;
			this.low = low;
			this.margin = margin;
			this.roi = roi;
			this.geLimit = geLimit;
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

	@Provides
	ExchangeInsightsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ExchangeInsightsConfig.class);
	}

	@Override
	protected void startUp()
	{
		fillTracker.reset();
		identityPending = client.getGameState() == GameState.LOGGED_IN;
		identitySentHash = -1;
		fillsSent = 0;
		alertsShown = 0;

		panel = new ExchangeInsightsPanel(this::testConnection, this::startAccountLink);
		final BufferedImage icon = ImageUtil.loadImageResource(ExchangeInsightsPlugin.class, "panel_icon.png");
		clientThread.invoke(() -> client.getSpriteOverrides().put(EI_SPRITE_ID, ImageUtil.getImageSpritePixels(icon, client)));
		navButton = NavigationButton.builder()
			.tooltip("Exchange Insights")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		overlayManager.add(slotOverlay);
		refreshConnectionStatus();

		log.debug("Exchange Insights started (configured={})", api.isConfigured());
	}

	@Override
	protected void shutDown()
	{
		fillTracker.reset();
		identityPending = false;
		identitySentHash = -1;
		linking = false;
		geQuote = null;
		currentGeItem = -1;
		slotQuotes.clear();
		slotQuoteFetchedAt.clear();
		overlayManager.remove(slotOverlay);
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		panel = null;
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

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (ExchangeInsightsConfig.GROUP.equals(event.getGroup()))
		{
			refreshConnectionStatus();
		}
	}

	/** Reflect the connection settings on the panel; pings when fully configured. */
	private void refreshConnectionStatus()
	{
		if (!api.isConfigured())
		{
			SwingUtilities.invokeLater(() ->
			{
				if (panel != null && !linking)
				{
					panel.setTesting(false);
					panel.setLinkVisible(true);
					panel.setLinkEnabled(true);
					panel.setStatus("Not linked - the GE overlay already works, but link your account for fills, alerts and flip tracking. Press Link account while logged into OSRS (or paste a token in the plugin settings).", ExchangeInsightsPanel.MUTED);
				}
			});
			return;
		}
		linking = false;
		SwingUtilities.invokeLater(() ->
		{
			if (panel != null)
			{
				panel.setLinkVisible(false);
			}
		});
		testConnection();
	}

	/**
	 * One-click account link: mint a code pair on the dashboard, open the user's
	 * browser to approve it, and poll until the token arrives - then store it in
	 * config, which flips the panel to Connected via the normal config-change path.
	 */
	private void startAccountLink()
	{
		if (linking || api.isConfigured())
		{
			return;
		}
		if (!api.hasUrl())
		{
			SwingUtilities.invokeLater(() -> panel.setStatus("Set the dashboard URL in the plugin settings first.", ExchangeInsightsPanel.ERR_RED));
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN || client.getAccountHash() == -1)
		{
			SwingUtilities.invokeLater(() -> panel.setStatus("Log into OSRS first, then press Link account - the link ties this character to your dashboard account.", ExchangeInsightsPanel.WARN_YELLOW));
			return;
		}
		final long hash = client.getAccountHash();
		final Player local = client.getLocalPlayer();
		final String rsn = local == null ? null : local.getName();
		linking = true;
		SwingUtilities.invokeLater(() ->
		{
			panel.setLinkEnabled(false);
			panel.setStatus("Opening your browser - sign in and approve the link there…", ExchangeInsightsPanel.WARN_YELLOW);
		});
		api.startLink(hash, rsn, start ->
		{
			if (start == null || start.deviceSecret == null || start.verificationUrl == null)
			{
				linking = false;
				SwingUtilities.invokeLater(() ->
				{
					if (panel != null)
					{
						panel.setLinkEnabled(true);
						panel.setStatus("Couldn't reach the dashboard to start linking - check the URL and try again.", ExchangeInsightsPanel.ERR_RED);
					}
				});
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
			SwingUtilities.invokeLater(() ->
			{
				if (panel != null)
				{
					panel.setLinkEnabled(true);
					panel.setStatus("Link request expired - press Link account to try again.", ExchangeInsightsPanel.ERR_RED);
				}
			});
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
				// Storing the token fires ConfigChanged → refreshConnectionStatus →
				// ping → the panel goes green. Nothing else to do here.
				configManager.setConfiguration(ExchangeInsightsConfig.GROUP, "token", res.token);
			}
			else
			{
				final String msg = "denied".equals(res.status)
					? "Link denied in the browser."
					: "Link request expired or was already used - press Link account to try again.";
				SwingUtilities.invokeLater(() ->
				{
					if (panel != null)
					{
						panel.setLinkEnabled(true);
						panel.setStatus(msg, ExchangeInsightsPanel.ERR_RED);
					}
				});
			}
		}), intervalSeconds, TimeUnit.SECONDS);
	}

	private void testConnection()
	{
		if (!api.isConfigured())
		{
			refreshConnectionStatus();
			return;
		}
		SwingUtilities.invokeLater(() ->
		{
			if (panel != null)
			{
				panel.setTesting(true);
				panel.setStatus("Checking connection…", ExchangeInsightsPanel.WARN_YELLOW);
			}
		});
		api.ping((status, handle) -> SwingUtilities.invokeLater(() ->
		{
			if (panel == null)
			{
				return;
			}
			panel.setTesting(false);
			switch (status)
			{
				case OK:
					panel.setStatus(handle != null && !handle.isEmpty() ? "Connected as @" + handle : "Connected", ExchangeInsightsPanel.OK_GREEN);
					break;
				case UNAUTHORIZED:
					panel.setStatus("Invalid or revoked token - generate a new one on the dashboard under Account settings.", ExchangeInsightsPanel.ERR_RED);
					break;
				default:
					panel.setStatus("Could not reach the dashboard - check the URL and your connection.", ExchangeInsightsPanel.ERR_RED);
					break;
			}
		}));
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		final GameState state = event.getGameState();
		if (state != GameState.LOGGED_IN)
		{
			// On logout/hop, forget slot state so the re-broadcast snapshot on the
			// next login re-baselines instead of replaying old fills as new.
			fillTracker.reset();
			return;
		}
		identityPending = true;
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
	 * How an active offer has aged: the signed per-item gp gap to the price that
	 * fills right now. Positive = still ahead of the market (bid at/over
	 * insta-buy, ask at/under insta-sell), negative = the market moved past it.
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
			return q.low == null ? null : q.low - o.getPrice();
		}
		return null;
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
				continue;
			}
			final Widget slot = client.getWidget(InterfaceID.GeOffers.INDEX_0 + i);
			if (slot == null || slot.isHidden())
			{
				continue;
			}
			final boolean buy = offers[i].getState() == GrandExchangeOfferState.BUYING;
			final String base = buy ? "Buy" : "Sell";
			final Widget title = findSlotTitle(slot, base);
			if (title == null)
			{
				continue;
			}
			final String col = gap >= 0 ? COL_UP : COL_DOWN;
			final int clamped = (int) Math.min(Integer.MAX_VALUE, Math.abs(gap));
			final String desired = base + " <col=" + col + ">" + (gap >= 0 ? "+" : "-")
				+ net.runelite.client.util.QuantityFormatter.quantityToRSDecimalStack(clamped, true) + "</col>";
			if (!desired.equals(title.getText()))
			{
				title.setText(desired);
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
			api.fetchQuote(id, quote ->
			{
				if (quote != null && quote.price != null)
				{
					slotQuotes.put(id, new GeQuote(id, "", quote.price.high, quote.price.low, quote.margin, quote.roi, null));
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
		final String desired = buildGeInfoText(q, offerPrice, sellOffer, activelyTraded, geLimit);
		if (!desired.equals(text))
		{
			desc.setText(desired);
		}
		positionFeeIcon(desc, feeComponentId, desired);
	}

	/**
	 * The sell setup parks a convenience-fee "(i)" info icon where the vanilla fee
	 * text used to sit; with the text rebuilt it would float mid-line. Instead of
	 * hiding it, re-seat it just after our "… after tax" note - the very text it
	 * explains. The icon and the description are siblings in the setup layer, so
	 * they share a coordinate space; the game re-creates the icon at its scripted
	 * spot on each new offer, so this re-runs every tick (no-op once placed).
	 */
	private void positionFeeIcon(Widget desc, int feeComponentId, String text)
	{
		final Widget fee = client.getWidget(feeComponentId);
		if (fee == null)
		{
			return;
		}
		final String[] lines = text.split("<br>");
		int lineIdx = -1;
		for (int i = 0; i < lines.length; i++)
		{
			if (lines[i].contains("after tax"))
			{
				lineIdx = i;
				break;
			}
		}
		if (lineIdx < 0)
		{
			// No tax note to annotate (e.g. one-sided market) - don't leave it floating.
			if (!fee.isHidden())
			{
				fee.setHidden(true);
			}
			return;
		}
		final String plain = lines[lineIdx].replaceAll("<[^>]*>", "");
		final net.runelite.api.FontTypeFace font = desc.getFont();
		int lineHeight = desc.getLineHeight();
		if (lineHeight <= 0)
		{
			lineHeight = font != null ? font.getBaseline() : 13;
		}
		final int textWidth = font != null ? font.getTextWidth(plain) : plain.length() * 6;
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
		log.debug("fee icon: desc={} fee={} lineIdx={} lh={} fontId={} textW={}", db, fb, lineIdx, lineHeight, desc.getFontId(), textWidth);
		// The fee widget is an oversized (~40x40) click box with the small (i)
		// sprite drawn CENTRED inside it - align the box's centre to the text, not
		// its corner: sprite centre = a half-glyph past the text end, on the
		// line's vertical midline.
		final int spriteHalf = 8;
		final int centerX = db.x + textWidth + 4 + spriteHalf;
		final int centerY = db.y + lineIdx * lineHeight + lineHeight / 2;
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
		if (q.margin != null)
		{
			// "Item margin" deliberately: this is the plain quote-spread margin (the
			// site's Item margins board). "Flip" is reserved for the quant-adjusted
			// Flip Finder economics, which this overlay does not show.
			final String col = q.margin >= 0 ? COL_UP : COL_DOWN;
			sb.append("<col=").append(COL_LABEL).append(">Item margin</col> ");
			sb.append("<col=").append(col).append('>').append(gpSigned(Math.round(q.margin)));
			if (q.roi != null)
			{
				sb.append(String.format(" (%+.1f%%)", q.roi * 100));
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
		api.fetchQuote(itemId, quote ->
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
				quote.item != null ? quote.item.geLimit : null);
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
			String lastMessage = null;
			for (final ApiClient.Alert alert : alerts)
			{
				final String title = alert.title == null || alert.title.isEmpty() ? "Watchlist alert" : alert.title;
				final String body = alert.body == null ? "" : alert.body;
				final String message = body.isEmpty() ? title : title + " - " + body;
				notifier.notify("Exchange Insights: " + message);
				clientThread.invokeLater(() ->
					client.addChatMessage(ChatMessageType.CONSOLE, "", "<col=b8860b>[Exchange Insights]</col> " + message, null));
				lastMessage = message;
			}
			alertsShown += alerts.size();
			final int count = alertsShown;
			final String last = lastMessage;
			SwingUtilities.invokeLater(() ->
			{
				if (panel != null)
				{
					panel.setAlerts(count, last);
				}
			});
		});
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		if (!api.isConfigured())
		{
			return;
		}
		final GrandExchangeOffer offer = event.getOffer();
		final int slot = event.getSlot();
		final GrandExchangeOfferState st = offer.getState();
		final boolean cleared = st == GrandExchangeOfferState.EMPTY;

		final int delta = fillTracker.observe(slot, cleared, offer.getQuantitySold());
		if (config.sendFills() && delta > 0)
		{
			final String type = isBuy(st) ? "buy" : "sell";
			// Listed price per item (pre-tax). The dashboard applies GE tax itself
			// so realized margins stay apples-to-apples with the modeled ones.
			final ApiClient.Fill fill = new ApiClient.Fill(
				offer.getItemId(), type, offer.getPrice(), delta, Instant.now().getEpochSecond());
			api.sendFills(Collections.singletonList(fill));

			// Session counter for the sidebar panel (this event runs on the client
			// thread, so the item-name lookup is safe here).
			String itemName;
			try
			{
				itemName = itemManager.getItemComposition(offer.getItemId()).getName();
			}
			catch (RuntimeException ex)
			{
				itemName = "item " + offer.getItemId();
			}
			final String desc = String.format("%s %,d × %s @ %,d gp",
				isBuy(st) ? "Bought" : "Sold", delta, itemName, offer.getPrice());
			final int count = ++fillsSent;
			SwingUtilities.invokeLater(() ->
			{
				if (panel != null)
				{
					panel.setFills(count, desc);
				}
			});
		}

		if (config.sendOffers())
		{
			final Map<String, Object> payload = new HashMap<>();
			payload.put("slot", slot);
			payload.put("state", st.name());
			payload.put("price", offer.getPrice());
			payload.put("totalQty", offer.getTotalQuantity());
			payload.put("qtySold", offer.getQuantitySold());
			payload.put("spent", offer.getSpent());
			api.sendEvents(Collections.singletonList(
				new ApiClient.Event("ge_offer", offer.getItemId(), payload, Instant.now().getEpochSecond())));
		}
	}

	private static boolean isBuy(GrandExchangeOfferState st)
	{
		return st == GrandExchangeOfferState.BUYING
			|| st == GrandExchangeOfferState.BOUGHT
			|| st == GrandExchangeOfferState.CANCELLED_BUY;
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

/*
 * Copyright (c) 2026, Exchange Insights — BSD 2-Clause License (see LICENSE).
 */
package com.exchangeinsights;

import com.google.inject.Provides;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;

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
	private ApiClient api;

	private final FillTracker fillTracker = new FillTracker(GE_SLOTS);
	private volatile boolean scanning = false;

	// Identity sync: which character this client is logged into, reported once per
	// login (RSN isn't available the instant LOGGED_IN fires, so the send is armed
	// there and completed on the first tick that has it).
	private boolean identityPending = false;
	private long identitySentHash = -1;

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
		log.debug("Exchange Insights started (configured={})", api.isConfigured());
	}

	@Override
	protected void shutDown()
	{
		fillTracker.reset();
		identityPending = false;
		identitySentHash = -1;
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
			for (final ApiClient.Alert alert : alerts)
			{
				final String title = alert.title == null || alert.title.isEmpty() ? "Watchlist alert" : alert.title;
				final String body = alert.body == null ? "" : alert.body;
				final String message = body.isEmpty() ? title : title + " - " + body;
				notifier.notify("Exchange Insights: " + message);
				clientThread.invokeLater(() ->
					client.addChatMessage(ChatMessageType.CONSOLE, "", "<col=b8860b>[Exchange Insights]</col> " + message, null));
			}
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

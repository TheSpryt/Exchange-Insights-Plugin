/*
 * Copyright (c) 2026, Exchange Insights — BSD 2-Clause License (see LICENSE).
 */
package com.exchangeinsights;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(ExchangeInsightsConfig.GROUP)
public interface ExchangeInsightsConfig extends Config
{
	String GROUP = "exchangeinsights";

	@ConfigSection(
		name = "Connection",
		description = "Where to send your data and how to authenticate.",
		position = 0
	)
	String connectionSection = "connection";

	@ConfigSection(
		name = "What to send",
		description = "Pick which streams to forward to your dashboard.",
		position = 1
	)
	String streamsSection = "streams";

	@ConfigItem(
		keyName = "baseUrl",
		name = "Dashboard URL",
		description = "Base URL of the Exchange Insights dashboard. Leave the default unless you self-host.",
		section = connectionSection,
		position = 0
	)
	default String baseUrl()
	{
		return "https://exchange-insights.gg";
	}

	@ConfigItem(
		keyName = "token",
		name = "Plugin token",
		description = "Your personal plugin token - generate it on the dashboard under Account settings. It links this client to your account; treat it like a password.",
		secret = true,
		section = connectionSection,
		position = 1
	)
	default String token()
	{
		return "";
	}

	@ConfigItem(
		keyName = "sendOffers",
		name = "Sync GE offers & trades",
		description = "Sync your live Grand Exchange offers to the dashboard's Grand Exchange board, and record every completed buy/sell into your verified trade history. Sends only your own GE activity.",
		section = streamsSection,
		position = 0
	)
	default boolean sendOffers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "datamineNewItems",
		name = "Datamine new items",
		description = "On login, scan a small forward window of item ids and report any newly-added named items (they often appear before the wiki). Bounded and best-effort.",
		section = streamsSection,
		position = 2
	)
	default boolean datamineNewItems()
	{
		return true;
	}

	@ConfigItem(
		keyName = "inGameAlerts",
		name = "In-game alerts",
		description = "Deliver your watchlist alerts (RuneLite channel) in game as a chat message and system notification.",
		section = streamsSection,
		position = 3
	)
	default boolean inGameAlerts()
	{
		return true;
	}

	@ConfigItem(
		keyName = "geOverlay",
		name = "GE offer info",
		description = "Add live insta-buy/insta-sell, after-tax item margin and buy limit to the item text in the Grand Exchange offer window. Reads public market data only - works without an account.",
		section = streamsSection,
		position = 4
	)
	default boolean geOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "geSlotBadges",
		name = "Offer age badges",
		description = "On the GE offers screen, badge each active slot with whether your price is still ahead of the market (green) or the market has moved past it (red, with how far behind). Public market data only.",
		section = streamsSection,
		position = 5
	)
	default boolean geSlotBadges()
	{
		return true;
	}

	@ConfigItem(
		keyName = "leftClickCollectToBank",
		name = "Left-click Collect to bank",
		description = "Make the Grand Exchange Collect button send everything to your bank on left click. Collect to inventory stays available on right-click.",
		section = streamsSection,
		position = 6
	)
	default boolean leftClickCollectToBank()
	{
		return false;
	}
}

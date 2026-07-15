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

	@ConfigSection(
		name = "Premium",
		description = "Features for Premium accounts (no effect on free accounts).",
		position = 2
	)
	String premiumSection = "premium";

	@ConfigItem(
		keyName = "token",
		name = "Plugin token",
		description = "Your personal plugin token - generate it on the dashboard under Account settings, or use 'Link account in browser' below. Treat it like a password. If you leave this empty and the Bank Templates plugin has a token configured, that one is used automatically.",
		secret = true,
		section = connectionSection,
		position = 1
	)
	default String token()
	{
		return "";
	}

	@ConfigItem(
		keyName = "linkAccount",
		name = "Link account in browser",
		description = "Tick this while logged into OSRS to link your account automatically: it opens the dashboard in your browser, you approve, and the plugin fills in the token for you. Resets itself once done.",
		section = connectionSection,
		position = 2
	)
	default boolean linkAccount()
	{
		return false;
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
		keyName = "geOverlay",
		name = "GE offer info",
		description = "Add live insta-buy/insta-sell, after-tax item margin and buy limit to the item text in the Grand Exchange offer window. Reads public market data only - works without an account.",
		section = streamsSection,
		position = 3
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
		position = 4
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
		position = 5
	)
	default boolean leftClickCollectToBank()
	{
		return false;
	}

	@ConfigItem(
		keyName = "inGameAlerts",
		name = "In-game alerts",
		description = "Deliver your watchlist alerts (a Premium feature) in game. Each alert also appears as an infobox you can right-click to Clear or Open in your browser.",
		section = premiumSection,
		position = 0
	)
	default boolean inGameAlerts()
	{
		return true;
	}

	@ConfigItem(
		keyName = "alertDelivery",
		name = "Alert delivery method",
		description = "How in-game alerts are announced: a game chat message, a system notification, or both.",
		section = premiumSection,
		position = 1
	)
	default AlertDelivery alertDelivery()
	{
		return AlertDelivery.BOTH;
	}

	@ConfigItem(
		keyName = "showFlipMargin",
		name = "Show quant flip margins",
		description = "Show the Flip Finder's quant-computed flip margin in the GE offer info instead of the plain item margin, and link the info icon to the item's flips (not margins). Premium accounts only.",
		section = premiumSection,
		position = 2
	)
	default boolean showFlipMargin()
	{
		return true;
	}
}

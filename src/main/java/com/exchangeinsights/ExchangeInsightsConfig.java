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
		description = "Base URL of your Exchange Insights service, e.g. https://exchange-insights.you.workers.dev (no trailing slash needed).",
		section = connectionSection,
		position = 0
	)
	default String baseUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "token",
		name = "Plugin token",
		description = "The PLUGIN_TOKEN secret set on your service. Required to authorize writes. Treat it like a password.",
		section = connectionSection,
		position = 1
	)
	default String token()
	{
		return "";
	}

	@ConfigItem(
		keyName = "sendFills",
		name = "Send GE fills",
		description = "Forward your Grand Exchange buys/sells so the dashboard can compute realized vs modeled flip P&L.",
		section = streamsSection,
		position = 0
	)
	default boolean sendFills()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendOffers",
		name = "Send GE offer book",
		description = "Also forward live offer state changes (placed/cancelled), not just completed fills. Off by default.",
		section = streamsSection,
		position = 1
	)
	default boolean sendOffers()
	{
		return false;
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
		return false;
	}
}

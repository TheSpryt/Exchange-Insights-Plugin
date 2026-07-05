/*
 * Copyright (c) 2026, Exchange Insights — BSD 2-Clause License (see LICENSE).
 */
package com.exchangeinsights;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Developer entrypoint: launches RuneLite with this plugin loaded as an
 * external plugin (the plugin-hub code path), used by the Gradle {@code run}
 * task. Not part of the shipped plugin.
 */
public class ExchangeInsightsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ExchangeInsightsPlugin.class);
		RuneLite.main(args);
	}
}

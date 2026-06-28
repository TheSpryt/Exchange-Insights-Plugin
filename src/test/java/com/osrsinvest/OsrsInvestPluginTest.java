/*
 * Copyright (c) 2026, OSRS Invest — BSD 2-Clause License (see LICENSE).
 */
package com.osrsinvest;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Developer entrypoint: launches RuneLite with this plugin loaded as an
 * external plugin (the plugin-hub code path), used by the Gradle {@code run}
 * task. Not part of the shipped plugin.
 */
public class OsrsInvestPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(OsrsInvestPlugin.class);
		RuneLite.main(args);
	}
}

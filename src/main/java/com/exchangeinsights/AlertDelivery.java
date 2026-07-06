/*
 * Copyright (c) 2026, Exchange Insights — BSD 2-Clause License (see LICENSE).
 */
package com.exchangeinsights;

/** How an in-game watchlist alert is announced. An actionable infobox (right-click
 *  Clear / Open in browser) is shown regardless; this only controls the announcement.
 *  MUST be public: RuneLite's config proxy (a different access context) reads it. */
public enum AlertDelivery
{
	CHAT("Chat message"),
	NOTIFICATION("System notification"),
	BOTH("Chat + notification");

	private final String label;

	AlertDelivery(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}

	public boolean chat()
	{
		return this == CHAT || this == BOTH;
	}

	public boolean notification()
	{
		return this == NOTIFICATION || this == BOTH;
	}
}

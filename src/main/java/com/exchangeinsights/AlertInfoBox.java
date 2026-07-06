/*
 * Copyright (c) 2026, Exchange Insights — BSD 2-Clause License (see LICENSE).
 */
package com.exchangeinsights;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.MenuAction;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.infobox.InfoBox;

/**
 * A watchlist alert shown as an infobox: the plugin icon with the alert as its
 * tooltip, and right-click "Clear" / "Open" entries. The entry objects are held so
 * the plugin can match them in the overlay-menu-clicked handler.
 */
class AlertInfoBox extends InfoBox
{
	final String title;
	final String link;
	final OverlayMenuEntry clearEntry;
	final OverlayMenuEntry openEntry; // null when the alert has no link
	private final String tooltip;

	AlertInfoBox(BufferedImage image, Plugin plugin, String title, String message, String link)
	{
		super(image, plugin);
		this.title = title;
		this.link = link;
		this.tooltip = message;
		this.clearEntry = new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY, "Clear", "Exchange Insights alert");
		this.openEntry = link != null && !link.isEmpty()
			? new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY, "Open", "Exchange Insights alert")
			: null;
	}

	@Override
	public String getText()
	{
		return "";
	}

	@Override
	public Color getTextColor()
	{
		return Color.WHITE;
	}

	@Override
	public String getTooltip()
	{
		return tooltip;
	}

	@Override
	public List<OverlayMenuEntry> getMenuEntries()
	{
		final List<OverlayMenuEntry> entries = new ArrayList<>(2);
		if (openEntry != null)
		{
			entries.add(openEntry);
		}
		entries.add(clearEntry);
		return entries;
	}
}

/*
 * Copyright (c) 2026, Exchange Insights — BSD 2-Clause License (see LICENSE).
 */
package com.exchangeinsights;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * GE offers screen: outline each active slot by how the offer has aged against
 * the live market - green when your price still fills first, red when the
 * market has moved past it. The gp amount itself is folded into the slot's
 * Buy/Sell title by the plugin; this overlay only draws the frames.
 */
class ExchangeInsightsSlotOverlay extends Overlay
{
	private static final Color AHEAD = new Color(0x6c, 0xc0, 0x71);
	private static final Color BEHIND = new Color(0xd4, 0x62, 0x62);
	private static final Color AT_MARKET = new Color(0xe0, 0xc0, 0x55);

	private final Client client;
	private final ExchangeInsightsPlugin plugin;
	private final ExchangeInsightsConfig config;

	@Inject
	ExchangeInsightsSlotOverlay(Client client, ExchangeInsightsPlugin plugin, ExchangeInsightsConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.geSlotBadges())
		{
			return null;
		}
		// render() runs per FRAME, not per tick - bail before touching the offer
		// array unless the offers index is actually on screen.
		final Widget index = client.getWidget(InterfaceID.GeOffers.INDEX);
		if (index == null || index.isHidden())
		{
			return null;
		}
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return null;
		}
		for (int i = 0; i < offers.length && i < 8; i++)
		{
			final Long gap = plugin.offerGap(offers[i]);
			if (gap == null)
			{
				continue;
			}
			// INDEX_0..INDEX_7 are contiguous component ids.
			final Widget slot = client.getWidget(InterfaceID.GeOffers.INDEX_0 + i);
			if (slot == null || slot.isHidden())
			{
				continue;
			}
			final Rectangle b = slot.getBounds();
			if (b == null || b.width <= 0)
			{
				continue;
			}
			final boolean buy = offers[i].getState() == GrandExchangeOfferState.BUYING;
			final int cls = ExchangeInsightsPlugin.classifyAge(buy, gap);
			g.setColor(cls > 0 ? AHEAD : cls < 0 ? BEHIND : AT_MARKET);
			g.drawRect(b.x, b.y, b.width - 1, b.height - 1);
		}
		return null;
	}
}

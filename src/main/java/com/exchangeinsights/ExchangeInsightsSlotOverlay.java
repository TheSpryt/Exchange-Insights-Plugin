/*
 * Copyright (c) 2026, Exchange Insights — BSD 2-Clause License (see LICENSE).
 */
package com.exchangeinsights;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.QuantityFormatter;

/**
 * GE offers screen: badge each active slot with how the offer has aged against
 * the live market. Green "AHEAD" = your price still fills first (bid at/over
 * insta-buy, ask at/under insta-sell); red "-Ngp" = the market has moved past
 * your price by that many gp per item, so it won't fill until it comes back.
 */
class ExchangeInsightsSlotOverlay extends Overlay
{
	private static final Color AHEAD = new Color(0x6c, 0xc0, 0x71);
	private static final Color BEHIND = new Color(0xd4, 0x62, 0x62);

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
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return null;
		}
		g.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics fm = g.getFontMetrics();
		for (int i = 0; i < offers.length && i < 8; i++)
		{
			final GrandExchangeOffer o = offers[i];
			if (o == null)
			{
				continue;
			}
			final GrandExchangeOfferState st = o.getState();
			final boolean buy = st == GrandExchangeOfferState.BUYING;
			if (!buy && st != GrandExchangeOfferState.SELLING)
			{
				continue; // empty, complete, or cancelled - nothing to age
			}
			// INDEX_0..INDEX_7 are contiguous component ids.
			final Widget slot = client.getWidget(InterfaceID.GeOffers.INDEX_0 + i);
			if (slot == null || slot.isHidden())
			{
				continue;
			}
			final ExchangeInsightsPlugin.GeQuote q = plugin.getSlotQuote(o.getItemId());
			if (q == null)
			{
				continue;
			}
			final String text;
			final Color color;
			if (buy)
			{
				if (q.high == null)
				{
					continue;
				}
				if (o.getPrice() >= q.high)
				{
					text = "AHEAD";
					color = AHEAD;
				}
				else
				{
					text = "-" + gp(q.high - o.getPrice());
					color = BEHIND;
				}
			}
			else
			{
				if (q.low == null)
				{
					continue;
				}
				if (o.getPrice() <= q.low)
				{
					text = "AHEAD";
					color = AHEAD;
				}
				else
				{
					text = "-" + gp(o.getPrice() - q.low);
					color = BEHIND;
				}
			}
			final Rectangle b = slot.getBounds();
			// Top-right of the slot's title band, clear of the centred Buy/Sell label.
			final int tx = b.x + b.width - fm.stringWidth(text) - 5;
			final int ty = b.y + 14;
			g.setColor(Color.BLACK);
			g.drawString(text, tx + 1, ty + 1);
			g.setColor(color);
			g.drawString(text, tx, ty);
		}
		return null;
	}

	/** Per-item gp gap in the tersest RS stack notation ("774", "51K", "13M") -
	 *  the badge shares a narrow title band with the centred Buy/Sell label, so
	 *  width matters more than precision (the details view has exact numbers). */
	private static String gp(long v)
	{
		final int clamped = (int) Math.min(Integer.MAX_VALUE, Math.max(0, v));
		return QuantityFormatter.quantityToRSDecimalStack(clamped, false);
	}
}

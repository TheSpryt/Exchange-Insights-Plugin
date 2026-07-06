/*
 * Copyright (c) 2026, Exchange Insights — BSD 2-Clause License (see LICENSE).
 */
package com.exchangeinsights;

import java.awt.BorderLayout;
import java.awt.Color;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel: connection status with a test button, plus what this session
 * has sent — so users can tell at a glance whether the plugin is working.
 * All mutators must be called on the Swing EDT.
 */
class ExchangeInsightsPanel extends PluginPanel
{
	static final Color OK_GREEN = new Color(0x6c, 0xc0, 0x71);
	static final Color WARN_YELLOW = new Color(0xe0, 0xc0, 0x55);
	static final Color ERR_RED = new Color(0xd4, 0x62, 0x62);
	static final Color MUTED = ColorScheme.LIGHT_GRAY_COLOR;

	private final JLabel statusLabel = new JLabel();
	private final JButton testButton = new JButton("Test connection");
	private final JButton linkButton = new JButton("Link account…");
	private final JLabel fillsValue = new JLabel("0");
	private final JLabel lastFillLabel = new JLabel("—");
	private final JLabel alertsValue = new JLabel("0");
	private final JLabel lastAlertLabel = new JLabel("—");

	ExchangeInsightsPanel(Runnable onTest, Runnable onLink)
	{
		setLayout(new BorderLayout(0, 14));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setOpaque(false);

		final JLabel title = new JLabel("Exchange Insights");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		top.add(title);
		top.add(Box.createVerticalStrut(10));

		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		top.add(statusLabel);
		top.add(Box.createVerticalStrut(10));

		linkButton.setToolTipText("Opens your browser to approve the link - the token is set up for you, nothing to copy.");
		linkButton.addActionListener(e -> onLink.run());
		linkButton.setVisible(false);
		top.add(linkButton);
		testButton.addActionListener(e -> onTest.run());
		top.add(testButton);
		add(top, BorderLayout.NORTH);

		final JPanel stats = new JPanel();
		stats.setLayout(new BoxLayout(stats, BoxLayout.Y_AXIS));
		stats.setOpaque(false);

		final JLabel session = new JLabel("This session");
		session.setFont(FontManager.getRunescapeBoldFont());
		session.setForeground(Color.WHITE);
		stats.add(session);
		stats.add(Box.createVerticalStrut(8));

		stats.add(row("GE fills sent", fillsValue));
		stats.add(Box.createVerticalStrut(4));
		stats.add(detail(lastFillLabel));
		stats.add(Box.createVerticalStrut(10));
		stats.add(row("Alerts shown", alertsValue));
		stats.add(Box.createVerticalStrut(4));
		stats.add(detail(lastAlertLabel));

		add(stats, BorderLayout.CENTER);
	}

	private static JPanel row(String key, JLabel value)
	{
		final JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		final JLabel k = new JLabel(key);
		k.setFont(FontManager.getRunescapeSmallFont());
		k.setForeground(MUTED);
		value.setFont(FontManager.getRunescapeSmallFont());
		value.setForeground(Color.WHITE);
		row.add(k, BorderLayout.WEST);
		row.add(value, BorderLayout.EAST);
		return row;
	}

	/** A muted, wrapping one-liner under a stat row (last fill / last alert). */
	private static JPanel detail(JLabel label)
	{
		final JPanel wrap = new JPanel(new BorderLayout());
		wrap.setOpaque(false);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(MUTED);
		wrap.add(label, BorderLayout.CENTER);
		return wrap;
	}

	/** HTML-wrap so long text wraps to the panel width instead of clipping. */
	private static String wrap(String text)
	{
		return "<html>" + text
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;") + "</html>";
	}

	void setStatus(String text, Color color)
	{
		statusLabel.setText(wrap(text));
		statusLabel.setForeground(color);
	}

	void setTesting(boolean inProgress)
	{
		testButton.setEnabled(!inProgress);
	}

	/** Show the one-click link button (no token yet) or the test button (linked). */
	void setLinkVisible(boolean visible)
	{
		linkButton.setVisible(visible);
		testButton.setVisible(!visible);
	}

	void setLinkEnabled(boolean enabled)
	{
		linkButton.setEnabled(enabled);
	}

	void setFills(int count, String lastDescription)
	{
		fillsValue.setText(String.format("%,d", count));
		lastFillLabel.setText(wrap(lastDescription));
	}

	void setAlerts(int count, String lastTitle)
	{
		alertsValue.setText(String.format("%,d", count));
		lastAlertLabel.setText(wrap(lastTitle));
	}
}

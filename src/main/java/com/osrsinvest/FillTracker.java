/*
 * Copyright (c) 2026, OSRS Invest — BSD 2-Clause License (see LICENSE).
 */
package com.osrsinvest;

import java.util.Arrays;

/**
 * Tracks per-slot Grand Exchange progress and reports how many units newly filled
 * since the last observation. Framework-free so the emit logic is unit-testable.
 *
 * The key subtlety: when we FIRST see a slot (plugin start, or the offer snapshot
 * the client re-broadcasts on login) we must NOT emit — those units may have
 * filled before we were watching. So the first observation only establishes a
 * baseline; emission starts from the next increase. A freshly placed offer fires
 * its BUYING/SELLING event at quantitySold 0 first, which becomes the baseline, so
 * even an instant full fill is captured by the following increment.
 */
class FillTracker
{
	private final int[] lastQtySold;

	FillTracker(int slots)
	{
		lastQtySold = new int[slots];
		reset();
	}

	/**
	 * @param slot     GE slot index
	 * @param cleared  true if the offer is empty/collected (resets the slot)
	 * @param qtySold  the offer's cumulative quantitySold
	 * @return units newly filled since the last observation (0 on first sight or no progress)
	 */
	int observe(int slot, boolean cleared, int qtySold)
	{
		if (cleared)
		{
			lastQtySold[slot] = -1;
			return 0;
		}
		final int prev = lastQtySold[slot];
		lastQtySold[slot] = qtySold;
		if (prev < 0)
		{
			return 0; // first sight → baseline only
		}
		final int delta = qtySold - prev;
		return delta > 0 ? delta : 0;
	}

	/** Forget all slot state (e.g. on logout) so the next snapshot re-baselines. */
	void reset()
	{
		Arrays.fill(lastQtySold, -1);
	}
}

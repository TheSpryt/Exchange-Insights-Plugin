/*
 * Copyright (c) 2026, OSRS Invest — BSD 2-Clause License (see LICENSE).
 */
package com.osrsinvest;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FillTrackerTest
{
	@Test
	public void firstSightBaselinesWithoutEmitting()
	{
		FillTracker t = new FillTracker(8);
		// Login snapshot: an offer already 100 filled must NOT replay as a new fill.
		assertEquals(0, t.observe(0, false, 100));
		// Subsequent progress emits only the delta.
		assertEquals(50, t.observe(0, false, 150));
	}

	@Test
	public void freshOfferCapturesIncrementsIncludingInstantFill()
	{
		FillTracker t = new FillTracker(8);
		assertEquals(0, t.observe(1, false, 0));   // BUYING placed → baseline
		assertEquals(1000, t.observe(1, false, 1000)); // instant full fill → captured
	}

	@Test
	public void partialFillsAccumulateNotDoubleCount()
	{
		FillTracker t = new FillTracker(8);
		t.observe(2, false, 0);
		assertEquals(3, t.observe(2, false, 3));
		assertEquals(0, t.observe(2, false, 3)); // re-fire of same state → nothing
		assertEquals(2, t.observe(2, false, 5));
	}

	@Test
	public void clearedSlotReBaselines()
	{
		FillTracker t = new FillTracker(8);
		t.observe(3, false, 0);
		t.observe(3, false, 10); // filled
		assertEquals(0, t.observe(3, true, 0));  // collected/empty → reset
		// Reused slot with a brand-new offer baselines again.
		assertEquals(0, t.observe(3, false, 0));
		assertEquals(7, t.observe(3, false, 7));
	}

	@Test
	public void resetForgetsAllSlots()
	{
		FillTracker t = new FillTracker(8);
		t.observe(4, false, 5);
		t.reset();
		assertEquals(0, t.observe(4, false, 20)); // post-reset first sight baselines
	}
}

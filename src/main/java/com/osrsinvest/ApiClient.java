/*
 * Copyright (c) 2026, OSRS Invest — BSD 2-Clause License (see LICENSE).
 */
package com.osrsinvest;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Thin async client for the OSRS Invest ingest API. All sends are fire-and-forget
 * off the client thread; the server dedupes fills, so a dropped or retried request
 * is harmless. Sends are no-ops until the URL + token are configured.
 */
@Slf4j
@Singleton
class ApiClient
{
	private static final MediaType JSON = MediaType.get("application/json");
	private static final String SOURCE = "osrs-invest-plugin/1.0.0";

	private final OkHttpClient http;
	private final Gson gson;
	private final OsrsInvestConfig config;

	@Inject
	ApiClient(OkHttpClient http, Gson gson, OsrsInvestConfig config)
	{
		this.http = http;
		this.gson = gson;
		this.config = config;
	}

	/** A single GE transaction. Field names match the /api/plugin/fills contract. */
	static final class Fill
	{
		final int id;
		final String type; // "buy" | "sell"
		final int price;
		final int qty;
		final long filledAt; // unix seconds

		Fill(int id, String type, int price, int qty, long filledAt)
		{
			this.id = id;
			this.type = type;
			this.price = price;
			this.qty = qty;
			this.filledAt = filledAt;
		}
	}

	/** A generic plugin event (datamine, offer book, …). */
	static final class Event
	{
		final String kind;
		final Integer id;
		final Object payload;
		final long clientTs;

		Event(String kind, Integer id, Object payload, long clientTs)
		{
			this.kind = kind;
			this.id = id;
			this.payload = payload;
			this.clientTs = clientTs;
		}
	}

	/** A fingerprinted item from the client cache, for the datamine diff. */
	static final class DatamineItem
	{
		final int id;
		final String name;
		final boolean tradeable;
		final boolean members;
		final int value;
		final boolean stackable;

		DatamineItem(int id, String name, boolean tradeable, boolean members, int value, boolean stackable)
		{
			this.id = id;
			this.name = name;
			this.tradeable = tradeable;
			this.members = members;
			this.value = value;
			this.stackable = stackable;
		}
	}

	private static final class FillsPayload
	{
		final List<Fill> fills;
		final String source = SOURCE;

		FillsPayload(List<Fill> fills)
		{
			this.fills = fills;
		}
	}

	private static final class EventsPayload
	{
		final List<Event> events;
		final String source = SOURCE;

		EventsPayload(List<Event> events)
		{
			this.events = events;
		}
	}

	private static final class DataminePayload
	{
		final List<DatamineItem> items;
		final String source = SOURCE;

		DataminePayload(List<DatamineItem> items)
		{
			this.items = items;
		}
	}

	boolean isConfigured()
	{
		return !config.baseUrl().trim().isEmpty() && !config.token().trim().isEmpty();
	}

	void sendFills(List<Fill> fills)
	{
		if (!fills.isEmpty())
		{
			post("/api/plugin/fills", new FillsPayload(fills));
		}
	}

	void sendEvents(List<Event> events)
	{
		if (!events.isEmpty())
		{
			post("/api/plugin/events", new EventsPayload(events));
		}
	}

	void sendDatamine(List<DatamineItem> items)
	{
		if (!items.isEmpty())
		{
			post("/api/plugin/datamine", new DataminePayload(items));
		}
	}

	private void post(String path, Object body)
	{
		if (!isConfigured())
		{
			return;
		}
		final String base = config.baseUrl().trim().replaceAll("/+$", "");
		final Request request;
		try
		{
			request = new Request.Builder()
				.url(base + path)
				.addHeader("Authorization", "Bearer " + config.token().trim())
				.addHeader("User-Agent", SOURCE)
				.post(RequestBody.create(JSON, gson.toJson(body)))
				.build();
		}
		catch (IllegalArgumentException ex)
		{
			log.warn("OSRS Invest: bad dashboard URL '{}'", base, ex);
			return;
		}

		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("OSRS Invest: {} send failed", path, e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						log.debug("OSRS Invest: {} returned {}", path, r.code());
					}
				}
			}
		});
	}
}

/*
 * Copyright (c) 2026, Exchange Insights — BSD 2-Clause License (see LICENSE).
 */
package com.exchangeinsights;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
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
 * Thin async client for the Exchange Insights ingest API. All sends are fire-and-forget
 * off the client thread; the server dedupes fills, so a dropped or retried request
 * is harmless. Sends are no-ops until the URL + token are configured.
 */
@Slf4j
@Singleton
class ApiClient
{
	private static final MediaType JSON = MediaType.get("application/json");
	private static final String SOURCE = "exchange-insights-plugin/1.1.0";

	private final OkHttpClient http;
	private final Gson gson;
	private final ExchangeInsightsConfig config;

	@Inject
	ApiClient(OkHttpClient http, Gson gson, ExchangeInsightsConfig config)
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

	/** One GE slot's live state for the offer-book sync. Nulls (Integer) so EMPTY
	 *  slots serialise without item/price fields. */
	static final class OfferSlot
	{
		int slot;
		String state; // EMPTY | BUYING | SELLING | BOUGHT | SOLD | CANCELLED_BUY | CANCELLED_SELL
		Integer itemId;
		Integer price;
		Integer qty;
		Integer filledQty;
		Integer spent;
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

	private static final class OffersPayload
	{
		final List<OfferSlot> offers;
		final String source = SOURCE;

		OffersPayload(List<OfferSlot> offers)
		{
			this.offers = offers;
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

	/** The character this client is logged into. Attaches the character (and its
	 *  alts) to the token's owner on the dashboard. accountHash is sent as a string:
	 *  it's a 64-bit value, and JSON numbers lose precision past 2^53. */
	private static final class IdentityPayload
	{
		final String accountHash;
		final String rsn;
		final String source = SOURCE;

		IdentityPayload(String accountHash, String rsn)
		{
			this.accountHash = accountHash;
			this.rsn = rsn;
		}
	}

	/** A pending watchlist alert routed to the RuneLite channel. */
	static final class Alert
	{
		long id;
		String title;
		String body;
		String link;
	}

	private static final class AlertsResponse
	{
		List<Alert> alerts;
	}

	/** Outcome of a connection test against /api/plugin/ping. */
	enum PingStatus
	{
		OK,
		UNAUTHORIZED,
		ERROR,
	}

	private static final class PingResponse
	{
		boolean ok;
		String handle;
	}

	/** A started device-link request: open verificationUrl, poll with deviceSecret. */
	static final class LinkStart
	{
		String userCode;
		String deviceSecret;
		String verificationUrl;
		long expiresAt;
		int pollSeconds;
	}

	/** One poll of a device-link request; token is set exactly once on approval. */
	static final class LinkPoll
	{
		String status; // pending | approved | denied | expired | invalid | claimed
		String token;
	}

	private static final class LinkStartPayload
	{
		final String accountHash;
		final String accountName;
		final String source = SOURCE;

		LinkStartPayload(String accountHash, String accountName)
		{
			this.accountHash = accountHash;
			this.accountName = accountName;
		}
	}

	private static final class LinkPollPayload
	{
		final String deviceSecret;

		LinkPollPayload(String deviceSecret)
		{
			this.deviceSecret = deviceSecret;
		}
	}

	/** Public live quote for one item (?econ=1 adds catalogue facts). No auth needed.
	 *  With ?flip=1 and a premium token, `flip` carries the Flip Finder net margin. */
	static final class Quote
	{
		Price price;
		Double margin; // per-unit after-tax item margin (plain spread), gp
		Double roi; // fraction, 0.05 = +5%
		Item item;
		Flip flip; // present only for premium tokens that asked for it

		static final class Price
		{
			Long high; // instabuy
			Long low; // instasell
		}

		static final class Item
		{
			String name;
			Integer geLimit;
		}

		static final class Flip
		{
			Double margin; // quant-adjusted net margin per unit, gp
			Double roi; // fraction
		}
	}

	/** Fully configured for the authenticated streams (fills, identity, alerts). */
	boolean isConfigured()
	{
		return hasUrl() && !config.token().trim().isEmpty();
	}

	/** A dashboard URL is set - enough for the public reads (quote, device link). */
	boolean hasUrl()
	{
		return !base().isEmpty();
	}

	private String base()
	{
		return config.baseUrl().trim().replaceAll("/+$", "");
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

	/** Push the full GE offer book snapshot (all slots, EMPTY included). */
	void sendOffers(List<OfferSlot> offers)
	{
		if (!offers.isEmpty())
		{
			post("/api/plugin/offers", new OffersPayload(offers));
		}
	}

	void sendDatamine(List<DatamineItem> items)
	{
		if (!items.isEmpty())
		{
			post("/api/plugin/datamine", new DataminePayload(items));
		}
	}

	void sendIdentity(long accountHash, String rsn)
	{
		post("/api/plugin/identity", new IdentityPayload(Long.toString(accountHash), rsn));
	}

	/**
	 * Test the configured URL + token against the dashboard's side-effect-free ping
	 * endpoint. The callback runs on the HTTP dispatcher thread with the status and,
	 * when connected, the account's public handle (may be null).
	 */
	void ping(BiConsumer<PingStatus, String> onResult)
	{
		final Request request = isConfigured() ? buildRequest("/api/plugin/ping") : null;
		if (request == null)
		{
			onResult.accept(PingStatus.ERROR, null);
			return;
		}
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Exchange Insights: ping failed", e);
				onResult.accept(PingStatus.ERROR, null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.code() == 401 || r.code() == 403)
					{
						onResult.accept(PingStatus.UNAUTHORIZED, null);
						return;
					}
					if (!r.isSuccessful() || r.body() == null)
					{
						onResult.accept(PingStatus.ERROR, null);
						return;
					}
					final PingResponse parsed = gson.fromJson(r.body().charStream(), PingResponse.class);
					onResult.accept(PingStatus.OK, parsed == null ? null : parsed.handle);
				}
				catch (Exception ex)
				{
					log.debug("Exchange Insights: ping parse failed", ex);
					onResult.accept(PingStatus.ERROR, null);
				}
			}
		});
	}

	/**
	 * Poll pending in-game alerts. The server stamps each returned alert delivered,
	 * so every alert is handed out exactly once; the callback runs on the HTTP
	 * dispatcher thread and is only invoked when there is at least one alert.
	 */
	void fetchAlerts(Consumer<List<Alert>> onAlerts)
	{
		if (!isConfigured())
		{
			return;
		}
		final Request request = buildRequest("/api/plugin/alerts");
		if (request == null)
		{
			return;
		}
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Exchange Insights: alerts poll failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						log.debug("Exchange Insights: alerts poll returned {}", r.code());
						return;
					}
					final AlertsResponse parsed = gson.fromJson(r.body().charStream(), AlertsResponse.class);
					if (parsed != null && parsed.alerts != null && !parsed.alerts.isEmpty())
					{
						onAlerts.accept(parsed.alerts);
					}
				}
				catch (Exception ex)
				{
					log.debug("Exchange Insights: alerts poll parse failed", ex);
				}
			}
		});
	}

	/** Request skeleton with the UA and, when a token is set, bearer auth. Null when
	 *  the URL is missing/malformed. Public endpoints work fine without the token. */
	private Request.Builder requestBuilder(String path)
	{
		final String base = base();
		if (base.isEmpty())
		{
			return null;
		}
		try
		{
			final Request.Builder b = new Request.Builder()
				.url(base + path)
				.addHeader("User-Agent", SOURCE);
			final String token = config.token().trim();
			if (!token.isEmpty())
			{
				b.addHeader("Authorization", "Bearer " + token);
			}
			return b;
		}
		catch (IllegalArgumentException ex)
		{
			log.warn("Exchange Insights: bad dashboard URL '{}'", base, ex);
			return null;
		}
	}

	private Request buildRequest(String path)
	{
		final Request.Builder b = requestBuilder(path);
		return b == null ? null : b.build();
	}

	/** Fire a request and hand the parsed JSON body (or null on any failure) to the
	 *  callback, which runs on the HTTP dispatcher thread. */
	private <T> void requestJson(Request request, Class<T> type, Consumer<T> onResult)
	{
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Exchange Insights: {} failed", request.url().encodedPath(), e);
				onResult.accept(null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						onResult.accept(null);
						return;
					}
					onResult.accept(gson.fromJson(r.body().charStream(), type));
				}
				catch (Exception ex)
				{
					log.debug("Exchange Insights: {} parse failed", request.url().encodedPath(), ex);
					onResult.accept(null);
				}
			}
		});
	}

	/** Start a device link: the server mints a code pair; open verificationUrl in
	 *  the browser and poll with the secret. Works without a token by design. */
	void startLink(long accountHash, String rsn, Consumer<LinkStart> onResult)
	{
		final Request.Builder b = requestBuilder("/api/plugin/link/start");
		if (b == null)
		{
			onResult.accept(null);
			return;
		}
		final LinkStartPayload payload = new LinkStartPayload(Long.toString(accountHash), rsn);
		requestJson(b.post(RequestBody.create(JSON, gson.toJson(payload))).build(), LinkStart.class, onResult);
	}

	/** One poll of a pending device link. */
	void pollLink(String deviceSecret, Consumer<LinkPoll> onResult)
	{
		final Request.Builder b = requestBuilder("/api/plugin/link/poll");
		if (b == null)
		{
			onResult.accept(null);
			return;
		}
		requestJson(b.post(RequestBody.create(JSON, gson.toJson(new LinkPollPayload(deviceSecret)))).build(), LinkPoll.class, onResult);
	}

	/** Public live quote for the GE overlay - no account or token required. When
	 *  `withFlip` is set, also asks for the premium Flip Finder margin (the server
	 *  only returns it for a premium token; otherwise the field is simply absent). */
	void fetchQuote(int itemId, boolean withFlip, Consumer<Quote> onResult)
	{
		final Request request = buildRequest("/api/items/" + itemId + "/quote?econ=1" + (withFlip ? "&flip=1" : ""));
		if (request == null)
		{
			onResult.accept(null);
			return;
		}
		requestJson(request, Quote.class, onResult);
	}

	private void post(String path, Object body)
	{
		if (!isConfigured())
		{
			return;
		}
		final Request.Builder b = requestBuilder(path);
		if (b == null)
		{
			return;
		}
		final Request request = b.post(RequestBody.create(JSON, gson.toJson(body))).build();

		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Exchange Insights: {} send failed", path, e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						log.debug("Exchange Insights: {} returned {}", path, r.code());
					}
				}
			}
		});
	}
}

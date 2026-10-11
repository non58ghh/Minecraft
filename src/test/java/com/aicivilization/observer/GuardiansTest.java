package com.aicivilization.observer;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardiansTest {

	private static final UUID IRIS = UUID.randomUUID();
	private static final UUID BRAM = UUID.randomUUID();
	private static final UUID NELL = UUID.randomUUID();

	private static String session(String id, UUID agent, String name, String sub, long created) {
		return "{\"id\":\"" + id + "\",\"client_reference_id\":\"" + agent + "\",\"subscription\":\"" + sub + "\",\"created\":" + created
				+ ",\"custom_fields\":[{\"key\":\"guardianname\",\"text\":{\"value\":\"" + name + "\"}}]}";
	}

	@Test
	void standingSubscriptionsMakeGuardiansAndTheFirstKeepsThePlace() throws Exception {
		List<String> seen = new CopyOnWriteArrayList<>();
		HttpServer stripe = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		stripe.createContext("/", ex -> {
			String q = ex.getRequestURI().getRawQuery();
			seen.add(ex.getRequestURI().getPath() + "?" + q + " " + ex.getRequestHeaders().getFirst("Authorization"));
			String body;
			if (ex.getRequestURI().getPath().equals("/v1/subscriptions")) {
				body = "{\"data\":[{\"id\":\"sub_a\",\"status\":\"active\"},{\"id\":\"sub_b\",\"status\":\"canceled\"},"
						+ "{\"id\":\"sub_c\",\"status\":\"past_due\"},{\"id\":\"sub_d\",\"status\":\"active\"}],\"has_more\":false}";
			} else if (!q.contains("starting_after")) {
				body = "{\"data\":[" + session("cs_1", IRIS, "  Moss\\n  Keeper ", "sub_a", 100) + ","
						+ session("cs_2", BRAM, "Gone", "sub_b", 110) + "],\"has_more\":true}";
			} else {
				body = "{\"data\":[" + session("cs_3", IRIS, "Later", "sub_d", 200) + ","
						+ session("cs_4", NELL, "A very long guardian name that goes on and on", "sub_c", 120) + "],\"has_more\":false}";
			}
			byte[] out = body.getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, out.length);
			ex.getResponseBody().write(out);
			ex.close();
		});
		stripe.start();
		try {
			Guardians g = new Guardians("http://127.0.0.1:" + stripe.getAddress().getPort(), "plink_x", () -> "rk_test", 600);
			Map<UUID, Guardians.Guardian> got = g.fetch();
			assertEquals("Moss Keeper", got.get(IRIS).name(), "the first standing Guardian keeps the place; the name is tidied");
			assertEquals(100, got.get(IRIS).since());
			assertFalse(got.containsKey(BRAM), "a cancelled subscription is no Guardian");
			assertEquals(Guardians.MAX_NAME, got.get(NELL).name().length(), "past due still stands; long names are cut");
			assertTrue(seen.stream().anyMatch(s -> s.contains("payment_link=plink_x") && s.contains("status=complete")));
			assertTrue(seen.stream().anyMatch(s -> s.contains("starting_after=cs_2")), "every page is read");
			assertTrue(seen.stream().allMatch(s -> s.endsWith("Bearer rk_test")));
		} finally {
			stripe.stop(0);
		}
	}

	@Test
	void noKeyMeansNoRead() {
		Guardians g = new Guardians("http://127.0.0.1:9", "plink_x", () -> "", 600);
		try {
			g.fetch();
		} catch (IllegalStateException e) {
			assertEquals("no Stripe key", e.getMessage());
		}
		assertNull(g.of(IRIS));
	}
}

package com.aicivilization.observer;

import com.aicivilization.events.Cause;
import com.aicivilization.events.SimEvent;
import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Belief;
import com.aicivilization.mind.DecisionTrace;
import com.aicivilization.mind.Goal;
import com.aicivilization.mind.MemoryEntry;
import com.aicivilization.mind.Needs;
import com.aicivilization.mind.Personality;
import com.aicivilization.mind.Possession;
import com.aicivilization.mind.Provenance;
import com.aicivilization.mind.RelationshipData;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Converts simulation state to JSON for the observer page. Pure data
 * mapping with no Minecraft types, so it is unit-testable. It only reads:
 * the observer sits outside the simulation and never writes to a mind.
 *
 * <p>Calls that take an {@link AgentMind} read mutable state and must run on
 * the server thread. {@link #event} takes only immutable data and is safe on
 * any thread.
 */
public final class ObserverJson {

	static final int DETAIL_MEMORY_LIMIT = 40;
	static final int DETAIL_DECISION_LIMIT = 5;

	private ObserverJson() {
	}

	/** Where an agent's body is, or null if it isn't loaded. */
	public record Position(String dimension, double x, double y, double z) {
	}

	public static JsonObject agentSummary(AgentMind mind, Position position, long tick) {
		JsonObject o = new JsonObject();
		o.addProperty("id", mind.identity().id().toString());
		o.addProperty("name", mind.identity().name());
		o.addProperty("alive", mind.isAlive());
		o.addProperty("birthTick", mind.identity().birthTick());
		o.addProperty("ageTicks", Math.max(0, tick - mind.identity().birthTick()));
		o.add("position", position(position));
		o.add("needs", needs(mind.needs()));
		o.addProperty("crisis", mind.needs().hasCrisis());
		o.addProperty("lowestNeed", mind.needs().lowestName());
		Goal top = mind.goals().stream()
				.filter(Goal::active)
				.max(Comparator.comparingDouble(Goal::priority))
				.orElse(null);
		o.addProperty("topGoal", top == null ? null : top.description());
		List<DecisionTrace> decisions = mind.recentDecisions();
		DecisionTrace last = decisions.isEmpty() ? null : decisions.get(decisions.size() - 1);
		o.addProperty("currentIntent", last == null ? null : last.chosen().name());
		o.addProperty("lastDecisionTick", last == null ? null : last.tick());
		o.addProperty("home", mind.home().map(h -> h.design().name()).orElse(null));
		return o;
	}

	/**
	 * Everything the agent-detail view shows. {@code names} resolves agent ids
	 * to display names; {@code recentEvents} are this agent's latest events.
	 */
	public static JsonObject agentDetail(AgentMind mind, Position position, long tick,
			Function<UUID, String> names, List<SimEvent> recentEvents) {
		JsonObject o = agentSummary(mind, position, tick);
		o.add("personality", personality(mind.personality()));

		JsonArray inventory = new JsonArray();
		for (var item : mind.possessions()) {
			JsonObject j = new JsonObject();
			j.addProperty("itemId", item.itemId());
			j.addProperty("quantity", item.quantity());
			inventory.add(j);
		}
		o.add("inventory", inventory);

		JsonArray goals = new JsonArray();
		mind.goals().stream()
				.sorted(Comparator.comparing(Goal::active).thenComparingDouble(Goal::priority).reversed())
				.forEach(g -> {
					JsonObject j = new JsonObject();
					j.addProperty("id", g.id());
					j.addProperty("description", g.description());
					j.addProperty("priority", g.priority());
					j.addProperty("intent", g.relatedIntent() == null ? null : g.relatedIntent().name());
					j.addProperty("createdTick", g.createdTick());
					j.addProperty("active", g.active());
					goals.add(j);
				});
		o.add("goals", goals);

		JsonArray beliefs = new JsonArray();
		mind.beliefs().stream()
				.sorted(Comparator.comparingLong(Belief::formedTick).reversed())
				.forEach(b -> {
					JsonObject j = new JsonObject();
					j.addProperty("id", b.id());
					j.addProperty("statement", b.statement());
					j.addProperty("confidence", b.confidence());
					j.addProperty("formedTick", b.formedTick());
					j.add("provenance", provenance(b.provenance(), names));
					beliefs.add(j);
				});
		o.add("beliefs", beliefs);

		JsonArray relationships = new JsonArray();
		mind.relationships().asMap().entrySet().stream()
				.sorted(Comparator.comparingDouble((Map.Entry<UUID, RelationshipData> e) -> e.getValue().affinity()).reversed())
				.forEach(e -> {
					RelationshipData r = e.getValue();
					JsonObject j = new JsonObject();
					j.addProperty("id", e.getKey().toString());
					j.addProperty("name", names.apply(e.getKey()));
					j.addProperty("affinity", r.affinity());
					j.addProperty("trust", r.trust());
					j.addProperty("lastInteractionTick", r.lastInteractionTick());
					j.addProperty("thingsLearned", r.thingsLearnedFromThem());
					relationships.add(j);
				});
		o.add("relationships", relationships);

		JsonArray memories = new JsonArray();
		mind.memories().all().stream()
				.sorted(Comparator.comparingLong(MemoryEntry::tick).thenComparingLong(MemoryEntry::id).reversed())
				.limit(DETAIL_MEMORY_LIMIT)
				.forEach(m -> memories.add(memory(m, names)));
		o.add("memories", memories);
		o.addProperty("memoryCount", mind.memories().size());

		JsonArray decisions = new JsonArray();
		List<DecisionTrace> all = mind.recentDecisions();
		for (int i = all.size() - 1; i >= Math.max(0, all.size() - DETAIL_DECISION_LIMIT); i--) {
			decisions.add(decision(all.get(i)));
		}
		o.add("decisions", decisions);

		JsonArray possessions = new JsonArray();
		for (Possession p : mind.possessions()) {
			JsonObject j = new JsonObject();
			j.addProperty("item", p.itemId());
			j.addProperty("quantity", p.quantity());
			j.addProperty("acquiredTick", p.acquiredTick());
			possessions.add(j);
		}
		o.add("possessions", possessions);

		mind.home().ifPresent(h -> {
			JsonObject j = new JsonObject();
			j.addProperty("x", h.x());
			j.addProperty("y", h.y());
			j.addProperty("z", h.z());
			j.addProperty("design", h.design().name());
			j.addProperty("builtTick", h.builtTick());
			o.add("homeDetail", j);
		});
		JsonArray designs = new JsonArray();
		for (var k : mind.knownDesigns()) {
			JsonObject j = new JsonObject();
			j.addProperty("id", k.design().id());
			j.addProperty("name", k.design().name());
			j.addProperty("how", k.how());
			j.addProperty("source", k.source());
			j.addProperty("learnedTick", k.learnedTick());
			j.addProperty("size", k.design().width() + "x" + k.design().depth() + "x" + k.design().height());
			JsonArray layers = new JsonArray();
			k.design().layers().forEach(layer -> {
				JsonArray rows = new JsonArray();
				layer.forEach(rows::add);
				layers.add(rows);
			});
			j.add("layers", layers);
			designs.add(j);
		}
		o.add("designs", designs);

		JsonArray events = new JsonArray();
		for (SimEvent e : recentEvents) {
			events.add(event(e, names));
		}
		o.add("recentEvents", events);
		return o;
	}

	public static JsonObject event(SimEvent e, Function<UUID, String> names) {
		JsonObject o = new JsonObject();
		o.addProperty("id", e.id());
		o.addProperty("tick", e.tick());
		o.addProperty("type", e.type().name());
		o.add("subjects", people(e.subjects(), names));
		o.addProperty("summary", e.summary());
		o.add("causes", causes(e.causes()));
		return o;
	}

	static JsonObject decision(DecisionTrace d) {
		JsonObject o = new JsonObject();
		o.addProperty("id", d.id());
		o.addProperty("tick", d.tick());
		o.addProperty("chosen", d.chosen().name());
		JsonArray candidates = new JsonArray();
		for (DecisionTrace.ScoredIntent c : d.candidates()) {
			JsonObject j = new JsonObject();
			j.addProperty("intent", c.intent().name());
			j.addProperty("score", c.score());
			JsonObject factors = new JsonObject();
			c.factors().entrySet().stream()
					.sorted(Map.Entry.<String, Double>comparingByValue().reversed())
					.forEach(f -> factors.addProperty(f.getKey(), f.getValue()));
			j.add("factors", factors);
			candidates.add(j);
		}
		o.add("candidates", candidates);
		o.add("causes", causes(d.causes()));
		return o;
	}

	static JsonObject memory(MemoryEntry m, Function<UUID, String> names) {
		JsonObject o = new JsonObject();
		o.addProperty("id", m.id());
		o.addProperty("tick", m.tick());
		o.addProperty("description", m.description());
		o.addProperty("importance", m.importance());
		o.add("participants", people(m.participants(), names));
		o.add("provenance", provenance(m.provenance(), names));
		return o;
	}

	static JsonObject provenance(Provenance p, Function<UUID, String> names) {
		JsonObject o = new JsonObject();
		switch (p) {
			case Provenance.Perceived ignored -> o.addProperty("type", "PERCEIVED");
			case Provenance.Inferred inferred -> {
				o.addProperty("type", "INFERRED");
				o.addProperty("sourceMemoryId", inferred.sourceMemoryId());
			}
			case Provenance.Told told -> {
				o.addProperty("type", "TOLD");
				o.addProperty("tellerId", told.tellerId().toString());
				o.addProperty("tellerName", names.apply(told.tellerId()));
				o.addProperty("tellerMemoryId", told.tellerMemoryId());
			}
		}
		return o;
	}

	static JsonArray causes(List<Cause> causes) {
		JsonArray a = new JsonArray();
		for (Cause c : causes) {
			JsonObject j = new JsonObject();
			j.addProperty("type", c.sourceType().name());
			j.addProperty("sourceId", c.sourceId());
			j.addProperty("detail", c.detail());
			a.add(j);
		}
		return a;
	}

	static JsonObject needs(Needs n) {
		JsonObject o = new JsonObject();
		o.addProperty("food", n.food());
		o.addProperty("safety", n.safety());
		o.addProperty("social", n.social());
		o.addProperty("belonging", n.belonging());
		return o;
	}

	static JsonObject personality(Personality p) {
		JsonObject o = new JsonObject();
		o.addProperty("curiosity", p.curiosity());
		o.addProperty("risk", p.risk());
		o.addProperty("sociability", p.sociability());
		o.addProperty("ambition", p.ambition());
		return o;
	}

	private static JsonArray people(Collection<UUID> ids, Function<UUID, String> names) {
		JsonArray a = new JsonArray();
		for (UUID id : ids) {
			JsonObject j = new JsonObject();
			j.addProperty("id", id.toString());
			j.addProperty("name", names.apply(id));
			a.add(j);
		}
		return a;
	}

	private static com.google.gson.JsonElement position(Position p) {
		if (p == null) {
			return JsonNull.INSTANCE;
		}
		JsonObject o = new JsonObject();
		o.addProperty("dimension", p.dimension());
		o.addProperty("x", Math.round(p.x() * 10) / 10.0);
		o.addProperty("y", Math.round(p.y() * 10) / 10.0);
		o.addProperty("z", Math.round(p.z() * 10) / 10.0);
		return o;
	}
}

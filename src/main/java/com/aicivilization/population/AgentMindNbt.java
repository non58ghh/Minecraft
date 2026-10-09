package com.aicivilization.population;

import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Belief;
import com.aicivilization.mind.Design;
import com.aicivilization.mind.Goal;
import com.aicivilization.mind.Home;
import com.aicivilization.mind.Identity;
import com.aicivilization.mind.IntentType;
import com.aicivilization.mind.KnownDesign;
import com.aicivilization.mind.MemoryEntry;
import com.aicivilization.mind.Needs;
import com.aicivilization.mind.Personality;
import com.aicivilization.mind.Possession;
import com.aicivilization.mind.Provenance;
import com.aicivilization.mind.RelationshipData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * (De)serializes an {@link AgentMind} to/from NBT. This is Minecraft
 * persistence glue, deliberately kept out of the {@code mind} package
 * itself so that package stays free of Minecraft dependencies.
 */
final class AgentMindNbt {

	private AgentMindNbt() {
	}

	static CompoundTag write(AgentMind mind) {
		CompoundTag tag = new CompoundTag();

		Identity identity = mind.identity();
		tag.store("id", UUIDUtil.CODEC, identity.id());
		tag.putString("name", identity.name());
		tag.putLong("birthTick", identity.birthTick());
		tag.putBoolean("alive", mind.isAlive());

		Personality p = mind.personality();
		tag.putDouble("curiosity", p.curiosity());
		tag.putDouble("risk", p.risk());
		tag.putDouble("sociability", p.sociability());
		tag.putDouble("ambition", p.ambition());

		Needs n = mind.needs();
		tag.putDouble("foodNeed", n.food());
		tag.putDouble("safetyNeed", n.safety());
		tag.putDouble("socialNeed", n.social());
		tag.putDouble("belongingNeed", n.belonging());

		ListTag memoryList = new ListTag();
		for (MemoryEntry entry : mind.memories().all()) {
			memoryList.add(writeMemory(entry));
		}
		tag.put("memories", memoryList);
		tag.putLong("nextMemoryId", mind.memories().peekNextId());

		ListTag relationshipList = new ListTag();
		for (var e : mind.relationships().asMap().entrySet()) {
			CompoundTag rel = new CompoundTag();
			rel.store("agentId", UUIDUtil.CODEC, e.getKey());
			RelationshipData data = e.getValue();
			rel.putDouble("affinity", data.affinity());
			rel.putDouble("trust", data.trust());
			rel.putLong("lastInteractionTick", data.lastInteractionTick());
			rel.putInt("thingsLearnedFromThem", data.thingsLearnedFromThem());
			if (data.lastSeenTick() >= 0) {
				rel.putLong("lastSeenTick", data.lastSeenTick());
				rel.putInt("lastSeenX", data.lastSeenX());
				rel.putInt("lastSeenY", data.lastSeenY());
				rel.putInt("lastSeenZ", data.lastSeenZ());
			}
			relationshipList.add(rel);
		}
		tag.put("relationships", relationshipList);

		ListTag beliefList = new ListTag();
		for (Belief belief : mind.beliefs()) {
			CompoundTag b = new CompoundTag();
			b.putLong("id", belief.id());
			b.putString("statement", belief.statement());
			b.putDouble("confidence", belief.confidence());
			b.putLong("formedTick", belief.formedTick());
			writeProvenance(b, belief.provenance());
			beliefList.add(b);
		}
		tag.put("beliefs", beliefList);
		tag.putLong("nextBeliefId", mind.nextBeliefIdPeek());

		ListTag goalList = new ListTag();
		for (Goal goal : mind.goals()) {
			CompoundTag g = new CompoundTag();
			g.putLong("id", goal.id());
			g.putString("description", goal.description());
			g.putDouble("priority", goal.priority());
			if (goal.relatedIntent() != null) {
				g.putString("relatedIntent", goal.relatedIntent().name());
			}
			g.putLong("createdTick", goal.createdTick());
			g.putBoolean("active", goal.active());
			goalList.add(g);
		}
		tag.put("goals", goalList);
		tag.putLong("nextGoalId", mind.nextGoalIdPeek());

		ListTag possessionList = new ListTag();
		for (Possession possession : mind.possessions()) {
			CompoundTag pos = new CompoundTag();
			pos.putString("itemId", possession.itemId());
			pos.putInt("quantity", possession.quantity());
			pos.putLong("acquiredTick", possession.acquiredTick());
			possessionList.add(pos);
		}
		tag.put("possessions", possessionList);

		ListTag designList = new ListTag();
		for (KnownDesign known : mind.knownDesigns()) {
			if (known.how().equals("innate")) {
				continue;
			}
			CompoundTag d = writeDesign(known.design());
			d.putString("how", known.how());
			d.putString("source", known.source());
			if (known.sourceId() != null) {
				d.store("sourceId", UUIDUtil.CODEC, known.sourceId());
			}
			d.putLong("learnedTick", known.learnedTick());
			designList.add(d);
		}
		tag.put("designs", designList);

		mind.home().ifPresent(home -> {
			CompoundTag h = writeDesign(home.design());
			h.putInt("x", home.x());
			h.putInt("y", home.y());
			h.putInt("z", home.z());
			h.putLong("builtTick", home.builtTick());
			tag.put("home", h);
		});

		return tag;
	}

	static AgentMind read(CompoundTag tag) {
		Identity identity = new Identity(tag.read("id", UUIDUtil.CODEC).orElseThrow(), tag.getStringOr("name", ""), tag.getLongOr("birthTick", 0));
		Personality personality = new Personality(
				tag.getDoubleOr("curiosity", 0), tag.getDoubleOr("risk", 0),
				tag.getDoubleOr("sociability", 0), tag.getDoubleOr("ambition", 0));
		Needs needs = new Needs(
				tag.getDoubleOr("foodNeed", 0), tag.getDoubleOr("safetyNeed", 0),
				tag.getDoubleOr("socialNeed", 0), tag.getDoubleOr("belongingNeed", 0));

		AgentMind mind = new AgentMind(identity, personality, needs);
		if (!tag.getBooleanOr("alive", true)) {
			mind.markDead();
		}

		List<MemoryEntry> memories = new ArrayList<>();
		ListTag memoryList = tag.getListOrEmpty("memories");
		for (int i = 0; i < memoryList.size(); i++) {
			memories.add(readMemory(memoryList.getCompoundOrEmpty(i)));
		}
		mind.memories().restoreState(memories, tag.getLongOr("nextMemoryId", 1));

		ListTag relationshipList = tag.getListOrEmpty("relationships");
		for (int i = 0; i < relationshipList.size(); i++) {
			CompoundTag rel = relationshipList.getCompoundOrEmpty(i);
			RelationshipData data = new RelationshipData(
					rel.getDoubleOr("affinity", 0), rel.getDoubleOr("trust", 0),
					rel.getLongOr("lastInteractionTick", 0), rel.getIntOr("thingsLearnedFromThem", 0));
			if (rel.contains("lastSeenTick")) {
				data.noteSeen(rel.getIntOr("lastSeenX", 0), rel.getIntOr("lastSeenY", 0), rel.getIntOr("lastSeenZ", 0),
						rel.getLongOr("lastSeenTick", -1));
			}
			mind.relationships().restore(rel.read("agentId", UUIDUtil.CODEC).orElseThrow(), data);
		}

		List<Belief> beliefs = new ArrayList<>();
		ListTag beliefList = tag.getListOrEmpty("beliefs");
		for (int i = 0; i < beliefList.size(); i++) {
			CompoundTag b = beliefList.getCompoundOrEmpty(i);
			beliefs.add(new Belief(b.getLongOr("id", 0), b.getStringOr("statement", ""), b.getDoubleOr("confidence", 0),
					readProvenance(b), b.getLongOr("formedTick", 0)));
		}
		mind.restoreBeliefs(beliefs, tag.getLongOr("nextBeliefId", 1));

		List<Goal> goals = new ArrayList<>();
		ListTag goalList = tag.getListOrEmpty("goals");
		for (int i = 0; i < goalList.size(); i++) {
			CompoundTag g = goalList.getCompoundOrEmpty(i);
			IntentType relatedIntent = g.contains("relatedIntent") ? IntentType.valueOf(g.getStringOr("relatedIntent", "")) : null;
			goals.add(new Goal(g.getLongOr("id", 0), g.getStringOr("description", ""), g.getDoubleOr("priority", 0),
					relatedIntent, g.getLongOr("createdTick", 0), g.getBooleanOr("active", true)));
		}
		mind.restoreGoals(goals, tag.getLongOr("nextGoalId", 1));

		List<Possession> possessions = new ArrayList<>();
		ListTag possessionList = tag.getListOrEmpty("possessions");
		for (int i = 0; i < possessionList.size(); i++) {
			CompoundTag pos = possessionList.getCompoundOrEmpty(i);
			possessions.add(new Possession(pos.getStringOr("itemId", ""), pos.getIntOr("quantity", 0), pos.getLongOr("acquiredTick", 0)));
		}
		mind.restorePossessions(possessions);

		List<KnownDesign> designs = new ArrayList<>();
		ListTag designList = tag.getListOrEmpty("designs");
		for (int i = 0; i < designList.size(); i++) {
			CompoundTag d = designList.getCompoundOrEmpty(i);
			designs.add(new KnownDesign(readDesign(d), d.getStringOr("how", "saw"), d.getStringOr("source", ""),
					d.read("sourceId", UUIDUtil.CODEC).orElse(null), d.getLongOr("learnedTick", 0)));
		}
		mind.restoreDesigns(designs);

		tag.getCompound("home").ifPresent(h -> mind.setHome(new Home(h.getIntOr("x", 0), h.getIntOr("y", 0),
				h.getIntOr("z", 0), readDesign(h), h.getLongOr("builtTick", 0))));

		return mind;
	}

	private static CompoundTag writeDesign(Design design) {
		CompoundTag d = new CompoundTag();
		d.putString("designId", design.id());
		d.putString("designName", design.name());
		d.putString("layers", design.encodedLayers());
		return d;
	}

	private static Design readDesign(CompoundTag d) {
		Design design = new Design(d.getStringOr("designId", "hut"), d.getStringOr("designName", "hut"),
				Design.decodeLayers(d.getStringOr("layers", "")));
		return com.aicivilization.mind.DesignValidator.isValid(design) ? design : Design.hut();
	}

	private static CompoundTag writeMemory(MemoryEntry entry) {
		CompoundTag tag = new CompoundTag();
		tag.putLong("id", entry.id());
		tag.putLong("tick", entry.tick());
		tag.putString("description", entry.description());
		tag.putDouble("importance", entry.importance());
		ListTag participants = new ListTag();
		for (UUID participant : entry.participants()) {
			CompoundTag p = new CompoundTag();
			p.store("id", UUIDUtil.CODEC, participant);
			participants.add(p);
		}
		tag.put("participants", participants);
		writeProvenance(tag, entry.provenance());
		return tag;
	}

	private static MemoryEntry readMemory(CompoundTag tag) {
		ListTag participantsTag = tag.getListOrEmpty("participants");
		Set<UUID> participants = new HashSet<>();
		for (int i = 0; i < participantsTag.size(); i++) {
			participants.add(participantsTag.getCompoundOrEmpty(i).read("id", UUIDUtil.CODEC).orElseThrow());
		}
		return new MemoryEntry(tag.getLongOr("id", 0), tag.getLongOr("tick", 0), tag.getStringOr("description", ""),
				tag.getDoubleOr("importance", 0), participants, readProvenance(tag));
	}

	private static void writeProvenance(CompoundTag tag, Provenance provenance) {
		switch (provenance) {
			case Provenance.Perceived ignored -> tag.putString("provenanceKind", "PERCEIVED");
			case Provenance.Inferred inferred -> {
				tag.putString("provenanceKind", "INFERRED");
				tag.putLong("provenanceSourceMemoryId", inferred.sourceMemoryId());
			}
			case Provenance.Told told -> {
				tag.putString("provenanceKind", "TOLD");
				tag.store("provenanceTellerId", UUIDUtil.CODEC, told.tellerId());
				tag.putLong("provenanceTellerMemoryId", told.tellerMemoryId());
			}
		}
	}

	private static Provenance readProvenance(CompoundTag tag) {
		String kind = tag.getStringOr("provenanceKind", "");
		return switch (kind) {
			case "INFERRED" -> new Provenance.Inferred(tag.getLongOr("provenanceSourceMemoryId", 0));
			case "TOLD" -> new Provenance.Told(tag.read("provenanceTellerId", UUIDUtil.CODEC).orElseThrow(), tag.getLongOr("provenanceTellerMemoryId", 0));
			default -> new Provenance.Perceived();
		};
	}
}

package com.aicivilization.population;

import com.aicivilization.mind.AgentMind;
import com.aicivilization.mind.Belief;
import com.aicivilization.mind.Goal;
import com.aicivilization.mind.Identity;
import com.aicivilization.mind.IntentType;
import com.aicivilization.mind.MemoryEntry;
import com.aicivilization.mind.Needs;
import com.aicivilization.mind.Personality;
import com.aicivilization.mind.Possession;
import com.aicivilization.mind.Provenance;
import com.aicivilization.mind.RelationshipData;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * (De)serializes an {@link AgentMind} to/from NBT. This is Minecraft
 * persistence glue, deliberately kept out of the {@code mind} package
 * itself so that package stays free of Minecraft dependencies.
 */
final class AgentMindNbt {

	private AgentMindNbt() {
	}

	static NbtCompound write(AgentMind mind) {
		NbtCompound tag = new NbtCompound();

		Identity identity = mind.identity();
		tag.putUuid("id", identity.id());
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

		NbtList memoryList = new NbtList();
		for (MemoryEntry entry : mind.memories().all()) {
			memoryList.add(writeMemory(entry));
		}
		tag.put("memories", memoryList);
		tag.putLong("nextMemoryId", mind.memories().peekNextId());

		NbtList relationshipList = new NbtList();
		for (var e : mind.relationships().asMap().entrySet()) {
			NbtCompound rel = new NbtCompound();
			rel.putUuid("agentId", e.getKey());
			RelationshipData data = e.getValue();
			rel.putDouble("affinity", data.affinity());
			rel.putDouble("trust", data.trust());
			rel.putLong("lastInteractionTick", data.lastInteractionTick());
			rel.putInt("thingsLearnedFromThem", data.thingsLearnedFromThem());
			relationshipList.add(rel);
		}
		tag.put("relationships", relationshipList);

		NbtList beliefList = new NbtList();
		for (Belief belief : mind.beliefs()) {
			NbtCompound b = new NbtCompound();
			b.putLong("id", belief.id());
			b.putString("statement", belief.statement());
			b.putDouble("confidence", belief.confidence());
			b.putLong("formedTick", belief.formedTick());
			writeProvenance(b, belief.provenance());
			beliefList.add(b);
		}
		tag.put("beliefs", beliefList);
		tag.putLong("nextBeliefId", mind.nextBeliefIdPeek());

		NbtList goalList = new NbtList();
		for (Goal goal : mind.goals()) {
			NbtCompound g = new NbtCompound();
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

		NbtList possessionList = new NbtList();
		for (Possession possession : mind.possessions()) {
			NbtCompound pos = new NbtCompound();
			pos.putString("itemId", possession.itemId());
			pos.putInt("quantity", possession.quantity());
			pos.putLong("acquiredTick", possession.acquiredTick());
			possessionList.add(pos);
		}
		tag.put("possessions", possessionList);

		return tag;
	}

	static AgentMind read(NbtCompound tag) {
		Identity identity = new Identity(tag.getUuid("id"), tag.getString("name"), tag.getLong("birthTick"));
		Personality personality = new Personality(
				tag.getDouble("curiosity"), tag.getDouble("risk"),
				tag.getDouble("sociability"), tag.getDouble("ambition"));
		Needs needs = new Needs(
				tag.getDouble("foodNeed"), tag.getDouble("safetyNeed"),
				tag.getDouble("socialNeed"), tag.getDouble("belongingNeed"));

		AgentMind mind = new AgentMind(identity, personality, needs);
		if (!tag.getBoolean("alive")) {
			mind.markDead();
		}

		List<MemoryEntry> memories = new ArrayList<>();
		NbtList memoryList = tag.getList("memories", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < memoryList.size(); i++) {
			memories.add(readMemory(memoryList.getCompound(i)));
		}
		mind.memories().restoreState(memories, tag.getLong("nextMemoryId"));

		NbtList relationshipList = tag.getList("relationships", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < relationshipList.size(); i++) {
			NbtCompound rel = relationshipList.getCompound(i);
			RelationshipData data = new RelationshipData(
					rel.getDouble("affinity"), rel.getDouble("trust"),
					rel.getLong("lastInteractionTick"), rel.getInt("thingsLearnedFromThem"));
			mind.relationships().restore(rel.getUuid("agentId"), data);
		}

		List<Belief> beliefs = new ArrayList<>();
		NbtList beliefList = tag.getList("beliefs", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < beliefList.size(); i++) {
			NbtCompound b = beliefList.getCompound(i);
			beliefs.add(new Belief(b.getLong("id"), b.getString("statement"), b.getDouble("confidence"),
					readProvenance(b), b.getLong("formedTick")));
		}
		mind.restoreBeliefs(beliefs, tag.getLong("nextBeliefId"));

		List<Goal> goals = new ArrayList<>();
		NbtList goalList = tag.getList("goals", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < goalList.size(); i++) {
			NbtCompound g = goalList.getCompound(i);
			IntentType relatedIntent = g.contains("relatedIntent") ? IntentType.valueOf(g.getString("relatedIntent")) : null;
			goals.add(new Goal(g.getLong("id"), g.getString("description"), g.getDouble("priority"),
					relatedIntent, g.getLong("createdTick"), g.getBoolean("active")));
		}
		mind.restoreGoals(goals, tag.getLong("nextGoalId"));

		List<Possession> possessions = new ArrayList<>();
		NbtList possessionList = tag.getList("possessions", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < possessionList.size(); i++) {
			NbtCompound pos = possessionList.getCompound(i);
			possessions.add(new Possession(pos.getString("itemId"), pos.getInt("quantity"), pos.getLong("acquiredTick")));
		}
		mind.restorePossessions(possessions);

		return mind;
	}

	private static NbtCompound writeMemory(MemoryEntry entry) {
		NbtCompound tag = new NbtCompound();
		tag.putLong("id", entry.id());
		tag.putLong("tick", entry.tick());
		tag.putString("description", entry.description());
		tag.putDouble("importance", entry.importance());
		NbtList participants = new NbtList();
		for (UUID participant : entry.participants()) {
			net.minecraft.nbt.NbtCompound p = new NbtCompound();
			p.putUuid("id", participant);
			participants.add(p);
		}
		tag.put("participants", participants);
		writeProvenance(tag, entry.provenance());
		return tag;
	}

	private static MemoryEntry readMemory(NbtCompound tag) {
		NbtList participantsTag = tag.getList("participants", NbtElement.COMPOUND_TYPE);
		Set<UUID> participants = new HashSet<>();
		for (int i = 0; i < participantsTag.size(); i++) {
			participants.add(participantsTag.getCompound(i).getUuid("id"));
		}
		return new MemoryEntry(tag.getLong("id"), tag.getLong("tick"), tag.getString("description"),
				tag.getDouble("importance"), participants, readProvenance(tag));
	}

	private static void writeProvenance(NbtCompound tag, Provenance provenance) {
		switch (provenance) {
			case Provenance.Perceived ignored -> tag.putString("provenanceKind", "PERCEIVED");
			case Provenance.Inferred inferred -> {
				tag.putString("provenanceKind", "INFERRED");
				tag.putLong("provenanceSourceMemoryId", inferred.sourceMemoryId());
			}
			case Provenance.Told told -> {
				tag.putString("provenanceKind", "TOLD");
				tag.putUuid("provenanceTellerId", told.tellerId());
				tag.putLong("provenanceTellerMemoryId", told.tellerMemoryId());
			}
		}
	}

	private static Provenance readProvenance(NbtCompound tag) {
		String kind = tag.getString("provenanceKind");
		return switch (kind) {
			case "INFERRED" -> new Provenance.Inferred(tag.getLong("provenanceSourceMemoryId"));
			case "TOLD" -> new Provenance.Told(tag.getUuid("provenanceTellerId"), tag.getLong("provenanceTellerMemoryId"));
			default -> new Provenance.Perceived();
		};
	}
}

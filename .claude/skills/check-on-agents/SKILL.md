---
name: check-on-agents
description: Report what the agents on the live server have been doing — population, needs, goals, conversations, crises, deaths, and why individual agents made their choices — by reading the observer API. Use when asked "what are my agents doing", "check on the civilization", "any news", "why did <agent> do X", or for a daily/periodic summary.
---

# Check on the agents

The mod serves a read-only JSON API (`observer/ObserverServer.java`). Every
`/api/` call needs `?t=<observerToken>` unless the server sets
`observerRequireToken: false`.

## Reading it from a cloud session

Cloud sessions can't reach the observer port (8080) or SSH to the VM.
Instead, the VM publishes a snapshot about once a minute as **guest
attributes** in the `aiciv/` namespace. You can read them over HTTPS with
the access this environment already has (VM and zone are in
`deploy-to-server`):

```
gcloud compute instances get-guest-attributes $V --zone $Z \
  --project trusty-magnet-500500-s5 --query-path=aiciv/ --format=json
```

Each entry's `value` is a JSON string. There is no token in it:

| Key | Same as |
|---|---|
| `overview` | `/api/overview` |
| `agents` | `/api/agents` |
| `events` | `/api/events?exclude=DECISION` (latest page, roughly the last few minutes) |
| `stories` | `/api/stories` (newest 15 stories, compact: written headline/text/stands when written, and each one's record of events) |
| `digest` | `/api/digest` (each agent's last 7 game days, newest first: distance, longest time standing still, food eaten and where food came from, people met, lowest health, needs at day's end, how it died) |
| `alerts` | `/api/alerts` (standing alerts: starving, stuck a day away from home, homebound three days, deaths in the last three days, each with when first raised) |

Check freshness with `overview.observedAtMillis` (epoch ms). If it's more
than a few minutes old, the publisher or the server is down; say so. The
snapshot has no per-agent minds (`/api/agents/<uuid>`) or older events. For
"why did X do Y" or history, ask the user for their observer link and
the output of the live endpoints below.

## Live API (from a browser that can reach the server)

Every `/api/` call needs `?t=<observerToken>` unless the server sets
`observerRequireToken: false`. Never put the token in commits, PRs, or
published artifacts.

## Endpoints (all GET)

| Route | Returns |
|---|---|
| `/api/overview` | population counts, sim on/off, active hours, chronicle, body/chunk status |
| `/api/agents` | one card per agent: needs, activity, top goal, position |
| `/api/agents/<uuid>` | full mind: goals, beliefs, relationships, memories with provenance, latest decision trace |
| `/api/events?limit=&since=&before=&type=&exclude=&agent=` | newest-first event page; `type`/`exclude` take an `EventType` (SPAWN, DECISION, TOLD, CONVERSATION, REASONING_INVOKED, REASONING_RESULT, NEED_CRISIS, DEATH, …) |
| `/api/events/<id>` | one event with its causes |

Example: `curl -s "http://$HOST:8080/api/events?t=$TOKEN&exclude=DECISION&limit=50"`.

## What to report

For patterns over days (who is starving and why, who never moves, who
eats what), use `digest` rather than the 80-event snapshot. Check
`alerts` first.

Start from `stories`: each is a thread of what someone thought, did and
how it turned out, already written up. Quote or summarise those, and check
a write-up against its `record` before repeating a claim. `routine` counts
everyday work per person per day.

Lead with what changed and what's surprising — this is an emergence
experiment, so the unscripted parts are the news:
- Population: alive / dead / bodies loaded; sim on, inside active hours?
- Notable events: deaths, need crises, information passed along (`TOLD`
  chains — who learned what from whom), new beliefs and goals.
- Standouts: who is struggling (lowest needs), who is most social, any agent
  whose history suggests an emerging specialization (describe it; never
  claim it was assigned).
- Health problems: bodies not loaded, scan errors, reasoning falling back to
  the heuristic, no events for a long time.

For "why did X do Y", fetch `/api/agents/<uuid>` and walk the latest decision
trace: candidates, scores, factors, and the causes behind the winner.

Offer to publish a visual summary as an artifact (without the token).

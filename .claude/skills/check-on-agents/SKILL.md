---
name: check-on-agents
description: Report what the agents on the live server have been doing — population, needs, goals, conversations, crises, deaths, and why individual agents made their choices — by reading the observer API. Use when asked "what are my agents doing", "check on the civilization", "any news", "why did <agent> do X", or for a daily/periodic summary.
---

# Check on the agents

The mod serves a read-only JSON API (`observer/ObserverServer.java`). Every
`/api/` call needs `?t=<observerToken>` unless the server sets
`observerRequireToken: false`.

## Address and token

Ask the user for their observer link (`http://<host>:8080/?t=<token>`), or
read the token from `config/aicivilization.json` on the VM via
`gcloud compute ssh` (see `deploy-to-server` for finding the VM). Never put
the token in commits, PRs, or published artifacts.

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

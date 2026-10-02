'use strict';
// Civilization Observer: read-only views over /api/*. Agent names, beliefs and
// summaries can come from LLM output, so everything is built with DOM methods
// and textContent; nothing is ever inserted as HTML.
(function () {
	const POLL_MS = 5000;
	const TICKS_PER_DAY = 24000;
	const NEEDS = ['food', 'safety', 'social', 'belonging'];
	const EVENT_TYPES = ['SPAWN', 'PERCEIVED', 'DECISION', 'CONVERSATION', 'TOLD',
		'REASONING_INVOKED', 'REASONING_RESULT', 'NEED_CRISIS', 'DEATH', 'ACTION', 'MILESTONE'];
	const TOKEN_KEY = 'observer-token';

	const main = document.getElementById('main');
	const clock = document.getElementById('clock');
	let token = new URLSearchParams(location.search).get('t') || readStoredToken();
	let pollTimer = null;
	let routeId = 0;

	// ---------------------------------------------------------------- helpers

	function readStoredToken() {
		try { return localStorage.getItem(TOKEN_KEY) || ''; } catch (e) { return ''; }
	}

	function storeToken(value) {
		try { localStorage.setItem(TOKEN_KEY, value); } catch (e) { /* private mode: URL still works */ }
	}

	function h(tag, attrs, ...kids) {
		const el = document.createElement(tag);
		for (const [k, v] of Object.entries(attrs || {})) {
			if (v === null || v === undefined || v === false) continue;
			if (k === 'class') el.className = v;
			else if (k === 'text') el.textContent = v;
			else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
			else if (k === 'style') el.style.cssText = v;
			else el.setAttribute(k, v);
		}
		for (const kid of kids.flat(Infinity)) {
			if (kid === null || kid === undefined || kid === false) continue;
			el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
		}
		return el;
	}

	async function api(path, params) {
		const q = new URLSearchParams();
		for (const [k, v] of Object.entries(params || {})) {
			if (v !== null && v !== undefined && v !== '') q.set(k, v);
		}
		q.set('t', token);
		const res = await fetch('/api/' + path + '?' + q, { cache: 'no-store' });
		if (res.status === 401) throw { auth: true };
		if (!res.ok) throw { status: res.status };
		return res.json();
	}

	function fmtTick(t) {
		const day = Math.floor(t / TICKS_PER_DAY);
		const tod = ((t % TICKS_PER_DAY) + TICKS_PER_DAY) % TICKS_PER_DAY;
		return 'Day ' + day + ' · ' + fmtTime(tod);
	}

	function fmtTime(tod) {
		const hours = (Math.floor(tod / 1000) + 6) % 24;
		const mins = Math.floor((tod % 1000) * 60 / 1000);
		return String(hours).padStart(2, '0') + ':' + String(mins).padStart(2, '0');
	}

	function fmtAge(ticks) {
		const days = ticks / TICKS_PER_DAY;
		if (days >= 1) return days.toFixed(1) + ' days old';
		return Math.round(ticks / 1000) + ' hours old';
	}

	function num(v, digits) {
		return typeof v === 'number' ? v.toFixed(digits === undefined ? 2 : digits) : '–';
	}

	function label(s) {
		if (!s) return '';
		return s.toLowerCase().replace(/_/g, ' ');
	}

	function agentLink(person) {
		if (!person) return null;
		return h('a', { href: '#/agents/' + person.id }, person.name || 'unknown');
	}

	function causeChip(cause, agentId) {
		const raw = cause.detail || (label(cause.type) + ' ' + cause.sourceId);
		const text = raw.replace(/-?\d+\.\d{3,}/g, x => Number(x).toFixed(2));
		if (cause.type === 'EVENT') {
			return h('a', { class: 'chip', href: '#/events/' + cause.sourceId, title: 'Open event #' + cause.sourceId }, text);
		}
		if (cause.type === 'MEMORY' && agentId) {
			return h('a', { class: 'chip', href: '#/agents/' + agentId + '/m/' + cause.sourceId,
				title: 'Open memory #' + cause.sourceId }, text);
		}
		return h('span', { class: 'chip', title: label(cause.type) }, text);
	}

	function causes(list, agentId) {
		if (!list || list.length === 0) return h('span', { class: 'meta' }, 'no recorded causes');
		return h('div', { class: 'chips' }, list.map(c => causeChip(c, agentId)));
	}

	function needBars(needs) {
		return h('div', { class: 'needs' }, NEEDS.map(n => {
			const v = Math.max(0, Math.min(1, needs[n] || 0));
			return [
				h('span', {}, n),
				h('div', { class: 'track' }, h('div', { class: 'fill',
					style: 'width:' + (v * 100).toFixed(0) + '%;background:var(--' + n + ')' })),
				h('span', { class: 'num' }, num(v)),
			];
		}));
	}

	function agentTags(a) {
		return [
			!a.alive ? h('span', { class: 'tag plain' }, 'dead') : null,
			a.alive && a.crisis ? h('span', { class: 'tag warn' }, label(a.lowestNeed) + ' crisis') : null,
			a.alive && a.currentIntent ? h('span', { class: 'tag' }, label(a.currentIntent)) : null,
		];
	}

	function where(position) {
		if (!position) return 'not loaded';
		return Math.round(position.x) + ', ' + Math.round(position.y) + ', ' + Math.round(position.z);
	}

	function provenanceText(p) {
		if (!p) return null;
		if (p.type === 'PERCEIVED') return 'saw it';
		if (p.type === 'INFERRED') return 'worked it out from memory #' + p.sourceMemoryId;
		if (p.type === 'TOLD') {
			return h('span', {}, 'told by ',
				h('a', { href: '#/agents/' + p.tellerId + '/m/' + p.tellerMemoryId }, p.tellerName || 'someone'));
		}
		return label(p.type);
	}

	function panel(title, ...body) {
		return h('section', { class: 'panel' }, title ? h('h2', {}, title) : null, body);
	}

	function eventRows(events) {
		return collapse(events, e => e.type + '|' + e.summary).map(g => eventRow(g.item, g.count));
	}

	function eventRow(e, count) {
		return h('li', { class: 'ev' },
			h('div', {}, h('span', { class: 'type' }, label(e.type)), ' · ',
				h('span', { class: 'meta' }, fmtTick(e.tick))),
			h('div', {}, h('a', { href: '#/events/' + e.id }, e.summary), times(count)),
			e.subjects.length ? h('div', { class: 'meta' }, e.subjects.map((s, i) => [i ? ', ' : '', agentLink(s)])) : null);
	}

	function show(...nodes) {
		main.replaceChildren(...nodes.flat(Infinity).filter(n => n !== null && n !== undefined && n !== false));
	}

	// The simulation often repeats itself (the same exchange many times in a
	// row), so runs of identical consecutive items are shown once with a count.
	function collapse(items, keyOf) {
		const groups = [];
		for (const item of items) {
			const last = groups[groups.length - 1];
			if (last && keyOf(last.item) === keyOf(item)) last.count++;
			else groups.push({ item, count: 1 });
		}
		return groups;
	}

	function times(count) {
		return count > 1 ? h('span', { class: 'count' }, '×' + count) : null;
	}

	const expanded = {};

	function showError(err) {
		if (err && err.auth) {
			renderTokenForm('That token was not accepted.');
			return;
		}
		show(h('div', { class: 'notice' }, 'Could not reach the server' +
			(err && err.status ? ' (HTTP ' + err.status + ')' : '') + '. Retrying…'));
	}

	function setTab(tab) {
		for (const a of document.querySelectorAll('#nav a')) {
			a.classList.toggle('on', a.dataset.tab === tab);
		}
	}

	function clockText(o) {
		return 'Day ' + o.day + ' · ' + fmtTime(o.timeOfDay) + (o.simulationEnabled === false ? ' · AI off' : '');
	}

	async function updateClock() {
		try {
			const o = await api('overview');
			if (o.ready) clock.textContent = clockText(o);
			return o;
		} catch (e) {
			return null;
		}
	}

	// ----------------------------------------------------------------- views

	function renderTokenForm(message) {
		setTab(null);
		const input = h('input', { type: 'password', placeholder: 'Observer token', autocomplete: 'off' });
		const go = () => {
			const value = input.value.trim();
			if (!value) return;
			token = value;
			storeToken(value);
			const url = new URL(location.href);
			url.searchParams.set('t', value);
			history.replaceState(null, '', url);
			route();
		};
		show(panel('Token needed',
			message ? h('div', { class: 'notice' }, message) : null,
			h('p', {}, 'Open the link with ?t=… from the server log, or paste the observerToken from config/aicivilization.json.'),
			h('div', { class: 'tokenbox' }, input, h('button', { class: 'btn', onclick: go }, 'Open'))));
		input.addEventListener('keydown', e => { if (e.key === 'Enter') go(); });
	}

	async function viewOverview(myRoute) {
		setTab('overview');
		const [o, agents] = await Promise.all([api('overview'), api('agents')]);
		if (myRoute !== routeId) return;
		if (!o.ready) {
			show(panel(null, h('p', { class: 'empty' }, 'The server is still starting up.')));
			return;
		}
		clock.textContent = clockText(o);
		const inCrisis = agents.filter(a => a.alive && a.crisis);
		const off = o.simulationEnabled === false;
		show(
			off ? h('div', { class: 'notice' }, 'The AI is switched off: agents are frozen and no AI calls are made. ' +
				'An operator can run /civ on in game to resume.') : null,
			h('div', { class: 'stats' },
				stat(off ? 'Off' : 'On', 'simulation'),
				stat(o.day, 'day'),
				stat(o.alive, 'agents alive'),
				stat(o.dead, 'agents dead'),
				stat(o.loadedBodies, 'bodies loaded'),
				stat(o.reasoningProvider === 'anthropic' ? 'Claude' : 'Heuristic', 'reasoning')),
			h('div', { style: 'height:12px' }),
			inCrisis.length ? panel('In crisis', h('ul', { class: 'list' }, inCrisis.map(a =>
				h('li', {}, agentLink(a), ' ', h('span', { class: 'tag warn' }, label(a.lowestNeed)),
					h('div', { class: 'meta' }, a.topGoal || 'no active goal'))))) : null,
			panel('Chronicle', o.chronicle.length
				? h('ul', { class: 'list' }, collapse(o.chronicle, l => l.text).map(g => h('li', {},
					h('a', { href: '#/events/' + g.item.eventId }, g.item.text), times(g.count),
					h('div', { class: 'meta' }, 'Day ' + g.item.day))))
				: h('p', { class: 'empty' }, 'Nothing has happened yet. Try /civ spawn 3 in game.')));
	}

	function stat(value, caption) {
		return h('div', { class: 'stat' }, h('b', {}, String(value)), h('span', {}, caption));
	}

	async function viewAgents(myRoute) {
		setTab('agents');
		const agents = await api('agents');
		if (myRoute !== routeId) return;
		updateClock();
		agents.sort((a, b) => (b.alive - a.alive) || a.name.localeCompare(b.name));
		show(agents.length
			? h('div', { class: 'cards' }, agents.map(a => h('a', { class: 'card' + (a.alive ? '' : ' dead'), href: '#/agents/' + a.id },
				h('div', { class: 'row' }, h('span', { class: 'name' }, a.name), agentTags(a)),
				needBars(a.needs),
				h('div', {}, a.topGoal || h('span', { class: 'meta' }, 'no active goal')),
				h('div', { class: 'meta' }, where(a.position) + ' · ' + fmtAge(a.ageTicks)))))
			: panel(null, h('p', { class: 'empty' }, 'No agents yet. Try /civ spawn 3 in game.')));
	}

	const decisionChoice = {};

	async function viewAgent(myRoute, id, memoryId) {
		setTab('agents');
		const a = await api('agents/' + id);
		if (myRoute !== routeId) return;
		updateClock();
		const picked = Math.min(decisionChoice[id] || 0, Math.max(0, a.decisions.length - 1));
		const memIds = new Set(a.memories.map(m => String(m.id)));

		show(
			h('a', { class: 'back', href: '#/agents' }, '← All agents'),
			panel(null,
				h('div', { class: 'row', style: 'display:flex;gap:8px;align-items:baseline;flex-wrap:wrap' },
					h('span', { style: 'font-size:22px;font-weight:700' }, a.name), agentTags(a)),
				h('div', { class: 'meta' }, fmtAge(a.ageTicks) + ' · ' + where(a.position) +
					' · ' + a.memoryCount + ' memories')),
			whyPanel(a, picked),
			panel('Carrying', (a.inventory && a.inventory.length)
				? h('div', {}, a.inventory.map(i => h('span', { class: 'tag' }, i.quantity + ' × ' + i.itemId.replace(/^minecraft:/, '').replace(/_/g, ' '))))
				: h('div', { class: 'meta' }, 'Nothing.')),
			h('div', { class: 'grid2' },
				panel('Needs', needBars(a.needs)),
				panel('Personality', h('div', { class: 'needs' }, ['curiosity', 'risk', 'sociability', 'ambition'].map(k => [
					h('span', {}, k),
					h('div', { class: 'track' }, h('div', { class: 'fill',
						style: 'width:' + ((a.personality[k] || 0) * 100).toFixed(0) + '%;background:var(--accent)' })),
					h('span', { class: 'num' }, num(a.personality[k])),
				])))),
			h('div', { class: 'grid2' },
				panel('Goals', a.goals.length ? h('ul', { class: 'list' }, a.goals.map(g => h('li', {},
					h('div', {}, g.description, ' ', g.active ? null : h('span', { class: 'tag plain' }, 'done')),
					h('div', { class: 'meta' }, 'priority ' + num(g.priority) + (g.intent ? ' · ' + label(g.intent) : '') +
						' · ' + fmtTick(g.createdTick))))) : h('p', { class: 'empty' }, 'No goals yet.')),
				panel('Beliefs', a.beliefs.length ? h('ul', { class: 'list' }, a.beliefs.map(b => h('li', {},
					h('div', {}, b.statement),
					h('div', { class: 'meta' }, 'confidence ' + num(b.confidence) + ' · ', provenanceText(b.provenance),
						' · ' + fmtTick(b.formedTick))))) : h('p', { class: 'empty' }, 'No beliefs yet.'))),
			panel('Relationships', a.relationships.length ? h('ul', { class: 'list' }, a.relationships.map(r => h('li', {},
				h('div', {}, agentLink(r)),
				h('div', { class: 'meta' }, 'affinity ' + num(r.affinity) + ' · trust ' + num(r.trust) +
					' · learned ' + r.thingsLearned + ' · last ' + fmtTick(r.lastInteractionTick))))) :
				h('p', { class: 'empty' }, 'Hasn\'t met anyone yet.')),
			panel('Memories',
				memoryId && !memIds.has(String(memoryId))
					? h('div', { class: 'notice' }, 'Memory #' + memoryId + ' is older than the ' + a.memories.length + ' most recent shown here.')
					: null,
				a.memories.length ? memoryList(a, memoryId) : h('p', { class: 'empty' }, 'No memories yet.')),
			panel('Recent events', a.recentEvents.length
				? h('ul', { class: 'list' }, eventRows(a.recentEvents))
				: h('p', { class: 'empty' }, 'No events yet.'),
				h('p', {}, h('a', { href: '#/timeline/agent/' + a.id }, 'All of ' + a.name + '\'s events in the timeline →'))));

		if (memoryId && memIds.has(String(memoryId)) && !viewAgent.scrolled) {
			viewAgent.scrolled = true;
			const el = document.getElementById('mem-' + memoryId);
			if (el) el.scrollIntoView({ block: 'center' });
		}
	}

	const MEMORY_GROUPS_SHOWN = 12;

	function memoryList(a, memoryId) {
		// Group repeats, but never fold the highlighted memory into another.
		const groups = collapse(a.memories, m => String(m.id) === String(memoryId) ? 'hl' + m.id : m.description);
		const highlightAt = groups.findIndex(g => String(g.item.id) === String(memoryId));
		const all = expanded[a.id] || highlightAt >= MEMORY_GROUPS_SHOWN;
		const shown = all ? groups : groups.slice(0, MEMORY_GROUPS_SHOWN);
		return [
			h('ul', { class: 'list' }, shown.map(g => memoryRow(g.item, g.count, memoryId))),
			!all && groups.length > shown.length ? h('p', {}, h('button', { class: 'btn',
				onclick: () => { expanded[a.id] = true; route(true); } }, 'Show all ' + groups.length)) : null,
		];
	}

	function memoryRow(m, count, memoryId) {
		return h('li', { id: 'mem-' + m.id, class: String(m.id) === String(memoryId) ? 'hl' : null },
					h('div', {}, m.description, times(count)),
					h('div', { class: 'meta' }, '#' + m.id + ' · ' + fmtTick(m.tick) + ' · importance ' + num(m.importance) +
						' · ', provenanceText(m.provenance),
						m.participants.length ? [' · with ', m.participants.map((p, i) => [i ? ', ' : '', agentLink(p)])] : null));
	}

	function whyPanel(a, picked) {
		if (!a.decisions.length) {
			return panel('Why', h('p', { class: 'empty' }, a.name + ' hasn\'t made a decision yet.'));
		}
		const d = a.decisions[picked];
		const maxScore = Math.max(...d.candidates.map(c => Math.abs(c.score)), 0.0001);
		return panel('Why',
			h('p', { style: 'margin-top:0' }, 'At ' + fmtTick(d.tick) + ', ' + a.name + ' chose ',
				h('b', {}, label(d.chosen)), '. Options considered, best first:'),
			h('div', { class: 'why' }, d.candidates.map(c => h('div', { class: 'cand' + (c.intent === d.chosen ? ' chosen' : '') },
				h('div', { class: 'head' }, h('span', { class: 'label' }, label(c.intent)),
					h('span', { class: 'score' }, num(c.score, 3))),
				h('div', { class: 'scorebar' }, h('div', { style: 'width:' + (Math.max(0, c.score) / maxScore * 100).toFixed(0) + '%' })),
				h('div', { class: 'chips' }, Object.entries(c.factors).map(([k, v]) =>
					h('span', { class: 'chip', title: k }, k + ' ' + (v >= 0 ? '+' : '') + num(v))))))),
			h('div', { style: 'margin-top:10px' }, h('div', { class: 'meta', style: 'margin-bottom:4px' }, 'Because of'),
				causes(d.causes, a.id)),
			a.decisions.length > 1 ? h('div', { style: 'margin-top:12px' },
				h('div', { class: 'meta', style: 'margin-bottom:4px' }, 'Earlier decisions'),
				h('div', { class: 'chips' }, a.decisions.map((x, i) => h('button', {
					class: 'chip', style: i === picked ? 'background:var(--accent-soft)' : null,
					onclick: () => { decisionChoice[a.id] = i; route(true); },
				}, fmtTick(x.tick) + ' · ' + label(x.chosen))))) : null);
	}

	async function viewEvent(myRoute, id) {
		setTab('timeline');
		let e;
		try {
			e = await api('events/' + id);
		} catch (err) {
			if (err && err.status === 404) {
				if (myRoute !== routeId) return;
				show(h('a', { class: 'back', href: '#/timeline' }, '← Timeline'),
					panel(null, h('p', { class: 'empty' }, 'Event #' + id + ' is older than the recent events the server keeps.')));
				return;
			}
			throw err;
		}
		if (myRoute !== routeId) return;
		updateClock();
		const first = e.subjects[0] && e.subjects[0].id;
		show(
			h('a', { class: 'back', href: '#/timeline' }, '← Timeline'),
			panel(label(e.type) + ' · #' + e.id,
				h('p', { style: 'font-size:17px;margin:0 0 6px' }, e.summary),
				h('div', { class: 'meta' }, fmtTick(e.tick)),
				e.subjects.length ? h('p', {}, 'Involves ', e.subjects.map((s, i) => [i ? ', ' : '', agentLink(s)])) : null),
			panel('Because of', causes(e.causes, first)));
	}

	const timeline = { key: null, events: [], more: false };

	async function viewTimeline(myRoute, agentFilter) {
		setTab('timeline');
		const params = new URLSearchParams(location.hash.split('?')[1] || '');
		// Decisions are the most frequent event, so they're hidden unless asked
		// for (each agent's Why panel shows them).
		const type = params.get('type') || '';
		const exclude = type ? '' : 'DECISION';
		const agent = agentFilter || params.get('agent') || '';
		const key = type + '|' + agent;
		const agents = await api('agents');
		if (key !== timeline.key) {
			const page = await api('events', { limit: 60, type, exclude, agent });
			timeline.key = key;
			timeline.events = page.events;
			timeline.more = page.more;
		} else if (timeline.events.length) {
			const page = await api('events', { since: timeline.events[0].id, limit: 200, type, exclude, agent });
			timeline.events = page.events.concat(timeline.events);
		} else {
			const page = await api('events', { limit: 60, type, exclude, agent });
			timeline.events = page.events;
			timeline.more = page.more;
		}
		if (myRoute !== routeId) return;
		updateClock();

		const setFilter = (t, a) => {
			const q = new URLSearchParams();
			if (t) q.set('type', t);
			if (a) q.set('agent', a);
			location.hash = '#/timeline' + (q.toString() ? '?' + q : '');
		};
		const typeSel = h('select', { onchange: () => setFilter(typeSel.value, agentSel.value) },
			h('option', { value: '' }, 'All but decisions'),
			EVENT_TYPES.map(t => h('option', { value: t, selected: t === type ? 'selected' : null }, label(t))));
		const agentSel = h('select', { onchange: () => setFilter(typeSel.value, agentSel.value) },
			h('option', { value: '' }, 'All agents'),
			agents.slice().sort((x, y) => x.name.localeCompare(y.name)).map(a =>
				h('option', { value: a.id, selected: a.id === agent ? 'selected' : null }, a.name)));
		const older = async () => {
			const last = timeline.events[timeline.events.length - 1];
			const page = await api('events', { before: last.id, limit: 60, type, exclude, agent });
			timeline.events = timeline.events.concat(page.events);
			timeline.more = page.more;
			route(true);
		};
		show(
			h('div', { class: 'filters' }, typeSel, agentSel),
			panel(null, timeline.events.length
				? h('ul', { class: 'list' }, eventRows(timeline.events))
				: h('p', { class: 'empty' }, 'No events match.'),
				timeline.more ? h('p', {}, h('button', { class: 'btn', onclick: older }, 'Load older')) : null));
	}

	// ---------------------------------------------------------------- router

	async function route(keepState) {
		if (!keepState) {
			routeId++;
			viewAgent.scrolled = false;
		}
		const myRoute = routeId;
		clearTimeout(pollTimer);
		if (!token) {
			renderTokenForm();
			return;
		}
		const path = location.hash.replace(/^#/, '').split('?')[0] || '/';
		const parts = path.split('/').filter(Boolean);
		try {
			if (parts.length === 0) await viewOverview(myRoute);
			else if (parts[0] === 'agents' && parts.length === 1) await viewAgents(myRoute);
			else if (parts[0] === 'agents') await viewAgent(myRoute, parts[1], parts[2] === 'm' ? parts[3] : null);
			else if (parts[0] === 'events' && parts[1]) await viewEvent(myRoute, parts[1]);
			else if (parts[0] === 'timeline') await viewTimeline(myRoute, parts[1] === 'agent' ? parts[2] : null);
			else await viewOverview(myRoute);
		} catch (err) {
			if (myRoute !== routeId) return;
			if (err && err.auth) {
				renderTokenForm('That token was not accepted.');
				return;
			}
			showError(err);
		}
		if (myRoute === routeId) {
			pollTimer = setTimeout(() => { if (!document.hidden) route(true); else route.pending = true; }, POLL_MS);
		}
	}

	document.addEventListener('visibilitychange', () => {
		if (!document.hidden && route.pending) {
			route.pending = false;
			route(true);
		}
	});
	window.addEventListener('hashchange', () => route());
	if (new URLSearchParams(location.search).get('t')) storeToken(token);
	route();
})();

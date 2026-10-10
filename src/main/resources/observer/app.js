'use strict';
// The Settlement Chronicle: read-only views over /api/*. Agent names, beliefs,
// conversations and summaries can come from LLM output, so everything is built
// with DOM methods and textContent; nothing is ever inserted as HTML.
(function () {
	const POLL_MS = 5000;
	const TICKS_PER_DAY = 24000;
	const TOKEN_KEY = 'observer-token';

	const main = document.getElementById('main');
	const clock = document.getElementById('clock');
	let token = new URLSearchParams(location.search).get('t') || readStoredToken();
	let pollTimer = null;
	let routeId = 0;

	// ---------------------------------------------------------------- basics

	function readStoredToken() {
		try { return localStorage.getItem(TOKEN_KEY) || ''; } catch (e) { return ''; }
	}

	function storeToken(value) {
		try { localStorage.setItem(TOKEN_KEY, value); } catch (e) { /* private mode: the URL still works */ }
	}

	function h(tag, attrs, ...kids) {
		const el = document.createElement(tag);
		for (const [k, v] of Object.entries(attrs || {})) {
			if (v === null || v === undefined || v === false) continue;
			if (k === 'class') el.className = v;
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

	function svg(tag, attrs, ...kids) {
		const el = document.createElementNS('http://www.w3.org/2000/svg', tag);
		for (const [k, v] of Object.entries(attrs || {})) el.setAttribute(k, v);
		for (const kid of kids.flat(Infinity)) if (kid) el.append(kid);
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

	function show(...nodes) {
		main.replaceChildren(...nodes.flat(Infinity).filter(n => n !== null && n !== undefined && n !== false));
	}

	function setTab(tab) {
		for (const a of document.querySelectorAll('#nav a')) a.classList.toggle('on', a.dataset.tab === tab);
	}

	// ----------------------------------------------------------------- words

	function dayOf(t) { return Math.floor(t / TICKS_PER_DAY); }

	function clockTime(t) {
		const tod = ((t % TICKS_PER_DAY) + TICKS_PER_DAY) % TICKS_PER_DAY;
		const hours = (Math.floor(tod / 1000) + 6) % 24;
		const mins = Math.floor((tod % 1000) * 60 / 1000);
		return String(hours).padStart(2, '0') + ':' + String(mins).padStart(2, '0');
	}

	function partOfDay(tod) {
		const hour = (Math.floor(tod / 1000) + 6) % 24;
		if (hour < 5) return 'night';
		if (hour < 8) return 'dawn';
		if (hour < 12) return 'morning';
		if (hour < 17) return 'afternoon';
		if (hour < 20) return 'evening';
		return 'night';
	}

	function when(t) { return 'Day ' + dayOf(t) + ', ' + clockTime(t); }

	function age(ticks) {
		const days = Math.floor(ticks / TICKS_PER_DAY);
		return days >= 1 ? days + (days === 1 ? ' day old' : ' days old') : 'new today';
	}

	function itemName(id) { return (id || '').replace(/^[^:]*:/, '').replace(/_/g, ' '); }

	function isTool(id) { return /_(pickaxe|axe|shovel|sword|hoe)$/.test(id); }

	function place(p) {
		return p ? Math.round(p.x) + ', ' + Math.round(p.z) : 'somewhere out of sight';
	}

	const DOING = {
		FORAGE_FOOD: 'looking for food', SEEK_SAFETY: 'looking for somewhere safe', SOCIALIZE: 'looking for company',
		EXPLORE: 'exploring', REST: 'resting', IDLE: 'idling', GATHER_MATERIALS: 'gathering materials',
		BUILD_SHELTER: 'building a home', FARM: 'farming', GO_HOME: 'heading home', FIGHT: 'fighting off a monster',
	};

	function doing(a) {
		if (!a.alive) return 'died';
		if (a.currentIntent === 'PURSUE_PLAN') return a.planGoal ? 'working toward: ' + a.planGoal : 'working on a plan';
		return DOING[a.currentIntent] || 'just arrived';
	}

	const NEED_WORDS = {
		food: [[0.15, 'starving'], [0.35, 'hungry'], [0.7, 'fed'], [2, 'well fed']],
		safety: [[0.15, 'terrified'], [0.35, 'uneasy'], [0.7, 'safe enough'], [2, 'safe']],
		social: [[0.15, 'very lonely'], [0.35, 'lonely'], [0.7, 'some company'], [2, 'good company']],
		belonging: [[0.15, 'rootless'], [0.35, 'unsettled'], [0.7, 'settled'], [2, 'at home']],
	};

	function needWord(need, v) {
		for (const [limit, word] of NEED_WORDS[need]) if (v < limit) return word;
		return '';
	}

	/** The one thing worth flagging about someone at a glance: their worst need, if it's actually low. */
	function trouble(needs) {
		const worst = Object.keys(NEED_WORDS).reduce((a, b) => (needs[a] ?? 1) <= (needs[b] ?? 1) ? a : b);
		return (needs[worst] || 0) < 0.35 ? needWord(worst, needs[worst] || 0) : null;
	}

	function character(p) {
		const words = [];
		const pick = (v, high, low) => { if (v >= 0.65) words.push(high); else if (v <= 0.35) words.push(low); };
		pick(p.curiosity, 'curious', 'incurious');
		pick(p.risk, 'bold', 'cautious');
		pick(p.sociability, 'sociable', 'reserved');
		pick(p.ambition, 'driven', 'easygoing');
		if (!words.length) return 'Even-tempered, nothing extreme.';
		const s = words.length === 1 ? words[0] : words.slice(0, -1).join(', ') + ' and ' + words[words.length - 1];
		return s.charAt(0).toUpperCase() + s.slice(1) + '.';
	}

	function bond(r) {
		if (r.affinity > 0.6) return 'close friend' + (r.trust > 0.6 ? ', trusts them' : '');
		if (r.affinity > 0.25) return 'friend' + (r.trust > 0.6 ? ', trusts them' : '');
		if (r.affinity > -0.1) return 'acquaintance';
		if (r.affinity > -0.4) return 'wary of them';
		return 'dislikes them';
	}

	function factorText(k, v) { return k + ' (' + (v >= 0 ? '+' : '') + v.toFixed(2) + ')'; }

	// ------------------------------------------------------------- portraits

	/** A small pixel face, the same for the same name every time. */
	function face(name, size, dead) {
		let x = 2166136261;
		for (const c of name || '?') { x ^= c.charCodeAt(0); x = Math.imul(x, 16777619) >>> 0; }
		// Kept unsigned throughout: a negative state gave a negative index, and a face with no colours (solid black).
		const r = n => { x = Math.imul(x ^ (x >>> 15), 2246822507) >>> 0; x = (x ^ (x >>> 13)) >>> 0; return x % n; };
		const skins = ['#f1d3b6', '#e3b98f', '#c99b6d', '#a0714f', '#7a5034', '#5a3a26'];
		const hairs = ['#2b1f16', '#5a3820', '#8a5a2b', '#c49a52', '#1b1b1b', '#8c8c8c', '#9a3c22', '#e2d2a8'];
		const eyes = ['#3b5f8a', '#4c6a35', '#5a3820', '#2b2b2b'];
		const skin = skins[r(skins.length)], hair = hairs[r(hairs.length)], eye = eyes[r(eyes.length)];
		const fringe = 1 + r(2), sideburns = r(2), beard = r(4) === 0, cap = r(5) === 0;
		const px = [];
		const set = (cx, cy, c) => px.push(svg('rect', { x: cx, y: cy, width: 1, height: 1, fill: c }));
		for (let yy = 0; yy < 8; yy++) for (let xx = 0; xx < 8; xx++) set(xx, yy, skin);
		for (let yy = 0; yy <= fringe; yy++) for (let xx = 0; xx < 8; xx++) set(xx, yy, cap ? '#6b2e1a' : hair);
		if (sideburns) for (let yy = fringe + 1; yy < 5; yy++) { set(0, yy, hair); set(7, yy, hair); }
		set(1, 4, '#ffffff'); set(2, 4, eye); set(5, 4, eye); set(6, 4, '#ffffff');
		set(3, 5, 'rgba(0,0,0,.18)'); set(4, 5, 'rgba(0,0,0,.18)');
		for (let xx = 2; xx < 6; xx++) set(xx, 6, beard ? hair : 'rgba(0,0,0,.28)');
		if (beard) for (let xx = 1; xx < 7; xx++) set(xx, 7, hair);
		return svg('svg', { class: 'face' + (dead ? ' dead' : ''), width: size, height: size, viewBox: '0 0 8 8',
			'shape-rendering': 'crispEdges', role: 'img', 'aria-label': name || '' }, px);
	}

	function personLink(p) {
		return p ? h('a', { href: '#/people/' + p.id }, p.name || 'someone') : null;
	}

	function list(items, sep) {
		return items.map((x, i) => [i ? (i === items.length - 1 ? ' and ' : sep || ', ') : '', x]);
	}

	// ------------------------------------------------------------------- sky

	// Sky colours (top, horizon) through the game day, keyed by time of day in ticks.
	const SKY = [
		[0, '#5d8fd0', '#f4c9a0'], [1500, '#6fa6e2', '#cfe4f6'], [6000, '#5e9de4', '#bfe0fb'],
		[10500, '#6f95cf', '#f2c58f'], [12200, '#3e3f7a', '#ef8a5a'], [13500, '#121a3d', '#3a3466'],
		[18000, '#070b1f', '#18203f'], [22500, '#152048', '#4a3f6e'], [24000, '#5d8fd0', '#f4c9a0'],
	];

	function mix(a, b, f) {
		const pa = parseInt(a.slice(1), 16), pb = parseInt(b.slice(1), 16);
		const c = [16, 8, 0].map(sh => Math.round(((pa >> sh) & 255) * (1 - f) + ((pb >> sh) & 255) * f));
		return '#' + c.map(v => v.toString(16).padStart(2, '0')).join('');
	}

	function skyAt(tod) {
		for (let i = 1; i < SKY.length; i++) {
			if (tod <= SKY[i][0]) {
				const [t0, a0, b0] = SKY[i - 1], [t1, a1, b1] = SKY[i];
				const f = (tod - t0) / (t1 - t0);
				return [mix(a0, a1, f), mix(b0, b1, f)];
			}
		}
		return [SKY[0][1], SKY[0][2]];
	}

	function isDark(tod) { return tod >= 12800 && tod < 23200; }

	/** The masthead's sky follows the game clock: sun by day, moon and stars by night. */
	function paintSky(tod) {
		const sky = document.getElementById('sky');
		if (!sky) return;
		const [top, low] = skyAt(tod);
		const dark = isDark(tod);
		document.querySelector('.mast').classList.toggle('dark', dark);
		// Sun up at 0 (6am) and down at 12000 (6pm); the moon the other half of the day.
		const f = ((dark ? tod - 12000 : tod) % 12000 + 12000) % 12000 / 12000;
		// The sun or moon crosses the right-hand part of the sky, clear of the title.
		const orbX = 50 + f * 46, orbY = 78 - Math.sin(f * Math.PI) * 58;
		const stars = [];
		if (dark) {
			for (let i = 0; i < 28; i++) {
				stars.push(h('i', { class: 'star' + (i % 3 === 0 ? ' twinkle' : ''),
					style: `left:${(i * 37.3) % 100}%;top:${(i * 23.7) % 72}%;opacity:${0.3 + (i % 4) * 0.17}` }));
			}
		}
		const id = 'g' + Math.round(tod / 100);
		sky.replaceChildren(svg('svg', { class: 'bg', viewBox: '0 0 100 100', preserveAspectRatio: 'none' },
			svg('defs', {}, svg('linearGradient', { id, x1: 0, y1: 0, x2: 0, y2: 1 },
				svg('stop', { offset: '0', 'stop-color': top }), svg('stop', { offset: '1', 'stop-color': low }))),
			svg('rect', { x: 0, y: 0, width: 100, height: 100, fill: 'url(#' + id + ')' }),
			// A ridge of hills along the bottom, darker at night.
			svg('path', { d: 'M0 100 L0 84 Q8 74 16 82 T34 78 T52 85 T70 75 T88 83 T100 78 L100 100 Z',
				fill: dark ? '#0a0f1c' : '#4f7a3a', opacity: dark ? 0.9 : 0.5 })),
			stars, h('i', { class: 'orb ' + (dark ? 'moon' : 'sun'), style: `left:${orbX}%;top:${orbY}%` }));
	}

	// ---------------------------------------------------------------- hearts

	function heart(kind) {
		// A 7x6 pixel heart: full, half (left side filled) or empty.
		const rows = ['.XX.XX.', 'XXXXXXX', 'XXXXXXX', '.XXXXX.', '..XXX..', '...X...'];
		const px = [];
		rows.forEach((row, y) => [...row].forEach((c, x) => {
			if (c !== 'X') return;
			const filled = kind === 'full' || (kind === 'half' && x <= 3);
			px.push(svg('rect', { x, y, width: 1, height: 1, fill: filled ? 'var(--heart)' : 'var(--heart-empty)' }));
		}));
		return svg('svg', { class: 'heart', viewBox: '0 0 7 6', width: 11, height: 10, 'shape-rendering': 'crispEdges' }, px);
	}

	/** Ten hearts, as in the game: two health points to a heart. */
	function hearts(a) {
		if (!a.alive || !a.maxHealth) return null;
		const hp = Math.max(0, Math.min(a.maxHealth, a.health || 0));
		const n = Math.round(a.maxHealth / 2);
		const out = [];
		for (let i = 0; i < n; i++) {
			const left = hp - i * 2;
			out.push(heart(left >= 2 ? 'full' : left >= 1 ? 'half' : 'empty'));
		}
		return h('span', { class: 'hearts' + (hp / a.maxHealth < 0.35 ? ' low' : ''), title: Math.round(hp) + ' of ' + a.maxHealth + ' health',
			'aria-label': Math.round(hp) + ' of ' + a.maxHealth + ' health' }, out);
	}

	// ------------------------------------------------------------------- map

	const MONSTER_COLOR = { zombie: '#5f9e4a', husk: '#b59a5a', drowned: '#3f8f8a', 'zombie villager': '#6f9e4a',
		skeleton: '#d8d8d0', stray: '#a9c2c9', bogged: '#7f9a62', spider: '#7a2a2a', 'cave spider': '#2f5a6a', creeper: '#3ec43e' };

	/**
	 * A top-down map of everyone whose body is loaded: people as their faces,
	 * homes as little houses, and monsters near them (a dashed line to whoever
	 * a monster is after). It fits whatever is on it, north up.
	 */
	function settlementMap(agents, monsters, tod) {
		const people = agents.filter(a => a.alive && a.position);
		if (!people.length) return null;
		const pts = [];
		people.forEach(a => { pts.push([a.position.x, a.position.z]); if (a.homeAt) pts.push([a.homeAt.x, a.homeAt.z]); });
		(monsters || []).forEach(m => pts.push([m.x, m.z]));
		let minX = Math.min(...pts.map(p => p[0])), maxX = Math.max(...pts.map(p => p[0]));
		let minZ = Math.min(...pts.map(p => p[1])), maxZ = Math.max(...pts.map(p => p[1]));
		const W = 640, H = 300, pad = 30;
		const cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
		const scale = Math.min((W - pad * 2) / Math.max(48, maxX - minX), (H - pad * 2) / Math.max(24, maxZ - minZ));
		const X = x => W / 2 + (x - cx) * scale, Y = z => H / 2 + (z - cz) * scale;
		const dark = isDark(tod);
		const byId = Object.fromEntries(people.map(a => [a.id, a]));

		const grid = [];
		const step = [8, 16, 32, 64, 128, 256].find(s => s * scale >= 40) || 512;
		for (let gx = Math.ceil((cx - W / 2 / scale) / step) * step; X(gx) < W; gx += step) {
			grid.push(svg('line', { x1: X(gx), y1: 0, x2: X(gx), y2: H, class: 'grid' }));
		}
		for (let gz = Math.ceil((cz - H / 2 / scale) / step) * step; Y(gz) < H; gz += step) {
			grid.push(svg('line', { x1: 0, y1: Y(gz), x2: W, y2: Y(gz), class: 'grid' }));
		}

		const homes = people.filter(a => a.homeAt).map(a => {
			const x = X(a.homeAt.x), y = Y(a.homeAt.z);
			const t = svg('title', {}); t.textContent = a.name + '\u2019s home';
			return svg('g', { class: 'home' }, t,
				svg('path', { d: `M${x - 7} ${y} L${x} ${y - 7} L${x + 7} ${y} Z` }),
				svg('rect', { x: x - 5, y, width: 10, height: 7 }));
		});

		const chases = [], mobs = [];
		for (const m of monsters || []) {
			const x = X(m.x), y = Y(m.z);
			const prey = m.after && byId[m.after];
			if (prey) chases.push(svg('line', { x1: x, y1: y, x2: X(prey.position.x), y2: Y(prey.position.z), class: 'chase' }));
			const t = svg('title', {}); t.textContent = 'A ' + m.kind + (prey ? ', after ' + prey.name : '');
			mobs.push(svg('g', { class: 'mob' + (prey ? ' hunting' : '') }, t,
				svg('rect', { x: x - 4, y: y - 4, width: 8, height: 8, rx: 1, fill: MONSTER_COLOR[m.kind] || '#8a3a8a',
					transform: `rotate(45 ${x} ${y})` })));
		}

		// Names that would land on top of each other are nudged down a line.
		const placed = [];
		const labelY = (x, y) => {
			let ly = y + 21;
			while (placed.some(([px, py]) => Math.abs(px - x) < 44 && Math.abs(py - ly) < 11)) ly += 11;
			placed.push([x, ly]);
			return ly;
		};
		const labels = [];
		const faces = people.map(a => {
			const x = X(a.position.x), y = Y(a.position.z);
			const chased = (monsters || []).some(m => m.after === a.id);
			const f = face(a.name, 20, false);
			f.setAttribute('x', x - 10); f.setAttribute('y', y - 10);
			const label = svg('text', { x, y: labelY(x, y), class: 'label' }); label.textContent = a.name;
			const link = svg('a', { href: '#/people/' + a.id, class: 'who' + (chased ? ' chased' : '') + (a.currentIntent === 'FIGHT' ? ' fighting' : '') });
			const t = svg('title', {}); t.textContent = a.name + ' is ' + doing(a);
			link.append(t, svg('circle', { cx: x, cy: y, r: 14, class: 'ring' }), f);
			labels.push(label);
			return link;
		});

		const scaleBar = svg('g', { class: 'scale' },
			svg('line', { x1: 12, y1: H - 12, x2: 12 + step * scale, y2: H - 12 }),
			(() => { const t = svg('text', { x: 12, y: H - 17 }); t.textContent = step + ' blocks'; return t; })());

		return h('figure', { class: 'map' + (dark ? ' night' : '') },
			svg('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': 'Map of the settlement' },
				svg('rect', { x: 0, y: 0, width: W, height: H, class: 'ground' }), grid, homes, chases, mobs, faces, labels, scaleBar),
			h('figcaption', { class: 'small' }, people.length + ' about'
				+ (people.filter(a => a.homeAt).length ? ' · ' + people.filter(a => a.homeAt).length + ' homes' : ' · no homes yet')
				+ ((monsters || []).length ? ' · ' + monsters.length + ' monster' + (monsters.length === 1 ? '' : 's') + ' near' : '')
				+ ' · north is up'));
	}

	// ---------------------------------------------------------------- events

	const ROUTINE = /\b(harvested|ate some|chopped|planted|tended|mined some|dug farmland|cut grass|made bread|cooked|hunted|fed two)\b/;

	function isRoutine(e) { return e.type === 'ACTION' && ROUTINE.test(e.summary); }

	function isNews(e) {
		return e.type === 'MILESTONE' || e.type === 'DEATH' || (e.transcript && e.transcript.length > 1)
			|| / gave | agreed | turned away|set out to make|made the|gave up|dug down .* found the|smelted|traded /.test(' ' + e.summary + ' ');
	}

	function dialogue(lines) {
		if (!lines || !lines.length) return null;
		return h('div', { class: 'dialogue' }, lines.map(line => {
			const i = line.indexOf(': ');
			return i > 0 ? h('p', {}, h('span', { class: 'who' }, line.slice(0, i)), line.slice(i + 2)) : h('p', {}, line);
		}));
	}

	function collapse(items, keyOf) {
		const groups = [];
		for (const item of items) {
			const last = groups[groups.length - 1];
			if (last && keyOf(last.item) === keyOf(item)) last.count++;
			else groups.push({ item, count: 1 });
		}
		return groups;
	}

	/** Events newer than this arrived since the last refresh, and are shown arriving. */
	let freshAfter = Infinity;
	let seenEventId = 0;

	function noteSeen(events) {
		const newest = Math.max(0, ...events.map(e => e.id));
		freshAfter = seenEventId || Infinity;
		seenEventId = Math.max(seenEventId, newest);
	}

	function entry(e, count) {
		const cls = 'entry' + (isRoutine(e) ? ' routine' : '') + (e.type === 'MILESTONE' || e.type === 'DEATH' || e.type === 'ATTACKED' ? ' big' : '')
			+ (e.id > freshAfter ? ' fresh' : '');
		const showLines = e.transcript && e.transcript.length > 1;
		return h('div', { class: cls },
			h('div', { class: 't' }, clockTime(e.tick)),
			h('div', { class: 'x' },
				h('a', { href: '#/events/' + e.id }, e.summary),
				count > 1 ? h('span', { class: 'times' }, '×' + count) : null,
				showLines ? dialogue(e.transcript) : null));
	}

	/** Events newest first, under a heading per day. */
	function byDay(events) {
		const out = [];
		let day = null;
		for (const g of collapse(events, e => e.type + '|' + e.summary)) {
			const d = dayOf(g.item.tick);
			if (d !== day) { day = d; out.push(h('div', { class: 'day' }, 'Day ' + d)); }
			out.push(entry(g.item, g.count));
		}
		return out;
	}

	function causeItem(c, agentId) {
		const text = (c.detail || c.type.toLowerCase()).replace(/-?\d+\.\d{3,}/g, x => Number(x).toFixed(2))
			.replace(/^(\w+)=/, '$1 at ');
		if (c.type === 'EVENT') return h('li', {}, h('a', { href: '#/events/' + c.sourceId }, text));
		if (c.type === 'MEMORY' && agentId) return h('li', {}, h('a', { href: '#/people/' + agentId + '/m/' + c.sourceId }, text));
		return h('li', {}, text);
	}

	// ----------------------------------------------------------------- clock

	function clockLine(o) {
		const note = o.simulationEnabled === false ? ' · paused' : o.withinActiveHours === false ? ' · resting' : '';
		return 'Day ' + o.day + ' · ' + partOfDay(o.timeOfDay) + ', ' + clockTime(o.timeOfDay) + note;
	}

	async function updateClock() {
		try {
			const o = await api('overview');
			if (o.ready) { clock.textContent = clockLine(o); paintSky(o.timeOfDay); }
			return o;
		} catch (e) {
			return null;
		}
	}

	function notices(o, alive) {
		const off = o.simulationEnabled === false;
		const resting = !off && o.withinActiveHours === false;
		return [
			off ? h('div', { class: 'notice' }, 'The settlement is paused: nobody moves and no AI calls are made. An operator can resume it with /civ on.') : null,
			resting ? h('div', { class: 'notice' }, 'Everyone is resting outside the active hours (' + o.activeHours + ').') : null,
			!off && !resting && o.loadedBodies < alive ? h('div', { class: 'notice' }, 'Only ' + o.loadedBodies + ' of ' + alive
				+ ' people are loaded in the world right now.') : null,
		];
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
		input.addEventListener('keydown', e => { if (e.key === 'Enter') go(); });
		show(h('h2', {}, 'A token is needed'),
			message ? h('div', { class: 'notice' }, message) : null,
			h('p', {}, 'Open the link with ?t=… from the server log, or paste the observerToken from config/aicivilization.json.'),
			h('div', { class: 'token' }, input, h('button', { onclick: go }, 'Open')));
	}

	async function viewToday(myRoute) {
		setTab('today');
		const [o, agents, page] = await Promise.all([api('overview'), api('agents'), api('events', { limit: 120, exclude: 'DECISION' })]);
		if (myRoute !== routeId) return;
		if (!o.ready) { show(h('p', { class: 'empty' }, 'The server is still starting up.')); return; }
		clock.textContent = clockLine(o);
		paintSky(o.timeOfDay);
		const alive = agents.filter(a => a.alive).sort((a, b) => a.name.localeCompare(b.name));
		const events = page.events || [];
		noteSeen(events);
		const lead = events.find(e => e.transcript && e.transcript.length > 1 && /talked about/.test(e.summary))
			|| events.find(e => e.type === 'MILESTONE') || events[0];
		const news = collapse(events.filter(e => e !== lead && isNews(e)), e => e.summary).slice(0, 8);

		show(
			notices(o, alive.length),
			h('p', { class: 'small' }, alive.length + ' living · ' + o.dead + ' buried · ' + o.total + ' have lived here'),
			settlementMap(agents, o.monsters, o.timeOfDay),
			lead ? h('article', {},
				h('div', { class: 'kicker' }, when(lead.tick) + (lead.transcript && lead.transcript.length > 1 ? ' · overheard' : '')),
				h('div', { class: 'headline' }, h('a', { href: '#/events/' + lead.id, style: 'text-decoration:none' }, lead.summary)),
				dialogue(lead.transcript),
				lead.subjects && lead.subjects.length ? h('p', { class: 'small' }, list(lead.subjects.map(personLink))) : null)
				: h('p', { class: 'empty' }, 'Nothing has happened yet.'),
			news.length ? [h('h2', {}, 'The latest'), news.map(g => entry(g.item, g.count)),
				h('a', { class: 'more', href: '#/chronicle' }, 'The whole chronicle →')] : null,
			h('h2', {}, 'Around the settlement'),
			alive.length ? h('ul', { class: 'people' }, alive.map(a => {
				const low = trouble(a.needs);
				return h('li', {}, h('a', { class: 'row', href: '#/people/' + a.id }, face(a.name, 32, false),
					h('div', {}, h('span', { class: 'name' }, a.name), ' ', hearts(a), ' ',
						h('span', { class: 'doing' + (a.currentIntent === 'FIGHT' ? ' fighting' : '') }, 'is ' + doing(a)),
						low ? h('span', { class: 'small bad' }, ' \u2014 ' + low) : null)));
			})) : h('p', { class: 'empty' }, 'Nobody lives here yet. An operator can start with /civ spawn 3.'));
	}

	async function viewPeople(myRoute) {
		setTab('people');
		const agents = await api('agents');
		if (myRoute !== routeId) return;
		updateClock();
		const alive = agents.filter(a => a.alive).sort((a, b) => a.name.localeCompare(b.name));
		const dead = agents.filter(a => !a.alive);
		show(
			h('ul', { class: 'people' }, alive.map(a => {
				const low = trouble(a.needs);
				return h('li', {}, h('a', { class: 'row', href: '#/people/' + a.id }, face(a.name, 44, false),
					h('div', { style: 'min-width:0' },
						h('div', {}, h('span', { class: 'name' }, a.name), ' ', hearts(a), h('span', { class: 'small' }, '  ' + age(a.ageTicks))),
						h('div', { class: 'doing' }, doing(a).charAt(0).toUpperCase() + doing(a).slice(1)),
						h('div', { class: 'small' }, (a.home ? 'Lives in ' + a.home : 'No home yet'),
							low ? [' · ', h('span', { class: 'bad' }, low)] : null))));
			})),
			dead.length ? h('details', {}, h('summary', {}, 'Those who have died (' + dead.length + ')'),
				h('ul', { class: 'people' }, dead.map(a => h('li', {}, h('a', { class: 'row', href: '#/people/' + a.id },
					face(a.name, 28, true), h('span', { class: 'quiet' }, a.name + ', ' + age(a.ageTicks).replace(' old', '')))))))
				: null);
	}

	const decisionChoice = {};
	const showAllMemories = {};

	async function viewPerson(myRoute, id, memoryId) {
		setTab('people');
		const a = await api('agents/' + id);
		if (myRoute !== routeId) return;
		updateClock();
		const latest = a.decisions && a.decisions.length ? a.decisions[0] : null;
		const reasons = latest ? (latest.candidates.find(c => c.intent === latest.chosen) || { factors: {} }) : null;
		const topReasons = reasons ? Object.entries(reasons.factors).filter(([k, v]) => v > 0.04 && k !== 'jitter')
			.sort((x, y) => y[1] - x[1]).slice(0, 2) : [];
		const home = a.homeDetail;
		const homeDesign = home && (a.designs || []).find(d => d.name === home.design);

		show(
			h('div', { class: 'who-head' }, face(a.name, 64, !a.alive),
				h('div', {}, h('h1', {}, a.name), hearts(a),
					h('div', { class: 'small' }, (a.alive ? age(a.ageTicks) : 'Died') + ' · ' + (a.alive ? 'at ' + place(a.position) : '')))),
			a.alive ? h('p', { class: 'now' }, a.name + ' is ' + doing(a) + '.',
				topReasons.length ? h('span', { class: 'quiet' }, ' Mostly: ' + topReasons.map(([k, v]) => factorText(k, v)).join(', ') + '.') : null)
				: null,
			a.plan ? planBox(a) : null,

			h('h2', {}, 'Condition'),
			h('div', { class: 'condition' }, Object.keys(NEED_WORDS).map(n => {
				const v = Math.max(0, Math.min(1, a.needs[n] || 0));
				return h('div', {}, n, h('b', { class: v < 0.35 ? 'bad' : null }, needWord(n, v)),
					h('div', { class: 'meter' + (v < 0.35 ? ' low' : '') }, h('i', { style: 'width:' + Math.round(v * 100) + '%' })));
			})),
			h('p', { style: 'margin-top:12px' }, character(a.personality)),

			h('h2', {}, 'Carrying'),
			a.inventory && a.inventory.length
				? h('p', { class: 'goods' }, list(a.inventory.slice().sort((x, y) => y.quantity - x.quantity).map(i =>
					h('span', { class: isTool(i.itemId) ? 'tool' : null }, (i.quantity > 1 ? i.quantity + ' ' : '') + itemName(i.itemId))), ', '))
				: h('p', { class: 'empty' }, 'Nothing.'),

			h('h2', {}, 'Home'),
			home ? h('p', {}, 'Lives in ', h('b', {}, home.design), ' at ' + home.x + ', ' + home.z + ', built ' + when(home.builtTick) + '.')
				: h('p', { class: 'empty' }, 'No home yet.'),
			homeDesign ? floorPlan(homeDesign) : null,
			a.project ? h('p', {}, 'Building ', h('b', {}, a.project.design), ' with ',
				personLink({ id: a.project.partnerId, name: a.project.partner }),
				a.project.siteKnown ? ' at ' + a.project.x + ', ' + a.project.z + '.' : ', still choosing where.') : null,
			(a.designs || []).filter(d => d.how !== 'innate').length ? h('p', { class: 'small' }, 'Knows how to build: ',
				list((a.designs || []).filter(d => d.how !== 'innate').map(d => d.name + ' (' + designOrigin(d) + ')'))) : null,

			h('h2', {}, 'People they know'),
			a.relationships.length ? relationships(a) : h('p', { class: 'empty' }, 'Hasn\'t met anyone yet.'),

			h('h2', {}, 'On their mind'),
			mindList(a),

			h('h2', {}, 'Memories'),
			memories(a, memoryId),

			latest ? h('h2', {}, 'How they decided') : null,
			latest ? decisionDetails(a) : null,
			h('p', { style: 'margin-top:24px' }, h('a', { class: 'more', href: '#/chronicle?view=record&agent=' + a.id },
				'Everything about ' + a.name + ' in the chronicle →')));

		if (memoryId && !viewPerson.scrolled) {
			viewPerson.scrolled = true;
			const el = document.getElementById('mem-' + memoryId);
			if (el) el.scrollIntoView({ block: 'center' });
		}
	}

	function designOrigin(d) {
		switch (d.how) {
			case 'designed': return 'their own design';
			case 'saw': return 'copied from ' + (d.source || 'someone') + '\'s';
			case 'told': return 'heard about from ' + (d.source || 'someone');
			default: return 'known from the start';
		}
	}

	function planBox(a) {
		const p = a.plan;
		return h('div', { class: 'plan-box' },
			h('div', { class: 'kicker' }, 'Plan'),
			h('div', { style: 'font-weight:700;margin:2px 0 4px' }, p.goal.charAt(0).toUpperCase() + p.goal.slice(1)),
			h('div', { class: 'small' }, 'Has ' + p.have + ' of ' + p.count + ' ' + itemName(p.target)),
			p.gap ? h('p', { class: 'bad', style: 'margin:8px 0 0' }, 'Doesn\'t know how to get ' + itemName(p.gap) + ' yet.')
				: p.steps.length ? h('ol', { class: 'steps' }, p.steps.map((s, i) => h('li', { class: i === 0 ? 'next' : null },
					s, i === 0 ? h('span', { class: 'small' }, '  next') : null)))
				: h('p', { class: 'good', style: 'margin:8px 0 0' }, 'Nothing left to do.'));
	}

	/** The first layer of a design, drawn: walls dark, the door in the accent colour. */
	function floorPlan(d) {
		const rows = d.layers && d.layers.length ? d.layers[0] : [];
		if (!rows.length) return null;
		const width = Math.max(...rows.map(r => r.length));
		return h('div', { class: 'plan-floor' },
			h('div', { class: 'grid', style: 'grid-template-columns:repeat(' + width + ',17px)' },
				rows.map(r => Array.from({ length: width }, (_, i) => {
					const c = r[i] || ' ';
					return h('span', { class: c === '#' ? 'w' : c === 'D' ? 'd' : c === '.' ? null : 'o' });
				}))),
			h('span', { class: 'small cap' }, d.name + ', ' + d.size));
	}

	const RELATIONSHIPS_SHOWN = 6;
	const showAllRelationships = {};

	function relationships(a) {
		const sorted = a.relationships.slice().sort((x, y) => Math.abs(y.affinity) - Math.abs(x.affinity));
		const all = showAllRelationships[a.id] || sorted.length <= RELATIONSHIPS_SHOWN;
		const shown = all ? sorted : sorted.slice(0, RELATIONSHIPS_SHOWN);
		return [
			h('ul', { class: 'people' }, shown.map(r => h('li', {}, h('a', { class: 'row', href: '#/people/' + r.id }, face(r.name, 24, false),
				h('div', {}, h('span', { class: 'name' }, r.name), ' ', h('span', { class: 'quiet' }, bond(r)),
					h('div', { class: 'small' }, 'last together ' + when(r.lastInteractionTick)
						+ (r.thingsLearned ? ' · learned ' + r.thingsLearned + ' things from them' : ''))))))),
			!all ? h('button', { class: 'more', onclick: () => { showAllRelationships[a.id] = true; route(true); } },
				'All ' + sorted.length + ' people ' + a.name + ' knows') : null,
		];
	}

	const BELIEFS_SHOWN = 6;
	const showAllBeliefs = {};

	function mindList(a) {
		const goals = (a.goals || []).filter(g => g.active);
		const beliefs = a.beliefs || [];
		if (!goals.length && !beliefs.length) return h('p', { class: 'empty' }, 'Nothing in particular.');
		const all = showAllBeliefs[a.id] || beliefs.length <= BELIEFS_SHOWN;
		const shown = all ? beliefs : beliefs.slice(0, BELIEFS_SHOWN);
		return [
			h('ul', { class: 'ledger' },
				goals.map(g => h('li', {}, 'Wants to ' + g.description.replace(/^to /, '') + '.', h('div', { class: 'small' }, 'since ' + when(g.createdTick)))),
				shown.map(b => h('li', {}, h('i', {}, '“' + b.statement + '”'), h('div', { class: 'small' }, 'believes it '
					+ (b.confidence > 0.75 ? 'firmly' : b.confidence > 0.45 ? 'fairly' : 'a little') + ' · ' + when(b.formedTick))))),
			!all ? h('button', { class: 'more', onclick: () => { showAllBeliefs[a.id] = true; route(true); } },
				'All ' + beliefs.length + ' thoughts') : null,
		];
	}

	function provenance(p) {
		if (!p) return null;
		if (p.type === 'PERCEIVED') return 'saw it';
		if (p.type === 'INFERRED') return 'worked it out';
		if (p.type === 'TOLD') return ['told by ', h('a', { href: '#/people/' + p.tellerId + '/m/' + p.tellerMemoryId }, p.tellerName || 'someone')];
		return null;
	}

	function memories(a, memoryId) {
		if (!a.memories.length) return h('p', { class: 'empty' }, 'No memories yet.');
		const groups = collapse(a.memories, m => String(m.id) === String(memoryId) ? 'hl' + m.id : m.description);
		const shown = showAllMemories[a.id] ? groups : groups.slice(0, 10);
		return [
			h('ul', { class: 'ledger' }, shown.map(g => {
				const m = g.item;
				return h('li', { id: 'mem-' + m.id, class: String(m.id) === String(memoryId) ? 'hl' : null },
					m.description, g.count > 1 ? h('span', { class: 'times' }, '×' + g.count) : null,
					h('div', { class: 'small' }, when(m.tick), ' · ', provenance(m.provenance),
						m.participants.length ? [' · with ', list(m.participants.map(personLink))] : null));
			})),
			groups.length > shown.length ? h('button', { class: 'more', onclick: () => { showAllMemories[a.id] = true; route(true); } },
				'All ' + groups.length + ' memories') : null,
		];
	}

	function decisionDetails(a) {
		const picked = Math.min(decisionChoice[a.id] || 0, a.decisions.length - 1);
		const d = a.decisions[picked];
		return h('details', { open: decisionChoice[a.id] !== undefined ? 'open' : null },
			h('summary', {}, 'Chose to ' + (d.chosen === 'PURSUE_PLAN' ? 'work on a plan' : DOING[d.chosen] || d.chosen.toLowerCase())
				+ ', ' + when(d.tick)),
			h('table', { class: 'scores' }, d.candidates.map(c => h('tr', { class: c.intent === d.chosen ? 'chosen' : null },
				h('td', {}, c.intent === 'PURSUE_PLAN' ? 'work on a plan' : DOING[c.intent] || c.intent.toLowerCase()),
				h('td', { class: 'quiet' }, Object.entries(c.factors).filter(([k]) => k !== 'jitter').map(([k, v]) => factorText(k, v)).join(', ')),
				h('td', { class: 'v' }, c.score.toFixed(2))))),
			d.causes && d.causes.length ? h('ul', { class: 'because small' }, d.causes.map(c => causeItem(c, a.id))) : null,
			a.decisions.length > 1 ? h('p', { class: 'small', style: 'margin-top:8px' }, 'Earlier: ',
				a.decisions.slice(0, 8).map((x, i) => [i ? ' · ' : '', i === picked ? h('b', {}, clockTime(x.tick))
					: h('a', { href: 'javascript:void 0', onclick: () => { decisionChoice[a.id] = i; route(true); } }, clockTime(x.tick))]))
				: null);
	}

	async function viewEvent(myRoute, id) {
		setTab('chronicle');
		let e;
		try {
			e = await api('events/' + id);
		} catch (err) {
			if (err && err.status === 404) {
				if (myRoute !== routeId) return;
				show(h('p', { class: 'empty' }, 'That page of the chronicle is older than the server keeps.'),
					h('a', { class: 'more', href: '#/chronicle' }, '← The chronicle'));
				return;
			}
			throw err;
		}
		if (myRoute !== routeId) return;
		updateClock();
		const first = e.subjects[0] && e.subjects[0].id;
		const kind = { CONVERSATION: 'Conversation', TOLD: 'News passed on', MILESTONE: 'Milestone', DEATH: 'Death', ATTACKED: 'Attacked',
			ACTION: 'Work', DECISION: 'Decision', REASONING_RESULT: 'A thought', REASONING_INVOKED: 'Reflection', REASONING_FAILED: 'Thinking failed',
			NEED_CRISIS: 'Crisis', SPAWN: 'Arrival', PERCEIVED: 'Seen' }[e.type] || e.type;
		show(
			h('a', { class: 'more', href: '#/chronicle' }, '← The chronicle'),
			h('div', { class: 'kicker', style: 'margin-top:14px' }, kind + ' · ' + when(e.tick)),
			h('div', { class: 'headline' }, e.summary),
			e.subjects.length ? h('p', {}, e.subjects.map(s => h('a', { href: '#/people/' + s.id,
				style: 'display:inline-flex;gap:6px;align-items:center;margin-right:14px;text-decoration:none' }, face(s.name, 22, false), s.name)))
				: null,
			dialogue(e.transcript),
			e.causes && e.causes.length ? [h('h2', {}, 'Why it happened'), h('ul', { class: 'because' }, e.causes.map(c => causeItem(c, first)))] : null);
	}

	const FILTERS = [
		['Everything', {}], ['Conversations', { type: 'CONVERSATION' }], ['Milestones', { type: 'MILESTONE' }],
		['Work', { type: 'ACTION' }], ['Thoughts', { type: 'REASONING_RESULT' }],
	];
	const chronicle = { key: null, events: [], more: false };

	// --------------------------------------------------------------- stories

	function views(which) {
		return h('div', { class: 'views' },
			h('a', { href: '#/chronicle', class: which === 'stories' ? 'on' : null }, 'Stories'),
			h('a', { href: '#/chronicle?view=record', class: which === 'record' ? 'on' : null }, 'Every event'));
	}

	function span(s) {
		const a = s.firstTick, b = s.lastTick;
		if (dayOf(a) !== dayOf(b)) return when(a) + ' to ' + when(b);
		return clockTime(a) === clockTime(b) ? clockTime(a) : clockTime(a) + '\u2013' + clockTime(b);
	}

	function story(s) {
		const record = s.record || [];
		const lead = record.find(e => e.type === 'CONVERSATION' || e.type === 'MILESTONE') || record[record.length - 1];
		const people = s.people && s.people.length ? list(s.people.map(personLink)) : null;
		return h('article', { class: 'story' },
			h('div', { class: 'who-when' }, span(s), people ? [' \u00b7 ', people] : null),
			s.written ? h('h3', {}, s.headline) : h('h3', { class: 'plain' }, lead ? lead.summary : 'Something happened'),
			s.written ? h('p', { class: 'tale' }, s.text) : null,
			s.written && s.stands ? h('div', { class: 'stands' }, h('b', {}, 'Where it stands'), s.stands) : null,
			s.written && s.newSinceWritten > 0
				? h('div', { class: 'later' }, s.newSinceWritten + (s.newSinceWritten === 1 ? ' newer event isn\u2019t' : ' newer events aren\u2019t') + ' in this write-up yet.')
				: null,
			!s.written ? h('div', { class: 'later' }, 'Not written up yet: here is what happened.') : null,
			h('details', s.written ? {} : { open: '' },
				h('summary', {}, 'What happened'),
				record.map(e => entry(e, 1))));
	}

	function also(minor, routine) {
		const items = minor.map(s => (s.record && s.record[0]) ? h('li', {}, h('a', { href: '#/events/' + s.record[0].id }, s.record[0].summary)) : null);
		let work = null;
		if (routine && routine.total) {
			const top = routine.byAgent.slice(0, 4).map(a => a.name + ' ' + a.count);
			const rest = routine.byAgent.length - top.length;
			work = h('div', {}, h('b', {}, 'Everyday work: '), routine.total + (routine.total === 1 ? ' job' : ' jobs') + ' (' + top.join(', ')
				+ (rest > 0 ? ' and ' + rest + (rest === 1 ? ' other' : ' others') : '') + ')');
		}
		if (!items.length && !work) return null;
		return h('div', { class: 'also' }, work, items.length ? [h('b', {}, 'Also: '), h('ul', {}, items)] : null);
	}

	async function viewStories(myRoute) {
		setTab('chronicle');
		const data = await api('stories');
		if (myRoute !== routeId) return;
		updateClock();
		const stories = data.stories || [];
		const routineByDay = new Map((data.routine || []).map(r => [r.day, r]));
		const days = [];
		for (const s of stories) {
			const d = dayOf(s.lastTick);
			if (!days.length || days[days.length - 1].day !== d) days.push({ day: d, stories: [] });
			days[days.length - 1].stories.push(s);
		}
		show(
			views('stories'),
			stories.length ? days.map(d => [
				h('div', { class: 'day' }, 'Day ' + d.day),
				d.stories.filter(s => s.worthWriting).map(story),
				also(d.stories.filter(s => !s.worthWriting), routineByDay.get(d.day)),
			]) : h('p', { class: 'empty' }, 'No stories yet. They appear as the settlement\u2019s people think, talk and act.'),
			h('p', { class: 'small' }, 'Each story is written by the settlement\u2019s AI from the events listed under it, and only from them. Open \u201cWhat happened\u201d to check.'));
	}

	async function viewChronicle(myRoute) {
		setTab('chronicle');
		const params = new URLSearchParams(location.hash.split('?')[1] || '');
		const type = params.get('type') || '';
		const agent = params.get('agent') || '';
		const exclude = type ? '' : 'DECISION';
		const key = type + '|' + agent;
		const agents = await api('agents');
		if (key !== chronicle.key || !chronicle.events.length) {
			const page = await api('events', { limit: 80, type, exclude, agent });
			chronicle.key = key;
			chronicle.events = page.events;
			chronicle.more = page.more;
		} else {
			const page = await api('events', { since: chronicle.events[0].id, limit: 200, type, exclude, agent });
			chronicle.events = page.events.concat(chronicle.events);
		}
		if (myRoute !== routeId) return;
		updateClock();
		const href = (t, a) => {
			const q = new URLSearchParams();
			q.set('view', 'record');
			if (t) q.set('type', t);
			if (a) q.set('agent', a);
			return '#/chronicle' + (q.toString() ? '?' + q : '');
		};
		const who = h('select', { onchange: () => { location.hash = href(type, who.value); } },
			h('option', { value: '' }, 'Everyone'),
			agents.slice().sort((x, y) => x.name.localeCompare(y.name)).map(a =>
				h('option', { value: a.id, selected: a.id === agent ? 'selected' : null }, a.name + (a.alive ? '' : ' (died)'))));
		const older = async () => {
			const last = chronicle.events[chronicle.events.length - 1];
			const page = await api('events', { before: last.id, limit: 80, type, exclude, agent });
			chronicle.events = chronicle.events.concat(page.events);
			chronicle.more = page.more;
			route(true);
		};
		show(
			views('record'),
			h('div', { class: 'filters' }, FILTERS.map(([name, f]) =>
				h('a', { href: href(f.type || '', agent), class: (f.type || '') === type ? 'on' : null }, name)), who),
			chronicle.events.length ? byDay(chronicle.events) : h('p', { class: 'empty' }, 'Nothing here yet.'),
			chronicle.more ? h('button', { class: 'more', onclick: older }, 'Earlier entries') : null);
	}

	// ---------------------------------------------------------------- router

	async function route(keepState) {
		if (!keepState) { routeId++; viewPerson.scrolled = false; }
		const myRoute = routeId;
		clearTimeout(pollTimer);
		const path = location.hash.replace(/^#/, '').split('?')[0] || '/';
		const parts = path.split('/').filter(Boolean);
		const section = parts[0] === 'agents' ? 'people' : parts[0] === 'timeline' ? 'chronicle' : parts[0];
		try {
			if (!section) await viewToday(myRoute);
			else if (section === 'people' && parts.length === 1) await viewPeople(myRoute);
			else if (section === 'people') await viewPerson(myRoute, parts[1], parts[2] === 'm' ? parts[3] : null);
			else if (section === 'events' && parts[1]) await viewEvent(myRoute, parts[1]);
			else if (section === 'chronicle') {
				const view = new URLSearchParams(location.hash.split('?')[1] || '').get('view');
				if (view === 'record') await viewChronicle(myRoute);
				else await viewStories(myRoute);
			}
			else await viewToday(myRoute);
		} catch (err) {
			if (myRoute !== routeId) return;
			if (err && err.auth) { renderTokenForm(token ? 'That token was not accepted.' : null); return; }
			show(h('div', { class: 'notice' }, 'Could not reach the server' + (err && err.status ? ' (HTTP ' + err.status + ')' : '') + '. Trying again…'));
		}
		if (myRoute === routeId) {
			pollTimer = setTimeout(() => { if (!document.hidden) route(true); else route.pending = true; }, POLL_MS);
		}
	}

	document.addEventListener('visibilitychange', () => {
		if (!document.hidden && route.pending) { route.pending = false; route(true); }
	});
	window.addEventListener('hashchange', () => route());
	if (new URLSearchParams(location.search).get('t')) storeToken(token);
	route();
})();

---
name: steward
description: Repo conventions for driving this project's pull requests to mergeable — what every PR must contain and how to handle CI, conflicts and reviews here.
---

# PR conventions for this repo

- One focused change per PR. Commit messages: an imperative title saying
  what changes for the player/server ("Keep agent chunks loaded while agents
  run, ..."), then a body explaining the observed problem and each fix as
  `- ` bullets.
- Any change under `src/` that ships includes the rebuilt jar and new
  SHA-256 (`ship-jar` skill) in the same commit. A PR with a stale dist jar
  is not mergeable.
- `./gradlew build` (which runs the tests) must pass locally before pushing.
  If `AgentMindEpistemicBoundaryTest` fails, the change broke a design
  principle: redesign it, don't weaken the test.
- User-visible behavior or settings changes update `README.md`; scope
  changes update `DESIGN.md` §10.
- Maven Central `429 Too Many Requests` during a build is a rate limit, not
  a code failure: retry with backoff.
- Merge conflicts in `dist/*.jar` or its SHA line: never hand-merge. Merge
  the base, rebuild, and re-run `ship-jar`.

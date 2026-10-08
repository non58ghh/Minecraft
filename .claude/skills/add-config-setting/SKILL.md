---
name: add-config-setting
description: Add or change a setting in config/aicivilization.json (ModConfig) end to end — field, default, validation, wiring, and README docs. Use when asked to "make X configurable", add an option/toggle/limit, or change a default.
---

# Add a config setting

Settings live as public fields on `config/ModConfig.java`, serialized by Gson
to `config/aicivilization.json` in the server's run directory.

1. Add a `public` field with a safe default and a javadoc comment saying what
   it does and its units (ticks, seconds, 0 = no cap, etc.). Keep the default
   equal to today's behavior so existing servers don't change on upgrade —
   a key missing from an old JSON file gets the field's default.
2. If the value needs parsing/validation (times, zones, ranges), do it in a
   small plain-Java class with a unit test, like `config/ActiveHours` +
   `ActiveHoursTest`. Invalid values log a warning and fall back; they never
   crash the server.
3. Read it where needed (most wiring is in `AICivilizationMod`). Say whether
   it takes effect live or needs a restart.
4. Secrets: never store a secret value in the config. Follow
   `anthropicApiKeyEnv` — store the *name* of an environment variable.
5. Document it in `README.md` in the section for that feature, with a JSON
   snippet, the default, and what happens at the edges (blank, 0, negative).
6. If `/civ status` or the observer overview should show it, add it there.
7. `./gradlew test`, then the `ship-jar` skill.

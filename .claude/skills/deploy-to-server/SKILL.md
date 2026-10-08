---
name: deploy-to-server
description: Put the current dist/ jar on the live Minecraft server (a Google Compute Engine VM) and restart it. Use when asked to deploy, update the server, install the new build, or restart the server.
---

# Deploy to the live server

The live server is a small Compute Engine VM running Fabric with this mod,
Geyser, Floodgate and Fabric API in `mods/`. `gcloud compute *` commands are
pre-approved in `.claude/settings.json`.

Restarting kicks every connected player, so **confirm with the user before
the restart step** unless they already said to deploy.

## Steps

1. Make sure the jar being deployed is committed and pushed (see
   `ship-jar`), and note its SHA-256 from `dist/README.md`.
2. Find the VM — don't guess names or zones:
   ```
   gcloud compute instances list --format="table(name,zone,status,networkInterfaces[0].accessConfigs[0].natIP)"
   ```
   If several could be the server, ask which one.
3. Locate the server directory and how it runs:
   ```
   gcloud compute ssh <vm> --zone <zone> --command "ls ~; systemctl list-units --type=service | grep -i minecraft; ls -d ~/*/mods 2>/dev/null"
   ```
   Say what you found (service name, server dir) in your reply.
4. Copy the jar up and verify the checksum on the VM:
   ```
   gcloud compute scp dist/aicivilization-*.jar <vm>:/tmp/ --zone <zone>
   gcloud compute ssh <vm> --zone <zone> --command "sha256sum /tmp/aicivilization-*.jar"
   ```
   Stop if it doesn't match `dist/README.md`.
5. Back up the old jar (`mv mods/aicivilization-*.jar ~/aicivilization.prev.jar`),
   move the new one into `mods/`, then restart the service (or the
   screen/tmux session the server runs in).
6. Check it came up: tail the log for `AI Civilization observer listening`
   and any `ERROR` / `UnsupportedClassVersionError` (the VM needs Java 25).
7. Report: VM, old -> new checksum, restart time, anything odd in the log.
   To roll back, put `~/aicivilization.prev.jar` back and restart.

Never print or commit the observer token or API keys seen on the VM.

---
name: deploy-to-server
description: Put the current dist/ jar on the live Minecraft server (a Google Compute Engine VM) and restart it. Use when asked to deploy, update the server, install the new build, or restart the server.
---

# Deploy to the live server

The live server is a Compute Engine VM running Fabric with this mod,
Geyser, Floodgate and Fabric API in `mods/`. `gcloud compute *` commands are
pre-approved in `.claude/settings.json`.

- Project `trusty-magnet-500500-s5`, zone `us-central1-a`, VM
  `instance-template-20260920-20260920-202503` (confirm with
  `gcloud compute instances list --project trusty-magnet-500500-s5` if it
  looks wrong).
- Server dir `/home/wyattej79/mcserver`, run by `minecraft.service`, which
  starts on boot.

**Cloud sessions cannot SSH to the VM.** The egress proxy blocks port 22,
and the IAP tunnel needs WebSockets, which the proxy doesn't carry. So
deploys go through a boot-time script, `startup-deploy.sh` (next to this
file), set as the VM's `startup-script` metadata. It reads the jar URL and
checksum from metadata, swaps the jar in only if the checksum matches, and
does nothing on later boots once it's installed.

Deploying reboots the VM (about 1-2 minutes) and kicks every connected
player, so **confirm with the user first** unless they already said to deploy.

## Steps

1. Make sure the jar is committed and pushed to `main` (see `ship-jar`).
   Note the commit (`git rev-parse origin/main`) and the SHA-256 from
   `dist/README.md`. Check the two match with `sha256sum dist/*.jar`.
2. Set the script and the pinned jar on the VM (`V`/`Z` = VM and zone above,
   `C` = commit, `H` = SHA-256):
   ```
   gcloud config set project trusty-magnet-500500-s5
   gcloud compute instances add-metadata $V --zone $Z \
     --metadata-from-file startup-script=.claude/skills/deploy-to-server/startup-deploy.sh \
     --metadata aiciv-jar-url=https://raw.githubusercontent.com/non58ghh/Minecraft/$C/dist/aicivilization-0.1.0-milestone1.jar,aiciv-jar-sha256=$H
   ```
3. Reboot with a graceful stop and start (not `reset`, which skips the
   world save):
   ```
   gcloud compute instances stop $V --zone $Z
   gcloud compute instances start $V --zone $Z
   ```
   The stop can return `502 upstream request failed` even though it
   worked. Check `gcloud compute instances describe $V --zone $Z
   --format="value(status)"` before retrying, and run `start` only once
   the status is `TERMINATED`.
4. Read the result from the serial console:
   ```
   gcloud compute instances get-serial-port-output $V --zone $Z | grep -a -E "AICIV-DEPLOY|minecraft.service"
   ```
   Expect `AICIV-DEPLOY: deployed <new sha> (was <old sha>); backup in ...`
   followed by `Started Minecraft Fabric Server`. A `refusing` /
   `checksum mismatch` / `download failed` line means the old jar was left
   in place. A later `minecraft.service: Main process exited` means the
   server crashed (e.g. `UnsupportedClassVersionError` — the VM needs Java
   25). Port 8080 can't be reached from a cloud session, so the serial log is
   the health check.
5. Report: old -> new checksum, restart time, anything odd. To roll back,
   point the metadata at an earlier commit and checksum, then reboot again.
   Each deploy also leaves the old jar in
   `/home/wyattej79/mcserver/backup-aiciv-<time>/`.

Fresh world (only when the user asks to restart or reset the world): add
`aiciv-reset-world=<a new value, e.g. the date and time>` to the same
`add-metadata` call, then reboot as above. On boot the script moves the
world folder aside to `backup-world-<time>/` (nothing is deleted) and the
server generates a new one; the mod founds a new settlement in it. Each
value acts once, so later deploys leave the world alone.

Settings (only when the user asks to change one): add
`aiciv-config=<key>=<value>` (numbers or true/false, a key already in
`config/aicivilization.json`) to the same call; for several, pass a
`--metadata-from-file aiciv-config=<file>` holding `k=v,k=v`. They're
written into the config on boot, before the server starts (log line
`settings changed: ...`). Expect
`AICIV-DEPLOY: reset world <value>: moved world to ...` in the serial log.

Never print or commit the observer token or API keys seen on the VM or in
the serial log.

# ZEUS Anti-Advert

Automatic advertisement detection for Paper 1.21.11 / Java 21.

## Behavior
- Cancels chat messages containing URLs, IP addresses, Discord invites, common domain suffixes, or configured blocked words.
- Adds one automatic warning per blocked message.
- At the configured warning threshold (default 3), applies a chat mute (default 30 minutes).
- Warning count resets when the mute expires.
- Warning and mute data persist in `plugins/ZEUSAntiAdvert/data.yml`.
- Players with `zeus.antiadvert.bypass` are exempt.

## Commands
- `/warnings <player>` — view warning count.
- `/clearwarns <player>` — clear warning count.
- `/zeusfilter reload` — reload config.

## Permissions
- `zeus.antiadvert.admin` — manage filter commands (default op).
- `zeus.antiadvert.bypass` — bypass automatic filter and mute (default op).

## Build
Requires JDK 21 and Maven:
`mvn clean package`
The JAR will be in `target/`.

## Install
1. Build the JAR.
2. Upload it to the server's `plugins` folder.
3. Restart Paper.
4. Edit `plugins/ZEUSAntiAdvert/config.yml` as needed.

This is a first source release. Test on a copy/test server before disabling or removing any existing moderation plugins.

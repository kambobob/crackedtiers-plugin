# TierDisplay

A Minecraft plugin that pulls a player's PvP tier from a website and displays it in the tab list and on their nametag.

Features
Fetches player tier data from an external website/API
Displays tier in the tab list next to the player's name
Displays tier above a player's nametag when looking at them
Cross-version support: Minecraft 1.12 through 26.2
Runs as a Velocity plugin, with support for Folia and Paper backend servers
Architecture

TierDisplay is split across the proxy and backend layers of a networked server:

Velocity (proxy layer) — install in the Velocity plugins/ folder. Handles cross-server tier lookups so tier data stays consistent as players move between backend servers.
Paper / Folia (backend layer) — handles in-game display: tab list entries and nametag overlays on each individual server.
Installation
Velocity (proxy)
Drop TierDisplay-Velocity.jar into your Velocity plugins/ folder.
Configure the tier data source (website/API URL) in the generated config file.
Restart the proxy.
Paper / Folia (backend)
Drop TierDisplay-Backend.jar into each backend server's plugins/ folder.
Ensure it can reach the proxy (or the tier API directly, depending on config).
Restart the server.
Configuration
yaml

# config.yml (example)
tier-api-url: "https://your-site.example.com/api/tiers"
refresh-interval-seconds: 60
display:
  tab-list: true
  nametag: true
Compatibility
Component	Supported Versions
Minecraft	1.12 – 26.2
Proxy	Velocity
Backend	Paper, Folia

Roadmap
 Finalize tier API integration/format
 Tab list rendering across supported versions
 Nametag overlay rendering
 Cross-server tier sync via Velocity
 Config validation & error handling for unreachable API

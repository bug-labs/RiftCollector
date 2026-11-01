# RiftCollector

Item collectors for Paper that keep their own counted storage, so a single slot can hold a million
items. Collectors are owned, upgradeable, and can filter, smelt and sell what they pick up.

> **2.0.0 is a full rewrite.** It shares no code with 1.x and is **not** a drop-in upgrade.
> Read [Upgrading from 1.x](#upgrading-from-1x) before touching a live server.

## Features

- **Counted storage** — a slot counts its items instead of stacking them. Take 1, take a stack, or
  fill your inventory in a click.
- **Upgrades** — pickup speed, pickup range, storage slots and items per slot, bought with money.
  They travel with the collector when it is picked up or broken.
- **Auto sell and auto smelt** — sell drops as they are picked up, or smelt them first.
- **Filters** — whitelist or blacklist exactly what a collector picks up.
- **Owners and trusted players** — only the owner, players they trust, and staff can open one.
- **Names** — players can name a collector, shown in its menus and on its hologram.
- **Holograms** — floating text above each collector, using vanilla text displays.
- **Everything is a config file** — menus are character grids in `gui/`, all text lives in `lang/`,
  in MiniMessage, `&` codes or hex.
- **SQLite or MySQL** — collectors, contents, upgrades and settings all persist properly.

## Requirements

| | Version |
|---|---|
| Paper (or a fork) | 1.21.11 |
| Java | 21+ |

Optional: PlaceholderAPI, Vault, ExcellentEconomy, ShopGUI+, EconomyShopGUI / +, zItemStacker. Each
is explained in the [integrations docs](https://iamsamarth.github.io/wiki/riftcollector/integrations/).

## Documentation

Full docs — installation, menus, upgrades, abilities, commands, configuration, integrations and an
FAQ — live at **[iamsamarth.github.io/wiki/riftcollector](https://iamsamarth.github.io/wiki/riftcollector/)**.

## Building

```bash
./gradlew build
```

The jar lands in `build/libs/`. Java 21 is required; the Gradle wrapper is included.

## Upgrading from 1.x

2.0 is a rewrite, so a server running 1.x should treat it as a new plugin:

- **The plugin is now named `RiftCollector`.** It reads `plugins/RiftCollector/`, not
  `plugins/Collector/`, so 1.x configs are not picked up.
- **Permissions moved to `riftcollector.*`.** Every 1.x `collector.*` node needs replacing in your
  permission plugin.
- **1.x collectors are not imported.** The old database is left untouched on disk, so you can roll
  back by reinstalling 1.x.
- **Features dropped in this beta:** XP collection and the XP bank, WorldGuard region checks,
  `tiers.yml` (upgrades are now in `config.yml`), the per-player collector limit, and the de/es/fr
  locales. Only `en_US` ships, and translations are a copy of one file.

Test it on a copy of your world first.

## Upgrading from 2.0.0-beta.1

Menus and messages were rewritten in beta.2, and the updater never overwrites a file you already
have. The old wording keeps working, but to take the new one, delete
`plugins/RiftCollector/gui/` and `plugins/RiftCollector/lang/en_US.yml` and restart; both are
written fresh from the jar. `config.yml` and `prices.yml` need nothing: new settings are added to
them and your values are kept.

## Support

Questions and bug reports: [Discord](https://discord.com/invite/7hbY4vA4vQ)

## License

No license has been chosen for this project yet.

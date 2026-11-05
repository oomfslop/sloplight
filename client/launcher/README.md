# Sloplight launcher

The macOS front end of the Sloplight client: a keyboard-first launcher written in Rust with [GPUI](https://www.gpui.rs), shipped as `Sloplight.app`.

It replaces Moonlight's Qt interface, handling hosts, apps, pairing and settings. Streaming is still done by the client's C++ core, which the launcher runs as a helper process (`moonlight stream-helper`).

## Use

- Hosts are discovered on the local network or added manually with `⌘N`. Tailscale addresses are used when the host is not on the LAN.
- Pair with a PIN, or with a one-time password on Apollo-style hosts.
- Pick an app and press Enter to stream it. `⌘K` finds an app on any host.
- With a Sloplight server, the **Windows** button (`⇧⌘O`) lists the host's windows and streams any of them as a Mac window, and `⌘O` launches a host app straight into one.
- The **Keep windows ready** card in a host's app grid keeps an idle connection open until you quit, so windows and launched apps show up almost at once. It sends next to no data while nothing is streamed.
- The launcher and the window picker can also get system-wide shortcuts in Settings → App windows. They then open as a panel over any app.

### Keyboard

| Key | Action |
| --- | --- |
| `⌘K` | Quick launch |
| `⌘O` | Launch an app on the host |
| `⇧⌘O` | Stream a host window |
| `⌘,` | Settings |
| `⌘N` | Add host |
| `⌘R` | Refresh |
| `⌘F` | Filter apps |
| `⌘I` | Host info |
| `⇧⌘C` | Copy host address |
| `⇧⌘H` | Show hidden apps |
| `⌘1`…`⌘9`, `⌘[`, `⌘]` | Switch hosts |
| `F2` | Rename |
| `⌘⌫` | Remove host or quit app |

Arrows and Enter move around the app grid, and typing filters it.

### Presets

Settings are grouped into presets: **Full quality** and **Efficient** are built in, and custom ones can be saved on top.

- **Full quality** is for home Wi-Fi and wired networks. It streams at the screen's native resolution and refresh rate.
- **Efficient** is for other networks, like hotels, offices, cafés and phone hotspots. It streams HEVC at 12 Mbps, 60 fps and the screen's size in points. The host stops refining still areas once they look right and sends about a frame a second while nothing changes. For app windows, background windows also update at an eighth of the frame rate and are encoded at lower quality.

## Theme

Colors come from your Zed theme. The launcher reads `~/.config/zed/settings.json`, finds the dark theme named there among your themes and installed extensions, and builds its palette on top of Zed's One Dark. The monospace font is Zed's buffer font. Without Zed, the built-in colors are used.

## Building

```sh
cargo build --release
```

The streaming helper is the Moonlight app, built with `nix build` at the repository root. `./scripts/bundle-macos.sh` builds `target/Sloplight.app` with the helper inside.

Outside the bundle, the helper is looked up in this order:

1. the path set in Settings → Advanced
2. `$SLOPLIGHT_HELPER`
3. next to its own binary
4. the Nix `result` link in the repository root
5. `/Applications/Moonlight.app`
6. `moonlight` on `$PATH`

CI builds `Sloplight.app` for Apple Silicon on `sloplight-v*` tags. The builds aren't notarized, so remove the quarantine flag after unzipping:

```sh
xattr -dr com.apple.quarantine Sloplight.app
```

## Files

Config is stored in `~/Library/Application Support/sloplight` (`hosts.json`, `settings.json`, `identity.json`). `SLOPLIGHT_CONFIG_DIR` overrides it.

`SLOPLIGHT_DEBUG_STATE=settings|info|pairing|add-host|palette|launcher|...` opens the launcher directly on that screen, for UI work without a host.

## Permissions

macOS asks for Local Network access on first launch. Without it, LAN hosts are unreachable and fail with "No route to host". When Sloplight is started from a terminal, the permission applies to the terminal app.

## License

GPL-3.0-or-later, like Moonlight. It uses code and assets from:

- [Moonlight](https://github.com/moonlight-stream/moonlight-qt) and [moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c) (GPL-3.0)
- [GPUI](https://github.com/zed-industries/zed/tree/main/crates/gpui) (Apache-2.0)
- the One Dark theme from [Zed](https://github.com/zed-industries/zed) (GPL-3.0)
- icons from [Lucide](https://lucide.dev) (ISC)

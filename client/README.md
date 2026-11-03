# Sloplight client

A hard fork of [Moonlight PC](https://github.com/moonlight-stream/moonlight-qt) for streaming from a [Sloplight server](../server). On macOS it ships as `Sloplight.app`: the [launcher](launcher) with the streaming client inside. On Windows it is the Moonlight app with this fork's changes. There are no Linux builds for now.

It remains a regular Moonlight client for Sunshine, Apollo and GeForce Experience hosts. Most additions require a Sloplight server, and many are macOS only for now.

## What's different

### Launcher

The [launcher](launcher), written in Rust with GPUI, replaces the QML interface on macOS. Streaming is still handled by the C++ client, which the launcher runs as a helper process.

### App windows

Instead of the whole desktop, individual host windows can be streamed, each appearing on the Mac as a native window.

- Windows behave like native ones: position, size, stacking and focus stay in sync with the host, size limits and resize steps are respected, and title bar double-click follows the macOS setting.
- Menus, popups and dialogs become their own Mac windows.
- Trackpad scrolling and gestures are sent to a virtual precision touchpad on the host.
- `⌘O` opens a launcher for the host's Start menu apps. The selected app is started and its window streamed and focused.

All windows share a single stream: the host places them on a private virtual display and composites them into one video frame, which the client splits back into separate windows. Windows other than the focused one are updated at a lower frame rate, and the stream drops to one frame per second while idle. The Efficient preset also encodes them at lower quality, so their changes take fewer bits. The focused window keeps full quality.

### Input

- Key repeat follows the macOS repeat delay and rate, and repeats are marked as such for the host.
- The host keyboard layout follows the Mac's input source.
- Two-way clipboard sync.

### Apollo extensions

Ported from [Artemis Qt](https://github.com/wjbeckett/artemis):

- Pairing with a one-time password.
- Launching apps by UUID.
- Virtual display, fractional refresh rate and render scale launch options.
- Server commands, and the client's permissions shown in the host details.
- An in-stream quick menu (`Ctrl+Alt+Shift+\`, or `Select+L1+R1+Y` on a gamepad).

Sunshine and GeForce Experience hosts ignore these.

### Other changes

- Lower client-side latency on macOS: the Metal renderer keeps three drawables, and streaming threads run at user-interactive priority.
- A "Capture on present" option that requests the server's low-latency VRR capture.
- A headless `--stream` mode for end-to-end latency tests.

## Building

The macOS build uses Nix, from the repository root:

```sh
nix build
```

This builds the streaming client into `result/`. To build the launcher and bundle it with the client inside:

```sh
cd client/launcher
./scripts/bundle-macos.sh
```

The result is `client/launcher/target/Sloplight.app`.

Use `nix build` rather than `nix build path:.`, which copies the entire working tree, including the multi-gigabyte `launcher/target` and the server's submodules, into the Nix store.

The Windows build follows the [upstream instructions](https://github.com/moonlight-stream/moonlight-qt#building). The app-window features are macOS only.

## License

GPL-3.0, same as Moonlight.

# vibeshine-client

A fork of [Moonlight PC](https://github.com/moonlight-stream/moonlight-qt) for streaming from a [vibeshine-server](https://github.com/oomfslop/vibeshine-server) host to a Mac.

It remains a regular Moonlight client for Sunshine, Apollo and GeForce Experience hosts. Most additions require vibeshine-server, and many are macOS only for now.

## What's different

### Sloplight

[Sloplight](sloplight) is a new launcher written in Rust with GPUI that replaces the QML interface on macOS. Streaming is still handled by the C++ client, which Sloplight runs as a helper process.

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
- A "Capture on present" option that requests Vibeshine's low-latency VRR capture.
- A headless `--stream` mode for end-to-end latency tests.

## Building

The macOS build uses Nix:

```sh
nix build '.?submodules=1'
```

This builds the Moonlight app into `result/`. To build Sloplight and bundle it with the helper inside:

```sh
cd sloplight
./scripts/bundle-macos.sh
```

The result is `sloplight/target/Sloplight.app`.

Use `'.?submodules=1'` rather than `path:.`, which copies the entire working tree, including the multi-gigabyte `sloplight/target`, into the Nix store.

Builds for other platforms follow the [upstream instructions](https://github.com/moonlight-stream/moonlight-qt#building). The app-window features are macOS only.

## License

GPL-3.0, same as Moonlight.

# Sloplight

Streams single Windows apps to a Mac as native windows, plus regular game streaming.

- [client](client): the Sloplight client, a hard fork of Moonlight PC. On macOS it ships as `Sloplight.app`, the [launcher](client/launcher) (Rust, GPUI) with the streaming client inside. On Windows it is the Moonlight app with this fork's changes. No Linux builds for now.
- [server](server): the Sloplight server, a hard fork of Nonary's Vibeshine (Sunshine-based).
- [moonlight-common-c](moonlight-common-c): the protocol library both build against.

Upstream fork points are in [upstream_commits.txt](upstream_commits.txt).

## Building

- Client (macOS): `nix build` here, see [client/README.md](client/README.md#building)
- Server: `git submodule update --init --recursive server`, then follow [server/README.md](server/README.md#building)

Releases are tagged `sloplight-v*` (client) and `server-v*` (server).

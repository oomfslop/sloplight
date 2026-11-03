# vibeshine

Streams single Windows apps to a Mac as native windows, plus regular game streaming.

- [client](client): vibeshine-client, a Moonlight PC fork, and [Sloplight](client/sloplight), its macOS launcher
- [server](server): vibeshine-server, a Vibeshine (Sunshine) fork
- [moonlight-common-c](moonlight-common-c): the protocol library both build against

Upstream fork points are in [upstream_commits.txt](upstream_commits.txt).

## Building

- Client (macOS): `nix build` here, see [client/README.md](client/README.md#building)
- Server: `git submodule update --init --recursive server`, then follow [server/README.md](server/README.md)

Releases are tagged `sloplight-v*` (client) and `server-v*` (server).

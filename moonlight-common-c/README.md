# moonlight-common-c

A fork of [moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c), Moonlight's core streaming library, shared by the Sloplight [client](../client) and [server](../server).

## What's different

- `LiSendExecServerCmd()` runs Apollo server commands.
- `LiSendTrackpadEvent()` sends precision touchpad contacts, each with the time the fingers were there, to the Sloplight server's virtual touchpad.
- Key events can be flagged as repeats. With `LI_FF_CLIENT_KEY_REPEAT`, the host leaves repeat timing to the client.
- On Apple platforms, the streaming threads run at user-interactive QoS.
- Controller haptic PCM, from [Nonary's fork](https://github.com/Nonary/moonlight-common-c).

Hosts that don't know these extensions ignore them, so the library still works with Sunshine and GeForce Experience.

The rest of this README is from upstream.

## Moonlight Streaming Core Library

Moonlight-common-c contains the core GameStream client code shared between [Moonlight](https://moonlight-stream.org) clients, including [Moonlight PC](https://github.com/moonlight-stream/moonlight-qt), [Moonlight Android](https://github.com/moonlight-stream/moonlight-android), [Moonlight iOS](https://github.com/moonlight-stream/moonlight-ios), and [Moonlight Chrome](https://github.com/moonlight-stream/moonlight-chrome).

If you are implementing your own Moonlight game streaming client that can use a C library, you probably want the code here.

### Note to Developers

Moonlight-common-c requires the _specific_ version of ENet that is bundled as a submodule. This version has changes required for IPv6 compatibility and retransmission reliability, among other things. These are breaking API/ABI changes which make Moonlight-common-c incompatible with other versions of the ENet library. Attempting to runtime link to another libenet library will cause your client to crash when connecting to recent versions of GeForce Experience.

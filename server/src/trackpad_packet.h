/**
 * @file src/trackpad_packet.h
 * @brief The precision touchpad packet of the input stream (a Sloplight
 *        protocol extension, sent by LiSendTrackpadEvent()): every finger on
 *        the client's trackpad in one frame, for the host to recognize
 *        scrolling and zooming itself.
 */
#pragma once

#include <cstdint>

#include <moonlight-common-c/src/Input.h>

/// The first clients' packets end before `timestamp`
#define SS_TRACKPAD_PACKET_UNTIMED_SIZE (sizeof(SS_TRACKPAD_PACKET) - sizeof(uint32_t))

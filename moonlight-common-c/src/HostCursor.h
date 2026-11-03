// SPDX-License-Identifier: GPL-3.0-or-later
// Sloplight extension: the host's cursor, sent to clients that launched with hideCursor=1.
// Payload (LE): version=1, flags, x, y, displayWidth, displayHeight (u16, host pixels,
// relative to the cursor's display), imageId (u64, 0: no image).
// With ML_HOST_CURSOR_FLAG_IMAGE: hotX, hotY (u16, host pixels), width, height (u16,
// image pixels), scaleShift (u8, an image pixel is 1 << scaleShift host pixels),
// reserved (u8), then width*height straight-alpha BGRA pixels, rows top-down.
//
// Both ends keep the ids of the last ML_HOST_CURSOR_CACHE_SIZE images, most recently
// used first: every visible cursor with a nonzero id moves that id to the front (adding
// it if new) and the oldest falls off. The host sends an image only when its id isn't kept.
#pragma once

#define ML_HOST_CURSOR_PACKET_TYPE 0x5602
#define ML_HOST_CURSOR_VERSION 1
#define ML_HOST_CURSOR_STATE_SIZE 18
#define ML_HOST_CURSOR_IMAGE_HEADER_SIZE 10
#define ML_HOST_CURSOR_FLAG_VISIBLE 0x01
#define ML_HOST_CURSOR_FLAG_IMAGE 0x02
#define ML_HOST_CURSOR_CACHE_SIZE 16
// Control packets carry a 16-bit length that also covers the encryption overhead
#define ML_HOST_CURSOR_MAX_PIXELS 16000

#pragma once

#include <cstddef>
#include <cstdint>

extern "C" {
#include <libavutil/frame.h>
}

// Decodes record-framed PyroWave frames (see server/docs/pyrowave-protocol.md) with the upstream Metal decoder into IOSurface-backed
// planar CVPixelBuffers, handed out as AV_PIX_FMT_VIDEOTOOLBOX frames
class PyroWaveMetalDecoder
{
public:
    PyroWaveMetalDecoder() = default;
    ~PyroWaveMetalDecoder();
    PyroWaveMetalDecoder(const PyroWaveMetalDecoder&) = delete;
    PyroWaveMetalDecoder& operator=(const PyroWaveMetalDecoder&) = delete;

    bool initialize(int width, int height);

    // Returns false if the frame is malformed or incomplete
    bool decode(const uint8_t* data, size_t size, AVFrame* frame);

private:
    bool pushRecords(const uint8_t* data, size_t size);

    void* m_MetalDevice = nullptr;
    void* m_CommandQueue = nullptr;
    AVBufferRef* m_FramesContext = nullptr;
    struct pyrowave_device_opaque* m_Device = nullptr;
    struct pyrowave_decoder_opaque* m_Decoder = nullptr;
};

// 0 unless the route to address leaves through wired Ethernet
int getRoutedWiredLinkMbps(const char* address);

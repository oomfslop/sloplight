#pragma once

#include "renderer.h"

#include <AudioToolbox/AudioToolbox.h>

#include <atomic>
#include <vector>

class CoreAudioRenderer : public IAudioRenderer
{
public:
    CoreAudioRenderer() = default;

    virtual ~CoreAudioRenderer();

    virtual bool prepareForPlayback(const OPUS_MULTISTREAM_CONFIGURATION* opusConfig);

    virtual void* getAudioBuffer(int* size);

    virtual bool submitAudio(int bytesWritten);

    virtual AudioFormat getAudioBufferFormat();

private:
    static OSStatus renderCallback(void* context, AudioUnitRenderActionFlags* flags, const AudioTimeStamp* timestamp,
                                   UInt32 bus, UInt32 frameCount, AudioBufferList* data);

    void adjustBuffering(uint64_t buffered);

    AudioComponentInstance m_OutputUnit = nullptr;
    AudioComponentInstance m_Varispeed = nullptr;
    int m_Channels = 0;
    int m_SampleRate = 0;
    int m_SamplesPerFrame = 0;
    std::vector<float> m_DecodeBuffer;

    // Single producer (submitAudio) and single consumer (renderCallback), counted in frames
    std::vector<float> m_Ring;
    size_t m_RingFrames = 0;
    std::atomic<uint64_t> m_WriteFrame { 0 };
    std::atomic<uint64_t> m_ReadFrame { 0 };

    // How much the consumer buffers before playing again after running dry
    std::atomic<uint32_t> m_TargetFrames { 0 };
    std::atomic<uint32_t> m_Underruns { 0 };

    // Consumer only
    bool m_Rebuffering = true;
    int m_FadeInFrames = 0;
    std::vector<float> m_LastOutput;

    // Producer only
    std::vector<float> m_SkippedFrame;
    bool m_HasSkippedFrame = false;
    uint32_t m_Drops = 0;
    uint32_t m_HandledUnderruns = 0;
    uint64_t m_WindowStartUs = 0;
    uint64_t m_WindowMinBuffered = UINT64_MAX;
    uint64_t m_LastTargetChangeUs = 0;
    float m_PlaybackRate = 1.0f;
};

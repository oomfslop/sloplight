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

    AudioComponentInstance m_AudioUnit = nullptr;
    int m_Channels = 0;
    int m_SampleRate = 0;
    int m_SamplesPerFrame = 0;
    std::vector<float> m_DecodeBuffer;

    // Single producer (submitAudio) and single consumer (renderCallback), counted in frames
    std::vector<float> m_Ring;
    size_t m_RingFrames = 0;
    std::atomic<uint64_t> m_WriteFrame { 0 };
    std::atomic<uint64_t> m_ReadFrame { 0 };
};

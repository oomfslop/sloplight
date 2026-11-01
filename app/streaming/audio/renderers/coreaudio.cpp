#include "coreaudio.h"

#include "SDL_compat.h"

#include <CoreAudio/CoreAudio.h>

static UInt32 getDeviceProperty(AudioObjectID device, AudioObjectPropertySelector selector)
{
    AudioObjectPropertyAddress address = { selector, kAudioObjectPropertyScopeOutput, kAudioObjectPropertyElementMain };
    UInt32 value = 0;
    UInt32 size = sizeof(value);
    AudioObjectGetPropertyData(device, &address, 0, nullptr, &size, &value);
    return value;
}

CoreAudioRenderer::~CoreAudioRenderer()
{
    if (m_AudioUnit != nullptr) {
        AudioOutputUnitStop(m_AudioUnit);
        AudioUnitUninitialize(m_AudioUnit);
        AudioComponentInstanceDispose(m_AudioUnit);
    }
}

bool CoreAudioRenderer::prepareForPlayback(const OPUS_MULTISTREAM_CONFIGURATION* opusConfig)
{
    m_Channels = opusConfig->channelCount;
    m_SampleRate = opusConfig->sampleRate;
    m_SamplesPerFrame = opusConfig->samplesPerFrame;
    m_DecodeBuffer.resize(m_SamplesPerFrame * m_Channels);
    m_RingFrames = 8 * m_SamplesPerFrame;
    m_Ring.resize(m_RingFrames * m_Channels);

    AudioComponentDescription desc = {};
    desc.componentType = kAudioUnitType_Output;
    desc.componentSubType = kAudioUnitSubType_DefaultOutput;
    desc.componentManufacturer = kAudioUnitManufacturer_Apple;
    AudioComponent component = AudioComponentFindNext(nullptr, &desc);
    if (component == nullptr || AudioComponentInstanceNew(component, &m_AudioUnit) != noErr) {
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Failed to create CoreAudio output unit");
        m_AudioUnit = nullptr;
        return false;
    }

    AudioStreamBasicDescription format = {};
    format.mSampleRate = m_SampleRate;
    format.mFormatID = kAudioFormatLinearPCM;
    format.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked;
    format.mChannelsPerFrame = m_Channels;
    format.mBitsPerChannel = 32;
    format.mFramesPerPacket = 1;
    format.mBytesPerFrame = m_Channels * sizeof(float);
    format.mBytesPerPacket = format.mBytesPerFrame;
    OSStatus err = AudioUnitSetProperty(m_AudioUnit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Input, 0, &format, sizeof(format));
    if (err != noErr) {
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Failed to set CoreAudio stream format: %d", (int)err);
        return false;
    }

    if (m_Channels > 2) {
        // Opus channel order after remapping: FL FR FC LFE BL BR SL SR
        static const AudioChannelLabel labels[] = {
            kAudioChannelLabel_Left, kAudioChannelLabel_Right, kAudioChannelLabel_Center, kAudioChannelLabel_LFEScreen,
            kAudioChannelLabel_LeftSurround, kAudioChannelLabel_RightSurround,
            kAudioChannelLabel_LeftSurroundDirect, kAudioChannelLabel_RightSurroundDirect,
        };
        std::vector<uint8_t> layoutData(offsetof(AudioChannelLayout, mChannelDescriptions) + m_Channels * sizeof(AudioChannelDescription));
        auto layout = reinterpret_cast<AudioChannelLayout*>(layoutData.data());
        layout->mChannelLayoutTag = kAudioChannelLayoutTag_UseChannelDescriptions;
        layout->mNumberChannelDescriptions = m_Channels;
        for (int i = 0; i < m_Channels; i++) {
            layout->mChannelDescriptions[i].mChannelLabel = i < (int)SDL_arraysize(labels) ? labels[i] : kAudioChannelLabel_Unused;
        }
        AudioUnitSetProperty(m_AudioUnit, kAudioUnitProperty_AudioChannelLayout, kAudioUnitScope_Input, 0, layoutData.data(), (UInt32)layoutData.size());
    }

    // Half an Opus frame per device cycle; the device clamps this to its own range
    UInt32 ioFrames = m_SamplesPerFrame / 2;
    AudioUnitSetProperty(m_AudioUnit, kAudioDevicePropertyBufferFrameSize, kAudioUnitScope_Global, 0, &ioFrames, sizeof(ioFrames));

    AURenderCallbackStruct callback = { renderCallback, this };
    err = AudioUnitSetProperty(m_AudioUnit, kAudioUnitProperty_SetRenderCallback, kAudioUnitScope_Input, 0, &callback, sizeof(callback));
    if (err != noErr) {
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Failed to set CoreAudio render callback: %d", (int)err);
        return false;
    }

    err = AudioUnitInitialize(m_AudioUnit);
    if (err != noErr) {
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "AudioUnitInitialize() failed: %d", (int)err);
        return false;
    }

    AudioObjectID device = kAudioObjectUnknown;
    UInt32 size = sizeof(device);
    AudioUnitGetProperty(m_AudioUnit, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global, 0, &device, &size);
    UInt32 deviceRate = 0;
    {
        Float64 nominalRate = 0;
        AudioObjectPropertyAddress address = { kAudioDevicePropertyNominalSampleRate, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain };
        size = sizeof(nominalRate);
        AudioObjectGetPropertyData(device, &address, 0, nullptr, &size, &nominalRate);
        deviceRate = (UInt32)nominalRate;
    }
    UInt32 latencyFrames = getDeviceProperty(device, kAudioDevicePropertyLatency);
    UInt32 safetyFrames = getDeviceProperty(device, kAudioDevicePropertySafetyOffset);
    UInt32 bufferFrames = getDeviceProperty(device, kAudioDevicePropertyBufferFrameSize);
    SDL_LogInfo(SDL_LOG_CATEGORY_APPLICATION,
                "CoreAudio output: %u Hz, buffer %u frames, latency %u frames, safety offset %u frames",
                (unsigned)deviceRate, (unsigned)bufferFrames, (unsigned)latencyFrames, (unsigned)safetyFrames);

    err = AudioOutputUnitStart(m_AudioUnit);
    if (err != noErr) {
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "AudioOutputUnitStart() failed: %d", (int)err);
        return false;
    }

    return true;
}

void* CoreAudioRenderer::getAudioBuffer(int*)
{
    return m_DecodeBuffer.data();
}

bool CoreAudioRenderer::submitAudio(int bytesWritten)
{
    UInt32 frames = bytesWritten / (m_Channels * sizeof(float));
    if (frames == 0) {
        return true;
    }

    uint64_t write = m_WriteFrame.load(std::memory_order_relaxed);
    uint64_t buffered = write - m_ReadFrame.load(std::memory_order_acquire);

    // Clock drift between host and output device slowly grows the backlog:
    // past two Opus frames, skip this one instead of adding latency
    if (buffered + frames > m_RingFrames || buffered > 2 * (uint64_t)m_SamplesPerFrame) {
        return true;
    }

    for (UInt32 i = 0; i < frames; i++) {
        size_t slot = (size_t)((write + i) % m_RingFrames) * m_Channels;
        memcpy(&m_Ring[slot], &m_DecodeBuffer[i * m_Channels], m_Channels * sizeof(float));
    }
    m_WriteFrame.store(write + frames, std::memory_order_release);
    return true;
}

IAudioRenderer::AudioFormat CoreAudioRenderer::getAudioBufferFormat()
{
    return AudioFormat::Float32NE;
}

OSStatus CoreAudioRenderer::renderCallback(void* context, AudioUnitRenderActionFlags*, const AudioTimeStamp*,
                                           UInt32, UInt32 frameCount, AudioBufferList* data)
{
    auto me = static_cast<CoreAudioRenderer*>(context);
    auto out = static_cast<float*>(data->mBuffers[0].mData);

    uint64_t read = me->m_ReadFrame.load(std::memory_order_relaxed);
    uint64_t available = me->m_WriteFrame.load(std::memory_order_acquire) - read;
    UInt32 frames = (UInt32)SDL_min((uint64_t)frameCount, available);
    for (UInt32 i = 0; i < frames; i++) {
        size_t slot = (size_t)((read + i) % me->m_RingFrames) * me->m_Channels;
        memcpy(&out[i * me->m_Channels], &me->m_Ring[slot], me->m_Channels * sizeof(float));
    }
    me->m_ReadFrame.store(read + frames, std::memory_order_release);

    if (frames < frameCount) {
        memset(&out[frames * me->m_Channels], 0, (frameCount - frames) * me->m_Channels * sizeof(float));
    }
    return noErr;
}

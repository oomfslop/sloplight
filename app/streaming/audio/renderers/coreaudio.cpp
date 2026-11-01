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

static AudioComponentInstance createUnit(OSType type, OSType subType)
{
    AudioComponentDescription desc = {};
    desc.componentType = type;
    desc.componentSubType = subType;
    desc.componentManufacturer = kAudioUnitManufacturer_Apple;
    AudioComponent component = AudioComponentFindNext(nullptr, &desc);
    AudioComponentInstance unit = nullptr;
    if (component == nullptr || AudioComponentInstanceNew(component, &unit) != noErr) {
        return nullptr;
    }
    return unit;
}

CoreAudioRenderer::~CoreAudioRenderer()
{
    if (m_OutputUnit != nullptr) {
        AudioOutputUnitStop(m_OutputUnit);
        AudioUnitUninitialize(m_OutputUnit);
        AudioComponentInstanceDispose(m_OutputUnit);
    }
    if (m_Varispeed != nullptr) {
        AudioUnitUninitialize(m_Varispeed);
        AudioComponentInstanceDispose(m_Varispeed);
    }
    if (m_WriteFrame.load() > 0) {
        SDL_LogInfo(SDL_LOG_CATEGORY_APPLICATION,
                    "CoreAudio: %u underruns, %u dropped frames, final buffer target %.1f ms",
                    m_Underruns.load(), m_Drops, m_TargetFrames.load() * 1000.0 / m_SampleRate);
    }
}

bool CoreAudioRenderer::prepareForPlayback(const OPUS_MULTISTREAM_CONFIGURATION* opusConfig)
{
    m_Channels = opusConfig->channelCount;
    m_SampleRate = opusConfig->sampleRate;
    m_SamplesPerFrame = opusConfig->samplesPerFrame;
    m_DecodeBuffer.resize(m_SamplesPerFrame * m_Channels);
    m_RingFrames = 16 * m_SamplesPerFrame;
    m_Ring.resize(m_RingFrames * m_Channels);
    m_LastOutput.resize(m_Channels);
    m_SkippedFrame.reserve(m_DecodeBuffer.size());
    m_TargetFrames = m_SamplesPerFrame / 2;

    m_OutputUnit = createUnit(kAudioUnitType_Output, kAudioUnitSubType_DefaultOutput);
    m_Varispeed = createUnit(kAudioUnitType_FormatConverter, kAudioUnitSubType_Varispeed);
    if (m_OutputUnit == nullptr || m_Varispeed == nullptr) {
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Failed to create CoreAudio units");
        return false;
    }

    AudioStreamBasicDescription format = {};
    format.mSampleRate = m_SampleRate;
    format.mFormatID = kAudioFormatLinearPCM;
    // Varispeed only takes non-interleaved float
    format.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked | kAudioFormatFlagIsNonInterleaved;
    format.mChannelsPerFrame = m_Channels;
    format.mBitsPerChannel = 32;
    format.mFramesPerPacket = 1;
    format.mBytesPerFrame = sizeof(float);
    format.mBytesPerPacket = format.mBytesPerFrame;
    OSStatus err = AudioUnitSetProperty(m_Varispeed, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Input, 0, &format, sizeof(format));
    if (err == noErr) {
        err = AudioUnitSetProperty(m_Varispeed, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Output, 0, &format, sizeof(format));
    }
    if (err == noErr) {
        err = AudioUnitSetProperty(m_OutputUnit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Input, 0, &format, sizeof(format));
    }
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
        AudioUnitSetProperty(m_OutputUnit, kAudioUnitProperty_AudioChannelLayout, kAudioUnitScope_Input, 0, layoutData.data(), (UInt32)layoutData.size());
    }

    // Half an Opus frame per device cycle; the device clamps this to its own range
    UInt32 ioFrames = m_SamplesPerFrame / 2;
    AudioUnitSetProperty(m_OutputUnit, kAudioDevicePropertyBufferFrameSize, kAudioUnitScope_Global, 0, &ioFrames, sizeof(ioFrames));

    AURenderCallbackStruct callback = { renderCallback, this };
    err = AudioUnitSetProperty(m_Varispeed, kAudioUnitProperty_SetRenderCallback, kAudioUnitScope_Input, 0, &callback, sizeof(callback));
    if (err != noErr) {
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Failed to set CoreAudio render callback: %d", (int)err);
        return false;
    }

    AudioUnitConnection connection = { m_Varispeed, 0, 0 };
    err = AudioUnitSetProperty(m_OutputUnit, kAudioUnitProperty_MakeConnection, kAudioUnitScope_Input, 0, &connection, sizeof(connection));
    if (err != noErr) {
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Failed to connect CoreAudio units: %d", (int)err);
        return false;
    }

    err = AudioUnitInitialize(m_Varispeed);
    if (err == noErr) {
        err = AudioUnitInitialize(m_OutputUnit);
    }
    if (err != noErr) {
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "AudioUnitInitialize() failed: %d", (int)err);
        return false;
    }

    AudioObjectID device = kAudioObjectUnknown;
    UInt32 size = sizeof(device);
    AudioUnitGetProperty(m_OutputUnit, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global, 0, &device, &size);
    Float64 deviceRate = 0;
    AudioObjectPropertyAddress rateAddress = { kAudioDevicePropertyNominalSampleRate, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain };
    size = sizeof(deviceRate);
    AudioObjectGetPropertyData(device, &rateAddress, 0, nullptr, &size, &deviceRate);
    UInt32 latencyFrames = getDeviceProperty(device, kAudioDevicePropertyLatency);
    Float64 varispeedLatency = 0;
    size = sizeof(varispeedLatency);
    AudioUnitGetProperty(m_Varispeed, kAudioUnitProperty_Latency, kAudioUnitScope_Global, 0, &varispeedLatency, &size);
    SDL_LogInfo(SDL_LOG_CATEGORY_APPLICATION,
                "CoreAudio output: %.0f Hz, buffer %u frames, latency %u frames, varispeed latency %.2f ms",
                deviceRate, (unsigned)getDeviceProperty(device, kAudioDevicePropertyBufferFrameSize),
                (unsigned)latencyFrames, varispeedLatency * 1000);

    err = AudioOutputUnitStart(m_OutputUnit);
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

void CoreAudioRenderer::adjustBuffering(uint64_t buffered)
{
    uint64_t nowUs = LiGetMicroseconds();
    m_WindowMinBuffered = SDL_min(m_WindowMinBuffered, buffered);
    if (nowUs - m_WindowStartUs < 500000) {
        return;
    }

    uint32_t target = m_TargetFrames.load();
    uint32_t underruns = m_Underruns.load();
    if (underruns != m_HandledUnderruns) {
        // A late packet ran us dry: hold more from now on
        m_HandledUnderruns = underruns;
        target = SDL_min(target + (uint32_t)m_SamplesPerFrame, 6 * (uint32_t)m_SamplesPerFrame);
        m_TargetFrames = target;
        m_LastTargetChangeUs = nowUs;
        SDL_LogInfo(SDL_LOG_CATEGORY_APPLICATION, "CoreAudio: underrun, buffer target now %.1f ms", target * 1000.0 / m_SampleRate);
    }
    else if (nowUs - m_LastTargetChangeUs > 30000000 && target > (uint32_t)m_SamplesPerFrame / 2) {
        target -= m_SamplesPerFrame / 2;
        m_TargetFrames = target;
        m_LastTargetChangeUs = nowUs;
    }

    // The host's clock never quite matches the output device's: play slightly
    // faster or slower to keep the buffer near its target instead of dropping
    // or running dry. 0.2% is a few cents of pitch, which nobody hears.
    float rate = 1.0f;
    if (m_WindowMinBuffered > target + m_SamplesPerFrame) {
        rate = 1.002f;
    }
    else if (m_WindowMinBuffered + m_SamplesPerFrame / 4 < target) {
        rate = 0.998f;
    }
    if (rate != m_PlaybackRate) {
        m_PlaybackRate = rate;
        AudioUnitSetParameter(m_Varispeed, kVarispeedParam_PlaybackRate, kAudioUnitScope_Global, 0, rate, 0);
    }

    m_WindowStartUs = nowUs;
    m_WindowMinBuffered = UINT64_MAX;
}

bool CoreAudioRenderer::submitAudio(int bytesWritten)
{
    UInt32 frames = bytesWritten / (m_Channels * sizeof(float));
    if (frames == 0) {
        return true;
    }

    uint64_t write = m_WriteFrame.load(std::memory_order_relaxed);
    uint64_t buffered = write - m_ReadFrame.load(std::memory_order_acquire);
    adjustBuffering(buffered);

    // Only a burst after a stall, or the device's clock swinging (AirPods do when
    // a window goes fullscreen), gets this far ahead; the rate adjustment would
    // take too long to catch up
    if (buffered + frames > m_RingFrames || buffered > m_TargetFrames.load() + 4 * (uint64_t)m_SamplesPerFrame) {
        m_Drops++;
        if (!m_HasSkippedFrame) {
            m_SkippedFrame.assign(m_DecodeBuffer.begin(), m_DecodeBuffer.begin() + frames * m_Channels);
            m_HasSkippedFrame = true;
        }
        return true;
    }

    // The first skipped frame continues what was written before it, so fading it
    // out into this one splices the two without a click
    int fadeFrames = m_HasSkippedFrame ? (int)SDL_min(frames, (UInt32)(m_SkippedFrame.size() / m_Channels)) : 0;
    m_HasSkippedFrame = false;
    for (UInt32 i = 0; i < frames; i++) {
        float* dst = &m_Ring[(size_t)((write + i) % m_RingFrames) * m_Channels];
        const float* src = &m_DecodeBuffer[i * m_Channels];
        if ((int)i < fadeFrames) {
            float mix = (float)i / fadeFrames;
            for (int c = 0; c < m_Channels; c++) {
                dst[c] = src[c] * mix + m_SkippedFrame[i * m_Channels + c] * (1.0f - mix);
            }
        }
        else {
            memcpy(dst, src, m_Channels * sizeof(float));
        }
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
    int channels = me->m_Channels;

    uint64_t read = me->m_ReadFrame.load(std::memory_order_relaxed);
    uint64_t available = me->m_WriteFrame.load(std::memory_order_acquire) - read;
    if (me->m_Rebuffering && available >= me->m_TargetFrames.load() + frameCount) {
        me->m_Rebuffering = false;
        me->m_FadeInFrames = me->m_SampleRate / 1000;
    }

    UInt32 frames = me->m_Rebuffering ? 0 : (UInt32)SDL_min((uint64_t)frameCount, available);
    int fadeLength = me->m_SampleRate / 1000;
    for (UInt32 i = 0; i < frames; i++) {
        const float* src = &me->m_Ring[(size_t)((read + i) % me->m_RingFrames) * channels];
        float gain = 1.0f;
        if (me->m_FadeInFrames > 0) {
            gain = 1.0f - (float)me->m_FadeInFrames / fadeLength;
            me->m_FadeInFrames--;
        }
        for (int c = 0; c < channels; c++) {
            static_cast<float*>(data->mBuffers[c].mData)[i] = src[c] * gain;
        }
    }
    me->m_ReadFrame.store(read + frames, std::memory_order_release);
    if (frames > 0) {
        for (int c = 0; c < channels; c++) {
            me->m_LastOutput[c] = static_cast<float*>(data->mBuffers[c].mData)[frames - 1];
        }
    }

    if (frames < frameCount) {
        if (!me->m_Rebuffering) {
            me->m_Rebuffering = true;
            me->m_Underruns.fetch_add(1, std::memory_order_relaxed);
        }
        // Decay from the last sample instead of cutting to silence, which clicks
        for (UInt32 i = frames; i < frameCount; i++) {
            for (int c = 0; c < channels; c++) {
                me->m_LastOutput[c] *= 0.9f;
                static_cast<float*>(data->mBuffers[c].mData)[i] = me->m_LastOutput[c];
            }
        }
    }
    return noErr;
}

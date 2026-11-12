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
    m_RingFrames = m_SampleRate / 2;
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

    AudioObjectID device = kAudioObjectUnknown;
    UInt32 size = sizeof(device);
    AudioUnitGetProperty(m_OutputUnit, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global, 0, &device, &size);
    AudioObjectPropertyAddress transportAddress = { kAudioDevicePropertyTransportType, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain };
    UInt32 transport = 0;
    size = sizeof(transport);
    AudioObjectGetPropertyData(device, &transportAddress, 0, nullptr, &size, &transport);

    // Shrinking the buffer on AirPods makes other apps' running audio glitch when
    // Game Mode retunes the link, and saves little next to their 30+ ms of latency
    if (transport != kAudioDeviceTransportTypeBluetooth && transport != kAudioDeviceTransportTypeBluetoothLE) {
        // Half an Opus frame per device cycle; the device clamps this to its own range
        UInt32 ioFrames = m_SamplesPerFrame / 2;
        AudioUnitSetProperty(m_OutputUnit, kAudioDevicePropertyBufferFrameSize, kAudioUnitScope_Global, 0, &ioFrames, sizeof(ioFrames));
    }

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

static const uint64_t k_JitterBucketUs = 5000000;
static const uint64_t k_StreamGapUs = 1000000;
static const int k_MaxTargetMs = 150;
static const uint64_t k_WarmupUs = 2000000;

void CoreAudioRenderer::trackArrival(uint64_t nowUs, UInt32 frames)
{
    // A pause longer than any stall we'd buffer for is a gap in the stream
    // (host stopped sending, packets flushed), not lateness
    if (m_FirstArrivalUs == 0) {
        m_FirstArrivalUs = nowUs;
    }
    // The host's backlog arrives in a burst at the start, ahead of the
    // schedule everything after it follows
    if (nowUs - m_FirstArrivalUs < k_WarmupUs) {
        return;
    }

    if (m_LastArrivalUs == 0 || nowUs - m_LastArrivalUs > k_StreamGapUs) {
        m_TimelineStartUs = nowUs;
        m_TimelineFrames = 0;
        m_JitterBuckets.fill({ INT64_MAX, 0 });
        m_JitterBucketStartUs = nowUs;
    }
    m_LastArrivalUs = nowUs;

    if (nowUs - m_JitterBucketStartUs >= k_JitterBucketUs) {
        m_JitterBucket = (m_JitterBucket + 1) % m_JitterBuckets.size();
        m_JitterBuckets[m_JitterBucket] = { INT64_MAX, 0 };
        m_JitterBucketStartUs = nowUs;
    }

    int64_t delayUs = (int64_t)(nowUs - m_TimelineStartUs) - (int64_t)(m_TimelineFrames * 1000000 / m_SampleRate);
    m_TimelineFrames += frames;

    // The earliest arrival of the last two buckets is "on time"; looking back
    // only that far follows the drift between the host's clock and ours
    JitterBucket& current = m_JitterBuckets[m_JitterBucket];
    const JitterBucket& previous = m_JitterBuckets[(m_JitterBucket + m_JitterBuckets.size() - 1) % m_JitterBuckets.size()];
    current.minDelayUs = SDL_min(current.minDelayUs, delayUs);
    current.maxLatenessUs = SDL_max(current.maxLatenessUs, delayUs - SDL_min(current.minDelayUs, previous.minDelayUs));

    int64_t peakUs = 0;
    for (const JitterBucket& bucket : m_JitterBuckets) {
        peakUs = SDL_max(peakUs, bucket.maxLatenessUs);
    }
    m_JitterFrames = (uint32_t)(peakUs * m_SampleRate / 1000000);
}

void CoreAudioRenderer::adjustBuffering(uint64_t nowUs, uint64_t buffered)
{
    uint32_t underruns = m_Underruns.load();
    if (underruns != m_HandledUnderruns) {
        m_HandledUnderruns = underruns;
        m_UnderrunPadFrames = SDL_min(m_UnderrunPadFrames + (uint32_t)m_SamplesPerFrame, 4 * (uint32_t)m_SamplesPerFrame);
        m_LastPadChangeUs = nowUs;
    }
    else if (m_UnderrunPadFrames > 0 && nowUs - m_LastPadChangeUs > 30000000) {
        m_UnderrunPadFrames -= SDL_min(m_UnderrunPadFrames, (uint32_t)m_SamplesPerFrame / 2);
        m_LastPadChangeUs = nowUs;
    }

    uint32_t desired = SDL_min(m_JitterFrames + m_SamplesPerFrame / 2 + m_UnderrunPadFrames,
                               (uint32_t)(m_SampleRate * k_MaxTargetMs / 1000));
    uint32_t target = m_TargetFrames.load();
    bool windowEnded = nowUs - m_WindowStartUs >= 500000;
    if (desired > target) {
        // Grow at once, so the burst that follows a stall is kept
        target = desired;
    }
    else if (windowEnded && desired < target) {
        target = SDL_max(desired, target - SDL_min(target, (uint32_t)m_SamplesPerFrame / 2));
    }
    m_TargetFrames = target;

    if (target > m_LoggedTargetFrames + m_SamplesPerFrame || target + m_SamplesPerFrame < m_LoggedTargetFrames) {
        m_LoggedTargetFrames = target;
        SDL_LogInfo(SDL_LOG_CATEGORY_APPLICATION, "CoreAudio: buffer target %.1f ms (jitter %.1f ms, underrun pad %.1f ms)",
                    target * 1000.0 / m_SampleRate, m_JitterFrames * 1000.0 / m_SampleRate, m_UnderrunPadFrames * 1000.0 / m_SampleRate);
    }

    m_WindowMinBuffered = SDL_min(m_WindowMinBuffered, buffered);
    if (!windowEnded) {
        return;
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

    uint64_t nowUs = LiGetMicroseconds();
    uint64_t write = m_WriteFrame.load(std::memory_order_relaxed);
    uint64_t buffered = write - m_ReadFrame.load(std::memory_order_acquire);
    trackArrival(nowUs, frames);
    adjustBuffering(nowUs, buffered);

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

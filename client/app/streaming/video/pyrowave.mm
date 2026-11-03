#include "pyrowave.h"

#include <pyrowave_metal.h>
#include <SDL.h>

#include <ifaddrs.h>
#include <net/if.h>
#include <net/route.h>
#include <netdb.h>
#include <string>
#include <sys/socket.h>
#include <sys/sysctl.h>
#include <unistd.h>
#include <vector>

#import <CoreVideo/CoreVideo.h>
#import <SystemConfiguration/SystemConfiguration.h>
#import <IOSurface/IOSurface.h>
#import <Metal/Metal.h>

extern "C" {
#include <libavutil/hwcontext.h>
}

// Vibeshine pads frames with 0xFFFFFFFF, N, then N zero words
static constexpr uint32_t k_PaddingMagic = 0xFFFFFFFFu;

static uint32_t readWord(const uint8_t* p)
{
    return p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24);
}

static void logPyroWaveMessage(void*, const char* msg)
{
    SDL_LogWarn(SDL_LOG_CATEGORY_APPLICATION, "PyroWave: %s", msg);
}

static bool isSameAddress(const sockaddr* a, const sockaddr_storage& b)
{
    if (a->sa_family != b.ss_family) {
        return false;
    }
    if (a->sa_family == AF_INET) {
        return ((const sockaddr_in*)a)->sin_addr.s_addr == ((const sockaddr_in*)&b)->sin_addr.s_addr;
    }
    return a->sa_family == AF_INET6 &&
           memcmp(&((const sockaddr_in6*)a)->sin6_addr, &((const sockaddr_in6*)&b)->sin6_addr, sizeof(in6_addr)) == 0;
}

static bool isEthernetInterface(const std::string& name)
{
    bool ethernet = false;
    CFArrayRef interfaces = SCNetworkInterfaceCopyAll();
    for (CFIndex i = 0; interfaces && i < CFArrayGetCount(interfaces); i++) {
        auto interface = (SCNetworkInterfaceRef)CFArrayGetValueAtIndex(interfaces, i);
        CFStringRef bsdName = SCNetworkInterfaceGetBSDName(interface);
        if (bsdName && name == [(NSString*)bsdName UTF8String]) {
            ethernet = CFEqual(SCNetworkInterfaceGetInterfaceType(interface), kSCNetworkInterfaceTypeEthernet);
            break;
        }
    }
    if (interfaces) {
        CFRelease(interfaces);
    }
    return ethernet;
}

int getRoutedWiredLinkMbps(const char* address)
{
    addrinfo hints = {};
    hints.ai_socktype = SOCK_DGRAM;
    addrinfo* info = nullptr;
    if (getaddrinfo(address, "47998", &hints, &info) != 0) {
        return 0;
    }

    // Connecting a UDP socket only picks the route; nothing is sent
    sockaddr_storage local = {};
    socklen_t localLength = sizeof(local);
    int fd = socket(info->ai_family, SOCK_DGRAM, 0);
    bool routed = fd >= 0 &&
                  connect(fd, info->ai_addr, info->ai_addrlen) == 0 &&
                  getsockname(fd, (sockaddr*)&local, &localLength) == 0;
    if (fd >= 0) {
        close(fd);
    }
    freeaddrinfo(info);

    ifaddrs* addresses = nullptr;
    if (!routed || getifaddrs(&addresses) != 0) {
        return 0;
    }
    std::string name;
    for (ifaddrs* ifa = addresses; ifa && name.empty(); ifa = ifa->ifa_next) {
        if (ifa->ifa_addr && isSameAddress(ifa->ifa_addr, local)) {
            name = ifa->ifa_name;
        }
    }
    freeifaddrs(addresses);
    if (name.empty() || !isEthernetInterface(name)) {
        return 0;
    }

    // getifaddrs() only exposes the 32-bit if_data, which can't hold 10 GbE
    int mib[] = { CTL_NET, PF_ROUTE, 0, 0, NET_RT_IFLIST2, (int)if_nametoindex(name.c_str()) };
    size_t length = 0;
    if (mib[5] == 0 || sysctl(mib, 6, nullptr, &length, nullptr, 0) != 0) {
        return 0;
    }
    std::vector<char> messages(length);
    if (sysctl(mib, 6, messages.data(), &length, nullptr, 0) != 0) {
        return 0;
    }
    for (size_t pos = 0; pos + sizeof(if_msghdr) <= length;) {
        auto header = (const if_msghdr*)(messages.data() + pos);
        if (header->ifm_msglen == 0) {
            break;
        }
        if (header->ifm_type == RTM_IFINFO2 && pos + sizeof(if_msghdr2) <= length) {
            return (int)(((const if_msghdr2*)header)->ifm_data.ifi_baudrate / 1000000);
        }
        pos += header->ifm_msglen;
    }
    return 0;
}

PyroWaveMetalDecoder::~PyroWaveMetalDecoder()
{
    if (m_Decoder) {
        pyrowave_decoder_destroy(m_Decoder);
    }
    if (m_Device) {
        pyrowave_device_destroy(m_Device);
    }
    av_buffer_unref(&m_FramesContext);
    [(id<MTLCommandQueue>)m_CommandQueue release];
    [(id<MTLDevice>)m_MetalDevice release];
}

bool PyroWaveMetalDecoder::initialize(int width, int height)
{ @autoreleasepool {

    id<MTLDevice> device = MTLCreateSystemDefaultDevice();
    if (!device || !pyrowave_device_is_supported(device)) {
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "PyroWave needs an Apple silicon GPU");
        [device release];
        return false;
    }
    m_MetalDevice = device;
    m_CommandQueue = [device newCommandQueue];

    pyrowave_device_create_info deviceInfo = {};
    deviceInfo.mtl_device = device;
    deviceInfo.message_callback = logPyroWaveMessage;
    pyrowave_result result = pyrowave_device_create(&deviceInfo, &m_Device);
    if (result != PYROWAVE_SUCCESS) {
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "pyrowave_device_create() failed: %s", pyrowave_result_to_string(result));
        return false;
    }

    pyrowave_decoder_create_info decoderInfo = {};
    decoderInfo.device = m_Device;
    decoderInfo.width = width;
    decoderInfo.height = height;
    decoderInfo.chroma = PYROWAVE_CHROMA_SUBSAMPLING_420;
    result = pyrowave_decoder_create(&decoderInfo, &m_Decoder);
    if (result != PYROWAVE_SUCCESS) {
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "pyrowave_decoder_create() failed: %s", pyrowave_result_to_string(result));
        return false;
    }

    AVBufferRef* deviceContext = nullptr;
    int err = av_hwdevice_ctx_create(&deviceContext, AV_HWDEVICE_TYPE_VIDEOTOOLBOX, nullptr, nullptr, 0);
    if (err < 0) {
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "av_hwdevice_ctx_create() failed: %d", err);
        return false;
    }
    m_FramesContext = av_hwframe_ctx_alloc(deviceContext);
    av_buffer_unref(&deviceContext);
    if (!m_FramesContext) {
        return false;
    }
    auto framesContext = (AVHWFramesContext*)m_FramesContext->data;
    framesContext->format = AV_PIX_FMT_VIDEOTOOLBOX;
    framesContext->sw_format = AV_PIX_FMT_YUV420P;
    framesContext->width = width;
    framesContext->height = height;
    err = av_hwframe_ctx_init(m_FramesContext);
    if (err < 0) {
        SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "av_hwframe_ctx_init() failed: %d", err);
        return false;
    }

    return true;
}}

bool PyroWaveMetalDecoder::pushRecords(const uint8_t* data, size_t size)
{
    if (size % 4 != 0) {
        return false;
    }

    size_t runStart = 0;
    size_t pos = 0;
    while (pos < size) {
        if (size - pos < 8) {
            return false;
        }

        uint32_t word = readWord(data + pos);
        if (word == k_PaddingMagic) {
            size_t paddingSize = 8 + 4 * (size_t)readWord(data + pos + 4);
            if (paddingSize > size - pos) {
                return false;
            }
            if (pos > runStart && pyrowave_decoder_push_packet(m_Decoder, data + runStart, pos - runStart) != PYROWAVE_SUCCESS) {
                return false;
            }
            pos += paddingSize;
            runStart = pos;
            continue;
        }

        size_t recordSize;
        if (word & 0x80000000u) {
            // Sequence header
            recordSize = 8;
        }
        else {
            // Block header: payload_words (including the header) in bits 16-27
            size_t payloadWords = (word >> 16) & 0xFFF;
            if (payloadWords < 2) {
                return false;
            }
            recordSize = payloadWords * 4;
        }
        if (recordSize > size - pos) {
            return false;
        }
        pos += recordSize;
    }

    return pos == runStart || pyrowave_decoder_push_packet(m_Decoder, data + runStart, pos - runStart) == PYROWAVE_SUCCESS;
}

bool PyroWaveMetalDecoder::decode(const uint8_t* data, size_t size, AVFrame* frame)
{ @autoreleasepool {
    // Clearing per frame keeps the 3-bit sequence counter from discarding
    // frames after several consecutive drops
    pyrowave_decoder_clear(m_Decoder);
    if (!pushRecords(data, size) || !pyrowave_decoder_decode_is_ready(m_Decoder, false)) {
        return false;
    }

    if (av_hwframe_get_buffer(m_FramesContext, frame, 0) < 0) {
        return false;
    }
    auto pixelBuffer = reinterpret_cast<CVPixelBufferRef>(frame->data[3]);

    id<MTLDevice> device = (id<MTLDevice>)m_MetalDevice;
    IOSurfaceRef surface = CVPixelBufferGetIOSurface(pixelBuffer);
    id<MTLTexture> planes[3] = {};
    pyrowave_gpu_buffers buffers = {};
    for (int i = 0; i < 3; i++) {
        MTLTextureDescriptor* desc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR8Unorm
                                                                                        width:CVPixelBufferGetWidthOfPlane(pixelBuffer, i)
                                                                                       height:CVPixelBufferGetHeightOfPlane(pixelBuffer, i)
                                                                                    mipmapped:NO];
        desc.usage = MTLTextureUsageShaderWrite | MTLTextureUsageShaderRead;
        desc.storageMode = MTLStorageModeShared;
        planes[i] = [device newTextureWithDescriptor:desc iosurface:surface plane:i];
        buffers.planes[i] = planes[i];
    }

    id<MTLCommandBuffer> commandBuffer = [(id<MTLCommandQueue>)m_CommandQueue commandBuffer];
    pyrowave_result result = PYROWAVE_ERROR_GENERIC;
    if (planes[0] && planes[1] && planes[2]) {
        result = pyrowave_decoder_decode_gpu_buffer(m_Decoder, commandBuffer, &buffers);
    }
    for (int i = 0; i < 3; i++) {
        [planes[i] release];
    }
    if (result != PYROWAVE_SUCCESS) {
        av_frame_unref(frame);
        return false;
    }

    // The renderer samples the planes on another queue, so the decode must be done first
    [commandBuffer commit];
    [commandBuffer waitUntilCompleted];

    frame->chroma_location = AVCHROMA_LOC_CENTER;
    return true;
}}

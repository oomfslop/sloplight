#include "input.h"

#include <Limelight.h>
#include <HostCursor.h>
#include "SDL_compat.h"
#include "streaming/streamutils.h"

#include <cmath>

#ifdef Q_OS_DARWIN
#include <dlfcn.h>
#include <mach-o/dyld.h>

namespace {

// sdl2-compat passes SDL3 cursors through as they are, but its surfaces can't carry
// the alternate images SDL3's Cocoa backend makes HiDPI cursors from
struct Sdl3CursorApi {
    void* (*createSurfaceFrom)(int width, int height, Uint32 format, void* pixels, int pitch);
    bool (*addSurfaceAlternateImage)(void* surface, void* image);
    void (*destroySurface)(void* surface);
    SDL_Cursor* (*createColorCursor)(void* surface, int hotX, int hotY);
};

const Sdl3CursorApi* getSdl3CursorApi()
{
    static const Sdl3CursorApi api = [] {
        Sdl3CursorApi api = {};
        for (uint32_t i = 0; i < _dyld_image_count(); i++) {
            const char* name = _dyld_get_image_name(i);
            void* library = strstr(name, "libSDL3") != nullptr ? dlopen(name, RTLD_LAZY | RTLD_NOLOAD) : nullptr;
            if (library != nullptr) {
                api.createSurfaceFrom = (decltype(api.createSurfaceFrom))dlsym(library, "SDL_CreateSurfaceFrom");
                api.addSurfaceAlternateImage = (decltype(api.addSurfaceAlternateImage))dlsym(library, "SDL_AddSurfaceAlternateImage");
                api.destroySurface = (decltype(api.destroySurface))dlsym(library, "SDL_DestroySurface");
                api.createColorCursor = (decltype(api.createColorCursor))dlsym(library, "SDL_CreateColorCursor");
                break;
            }
        }
        return api;
    }();
    return api.createSurfaceFrom && api.addSurfaceAlternateImage && api.destroySurface && api.createColorCursor ? &api : nullptr;
}

}
#endif

void SdlInputHandler::notifyMouseLeave()
{
    if (m_NeedsManualCaptureOnLeave) {
        // SDL on Windows doesn't send the mouse button up until the mouse re-enters the window
        // after leaving it. This breaks some of the Aero snap gestures, so we'll capture it to
        // allow us to receive the mouse button up events later.
        //
        // On macOS and X11, capturing the mouse allows us to receive mouse motion outside the
        // window (button up already worked without capture).
        if (m_AbsoluteMouseMode && isCaptureActive()) {
            // NB: Not using SDL_GetGlobalMouseState() because we want our state not the system's
            Uint32 mouseState = SDL_GetMouseState(nullptr, nullptr);
            for (Uint32 button = SDL_BUTTON_LEFT; button <= SDL_BUTTON_X2; button++) {
                if (mouseState & SDL_BUTTON(button)) {
                    SDL_CaptureMouse(SDL_TRUE);
                    break;
                }
            }
        }
    }
}

void SdlInputHandler::handleMouseButtonEvent(SDL_MouseButtonEvent* event)
{
    int button;

    if (event->which == SDL_TOUCH_MOUSEID) {
        // Ignore synthetic mouse events
        return;
    }
    else if (!isCaptureActive()) {
        if (event->button == SDL_BUTTON_LEFT && event->state == SDL_RELEASED &&
                isMouseInVideoRegion(event->x, event->y)) {
            // Capture the mouse again if clicked when unbound.
            // We start capture on left button released instead of
            // pressed to avoid sending an errant mouse button released
            // event to the host when clicking into our window (since
            // the pressed event was consumed by this code).
            setCaptureActive(true);
        }

        // Not capturing
        return;
    }
    else if (m_AbsoluteMouseMode && !isMouseInVideoRegion(event->x, event->y) && event->state == SDL_PRESSED) {
        // Ignore button presses outside the video region, but allow button releases
        return;
    }

    switch (event->button)
    {
        case SDL_BUTTON_LEFT:
            button = BUTTON_LEFT;
            break;
        case SDL_BUTTON_MIDDLE:
            button = BUTTON_MIDDLE;
            break;
        case SDL_BUTTON_RIGHT:
            button = BUTTON_RIGHT;
            break;
        case SDL_BUTTON_X1:
            button = BUTTON_X1;
            break;
        case SDL_BUTTON_X2:
            button = BUTTON_X2;
            break;
        default:
            SDL_LogInfo(SDL_LOG_CATEGORY_APPLICATION,
                        "Unhandled button event: %d",
                        event->button);
            return;
    }

    if (m_SwapMouseButtons) {
        if (button == BUTTON_RIGHT)
            button = BUTTON_LEFT;
        else if (button == BUTTON_LEFT)
            button = BUTTON_RIGHT;
    }

    LiSendMouseButtonEvent(event->state == SDL_PRESSED ?
                               BUTTON_ACTION_PRESS :
                               BUTTON_ACTION_RELEASE,
                           button);
}

void SdlInputHandler::handleMouseMotionEvent(SDL_MouseMotionEvent* event)
{
    if (!isCaptureActive()) {
        // Not capturing
        return;
    }
    else if (event->which == SDL_TOUCH_MOUSEID) {
        // Ignore synthetic mouse events
        return;
    }

    // Batch all pending mouse motion events to save CPU time
    Sint32 x = event->x, y = event->y, xrel = event->xrel, yrel = event->yrel;
    SDL_Event nextEvent;
    while (SDL_PeepEvents(&nextEvent, 1, SDL_GETEVENT, SDL_MOUSEMOTION, SDL_MOUSEMOTION) > 0) {
        event = &nextEvent.motion;

        // Ignore synthetic mouse events
        if (event->which != SDL_TOUCH_MOUSEID) {
            x = event->x;
            y = event->y;
            xrel += event->xrel;
            yrel += event->yrel;
        }
    }

    // We should not reference the original event anymore
    event = nullptr;

    if (m_AbsoluteMouseMode) {
        int windowWidth, windowHeight;
        SDL_GetWindowSize(m_Window, &windowWidth, &windowHeight);

        SDL_Rect src, dst;
        bool mouseInVideoRegion;

        src.x = src.y = 0;
        src.w = m_StreamWidth;
        src.h = m_StreamHeight;

        dst.x = dst.y = 0;
        dst.w = windowWidth;
        dst.h = windowHeight;

        // Use the stream and window sizes to determine the video region
        StreamUtils::scaleSourceToDestinationSurface(&src, &dst);

        mouseInVideoRegion = isMouseInVideoRegion(x, y, windowWidth, windowHeight);

        // Clamp motion to the video region
        x = qMin(qMax(x - dst.x, 0), dst.w);
        y = qMin(qMax(y - dst.y, 0), dst.h);

        // Send the mouse position update if one of the following is true:
        // a) it is in the video region now
        // b) it just left the video region (to ensure the mouse is clamped to the video boundary)
        // c) a mouse button is still down from before the cursor left the video region (to allow smooth dragging)
        Uint32 buttonState = SDL_GetMouseState(nullptr, nullptr);
        if (buttonState == 0) {
            if (m_PendingMouseButtonsAllUpOnVideoRegionLeave) {
                if (m_NeedsManualCaptureOnLeave) {
                    // Stop capturing the mouse now
                    SDL_CaptureMouse(SDL_FALSE);
                }
                m_PendingMouseButtonsAllUpOnVideoRegionLeave = false;
            }
        }
        if (mouseInVideoRegion || m_MouseWasInVideoRegion || m_PendingMouseButtonsAllUpOnVideoRegionLeave) {
            LiSendMousePositionEvent((short)x, (short)y, dst.w, dst.h);
        }

        // Adjust the cursor visibility if applicable
        if (mouseInVideoRegion ^ m_MouseWasInVideoRegion) {
            SDL_ShowCursor((mouseInVideoRegion && m_MouseCursorCapturedVisibilityState == SDL_DISABLE) ? SDL_DISABLE : SDL_ENABLE);
            if (!mouseInVideoRegion && buttonState != 0) {
                // If we still have a button pressed on leave, wait for that to come up
                // before we stop sending mouse position events.
                m_PendingMouseButtonsAllUpOnVideoRegionLeave = true;
            }
        }

        m_MouseWasInVideoRegion = mouseInVideoRegion;
    }
    else {
        double dx = xrel * m_MouseSensitivityX + m_MouseRemainderX;
        double dy = yrel * m_MouseSensitivityY + m_MouseRemainderY;
        m_MouseRemainderX = dx - std::trunc(dx);
        m_MouseRemainderY = dy - std::trunc(dy);
        if (std::trunc(dx) != 0 || std::trunc(dy) != 0) {
            LiSendMouseMoveEvent((short)std::trunc(dx), (short)std::trunc(dy));
        }
    }
}

void SdlInputHandler::handleMouseWheelEvent(SDL_MouseWheelEvent* event)
{
    if (!isCaptureActive()) {
        // Not capturing
        return;
    }
    else if (event->which == SDL_TOUCH_MOUSEID) {
        // Ignore synthetic mouse events
        return;
    }

    if (m_AbsoluteMouseMode) {
        int mouseX, mouseY;
        SDL_GetMouseState(&mouseX, &mouseY);
        if (!isMouseInVideoRegion(mouseX, mouseY)) {
            // Ignore scroll events outside the video region
            return;
        }
    }

#if SDL_VERSION_ATLEAST(2, 0, 18)
    if (event->preciseY != 0.0f) {
        // Invert the scroll direction if needed
        if (m_ReverseScrollDirection) {
            event->preciseY = -event->preciseY;
        }

#ifdef Q_OS_DARWIN
        // HACK: Clamp the scroll values on macOS to prevent OS scroll acceleration
        // from generating wild scroll deltas when scrolling quickly.
        event->preciseY = SDL_clamp(event->preciseY, -1.0f, 1.0f);
#endif

        LiSendHighResScrollEvent((short)(event->preciseY * 120)); // WHEEL_DELTA
    }

    if (event->preciseX != 0.0f) {
        // Invert the scroll direction if needed
        if (m_ReverseScrollDirection) {
            event->preciseX = -event->preciseX;
        }

#ifdef Q_OS_DARWIN
        // HACK: Clamp the scroll values on macOS to prevent OS scroll acceleration
        // from generating wild scroll deltas when scrolling quickly.
        event->preciseX = SDL_clamp(event->preciseX, -1.0f, 1.0f);
#endif

        LiSendHighResHScrollEvent((short)(event->preciseX * 120)); // WHEEL_DELTA
    }
#else
    if (event->y != 0) {
        // Invert the scroll direction if needed
        if (m_ReverseScrollDirection) {
            event->y = -event->y;
        }

#ifdef Q_OS_DARWIN
        // See comment above
        event->y = SDL_clamp(event->y, -1, 1);
#endif

        LiSendScrollEvent((signed char)event->y);
    }

    if (event->x != 0) {
        // Invert the scroll direction if needed
        if (m_ReverseScrollDirection) {
            event->x = -event->x;
        }

#ifdef Q_OS_DARWIN
        // See comment above
        event->x = SDL_clamp(event->x, -1, 1);
#endif

        LiSendHScrollEvent((signed char)event->x);
    }
#endif
}

bool SdlInputHandler::isMouseInVideoRegion(int mouseX, int mouseY, int windowWidth, int windowHeight)
{
    SDL_Rect src, dst;

    if (windowWidth < 0 || windowHeight < 0) {
        SDL_GetWindowSize(m_Window, &windowWidth, &windowHeight);
    }

    src.x = src.y = 0;
    src.w = m_StreamWidth;
    src.h = m_StreamHeight;

    dst.x = dst.y = 0;
    dst.w = windowWidth;
    dst.h = windowHeight;

    // Use the stream and window sizes to determine the video region
    StreamUtils::scaleSourceToDestinationSurface(&src, &dst);

    return (mouseX >= dst.x && mouseX <= dst.x + dst.w) &&
           (mouseY >= dst.y && mouseY <= dst.y + dst.h);
}

void SdlInputHandler::updatePointerRegionLock()
{
    // Pointer region lock is irrelevant in relative mouse mode
    if (SDL_GetRelativeMouseMode()) {
        return;
    }

    // Our pointer lock behavior tracks with the fullscreen mode unless the user has
    // toggled it themselves using the keyboard shortcut. If that's the case, they
    // have full control over it and we don't touch it anymore.
    if (!m_PointerRegionLockToggledByUser) {
        // Lock the pointer in true full-screen mode or in any fullscreen mode when only a single monitor is present
        Uint32 fullscreenFlags = SDL_GetWindowFlags(m_Window) & SDL_WINDOW_FULLSCREEN_DESKTOP;
        m_PointerRegionLockActive = (fullscreenFlags == SDL_WINDOW_FULLSCREEN) ||
                                    (fullscreenFlags != 0 && SDL_GetNumVideoDisplays() == 1);
    }

    // If region lock is enabled, grab the cursor so it can't accidentally leave our window.
    if (isCaptureActive() && m_PointerRegionLockActive) {
#if SDL_VERSION_ATLEAST(2, 0, 18)
        SDL_Rect src, dst;

        src.x = src.y = 0;
        src.w = m_StreamWidth;
        src.h = m_StreamHeight;

        dst.x = dst.y = 0;
        SDL_GetWindowSize(m_Window, &dst.w, &dst.h);

        // Use the stream and window sizes to determine the video region
        StreamUtils::scaleSourceToDestinationSurface(&src, &dst);

        // SDL 2.0.18 lets us lock the cursor to a specific region
        SDL_SetWindowMouseRect(m_Window, &dst);
#elif SDL_VERSION_ATLEAST(2, 0, 15)
        // SDL 2.0.15 only lets us lock the cursor to the whole window
        SDL_SetWindowMouseGrab(m_Window, SDL_TRUE);
#else
        SDL_SetWindowGrab(m_Window, SDL_TRUE);
#endif
    }
    else {
        // Allow the cursor to leave the bounds of our video region or window
#if SDL_VERSION_ATLEAST(2, 0, 18)
        SDL_SetWindowMouseRect(m_Window, nullptr);
#elif SDL_VERSION_ATLEAST(2, 0, 15)
        SDL_SetWindowMouseGrab(m_Window, SDL_FALSE);
#else
        SDL_SetWindowGrab(m_Window, SDL_FALSE);
#endif
    }
}

void SdlInputHandler::setHostCursor(const HOST_CURSOR& cursor, const QByteArray& pixels)
{
    m_HostCursor = cursor;
    m_HostCursor.bgra = nullptr;

    if (cursor.visible && cursor.imageId != 0) {
        auto cached = std::find_if(m_HostCursorImages.begin(), m_HostCursorImages.end(),
                                   [&](const HostCursorImage& image) { return image.id == cursor.imageId; });
        HostCursorImage image = { cursor.imageId, cursor.hotX, cursor.hotY, cursor.width, cursor.height, cursor.scaleShift, pixels };
        if (pixels.isEmpty()) {
            if (cached == m_HostCursorImages.end()) {
                SDL_LogWarn(SDL_LOG_CATEGORY_APPLICATION,
                            "Host cursor image %llx isn't cached",
                            (unsigned long long)cursor.imageId);
            }
            else {
                image = *cached;
            }
        }
        if (cached != m_HostCursorImages.end()) {
            m_HostCursorImages.erase(cached);
        }
        m_HostCursorImages.prepend(image);
        if (m_HostCursorImages.size() > ML_HOST_CURSOR_CACHE_SIZE) {
            m_HostCursorImages.removeLast();
        }
    }

    updateHostCursor();

    if (m_AbsoluteMouseMode == cursor.visible) {
        return;
    }

    bool captured = isCaptureActive();
    if (captured) {
        setCaptureActive(false);
    }

    m_AbsoluteMouseMode = cursor.visible;

    if (captured) {
        if (cursor.visible && cursor.displayWidth != 0 && cursor.displayHeight != 0) {
            // Put our cursor where the host's is, so capture doesn't move the host's to ours
            SDL_Rect src = { 0, 0, m_StreamWidth, m_StreamHeight };
            SDL_Rect dst = { 0, 0, 0, 0 };
            SDL_GetWindowSize(m_Window, &dst.w, &dst.h);
            StreamUtils::scaleSourceToDestinationSurface(&src, &dst);
            SDL_WarpMouseInWindow(m_Window,
                                  dst.x + cursor.x * dst.w / cursor.displayWidth,
                                  dst.y + cursor.y * dst.h / cursor.displayHeight);
        }
        setCaptureActive(true);
    }
}

void SdlInputHandler::updateHostCursor()
{
    if (!m_HostCursor.visible) {
        return;
    }

    static const QHash<int, SDL_SystemCursor> k_SystemCursors = {
        { 32512, SDL_SYSTEM_CURSOR_ARROW },
        { 32513, SDL_SYSTEM_CURSOR_IBEAM },
        { 32514, SDL_SYSTEM_CURSOR_WAIT },
        { 32515, SDL_SYSTEM_CURSOR_CROSSHAIR },
        { 32642, SDL_SYSTEM_CURSOR_SIZENWSE },
        { 32643, SDL_SYSTEM_CURSOR_SIZENESW },
        { 32644, SDL_SYSTEM_CURSOR_SIZEWE },
        { 32645, SDL_SYSTEM_CURSOR_SIZENS },
        { 32646, SDL_SYSTEM_CURSOR_SIZEALL },
        { 32648, SDL_SYSTEM_CURSOR_NO },
        { 32649, SDL_SYSTEM_CURSOR_HAND },
        { 32650, SDL_SYSTEM_CURSOR_WAITARROW },
    };

    SDL_Cursor* cursor = nullptr;
    if (k_SystemCursors.contains(m_HostCursor.systemCursor)) {
        cursor = SDL_CreateSystemCursor(k_SystemCursors.value(m_HostCursor.systemCursor));
    }
    if (cursor == nullptr && m_HostCursor.imageId != 0 && !m_HostCursorImages.isEmpty() &&
            m_HostCursorImages.first().id == m_HostCursor.imageId && !m_HostCursorImages.first().bgra.isEmpty()) {
        const HostCursorImage& image = m_HostCursorImages.first();

        int windowWidth, windowHeight, pixelWidth, pixelHeight;
        SDL_GetWindowSize(m_Window, &windowWidth, &windowHeight);
        SDL_GetWindowSizeInPixels(m_Window, &pixelWidth, &pixelHeight);
        double pixelsPerPoint = windowWidth > 0 ? (double)pixelWidth / windowWidth : 1.0;

        double pointsPerHostPixel = 1.0 / pixelsPerPoint;
        if (m_HostCursor.displayWidth != 0) {
            SDL_Rect src = { 0, 0, m_StreamWidth, m_StreamHeight };
            SDL_Rect dst = { 0, 0, windowWidth, windowHeight };
            StreamUtils::scaleSourceToDestinationSurface(&src, &dst);
            pointsPerHostPixel = (double)dst.w / m_HostCursor.displayWidth;
        }

        int hostWidth = image.width << image.scaleShift;
        int hostHeight = image.height << image.scaleShift;
        int width = qMax(1, (int)qRound(hostWidth * pointsPerHostPixel));
        int height = qMax(1, (int)qRound(hostHeight * pointsPerHostPixel));
        int hiResWidth = qMax(1, (int)qRound(hostWidth * pointsPerHostPixel * pixelsPerPoint));
        int hiResHeight = qMax(1, (int)qRound(hostHeight * pointsPerHostPixel * pixelsPerPoint));
        int hotX = qMin((int)qRound(image.hotX * pointsPerHostPixel), width - 1);
        int hotY = qMin((int)qRound(image.hotY * pointsPerHostPixel), height - 1);

        SDL_Surface* source = SDL_CreateRGBSurfaceWithFormatFrom((void*)image.bgra.constData(),
                                                                 image.width, image.height, 32,
                                                                 image.width * 4, SDL_PIXELFORMAT_ARGB8888);
        auto resize = [source](int w, int h) -> SDL_Surface* {
            if (source == nullptr || (w == source->w && h == source->h)) {
                return source;
            }
            SDL_Surface* resized = SDL_CreateRGBSurfaceWithFormat(0, w, h, 32, SDL_PIXELFORMAT_ARGB8888);
            if (resized != nullptr && SDL_SoftStretchLinear(source, nullptr, resized, nullptr) != 0) {
                SDL_FreeSurface(resized);
                resized = nullptr;
            }
            return resized;
        };
        SDL_Surface* base = resize(width, height);
        SDL_Surface* hiRes = hiResWidth != width ? resize(hiResWidth, hiResHeight) : nullptr;

#ifdef Q_OS_DARWIN
        const Sdl3CursorApi* sdl3 = getSdl3CursorApi();
        if (sdl3 != nullptr && base != nullptr && hiRes != nullptr) {
            void* base3 = sdl3->createSurfaceFrom(base->w, base->h, SDL_PIXELFORMAT_ARGB8888, base->pixels, base->pitch);
            void* hiRes3 = sdl3->createSurfaceFrom(hiRes->w, hiRes->h, SDL_PIXELFORMAT_ARGB8888, hiRes->pixels, hiRes->pitch);
            if (base3 != nullptr && hiRes3 != nullptr && sdl3->addSurfaceAlternateImage(base3, hiRes3)) {
                cursor = sdl3->createColorCursor(base3, hotX, hotY);
            }
            sdl3->destroySurface(hiRes3);
            sdl3->destroySurface(base3);
        }
#endif
        bool hiResCursor = cursor != nullptr;
        if (cursor == nullptr && base != nullptr) {
            cursor = SDL_CreateColorCursor(base, hotX, hotY);
        }
        SDL_LogInfo(SDL_LOG_CATEGORY_APPLICATION,
                    "Host cursor: %dx%d image (%dx%d host pixels), %dx%d points, %dx%d pixels, %s",
                    image.width, image.height, hostWidth, hostHeight, width, height, hiResWidth, hiResHeight,
                    hiResCursor ? "HiDPI" : "1x");

        if (hiRes != source) {
            SDL_FreeSurface(hiRes);
        }
        if (base != source) {
            SDL_FreeSurface(base);
        }
        SDL_FreeSurface(source);
        if (cursor == nullptr) {
            SDL_LogWarn(SDL_LOG_CATEGORY_APPLICATION,
                        "Failed to create the host cursor: %s",
                        SDL_GetError());
        }
    }

    SDL_SetCursor(cursor != nullptr ? cursor : SDL_GetDefaultCursor());
    if (m_HostSdlCursor != nullptr) {
        SDL_FreeCursor(m_HostSdlCursor);
    }
    m_HostSdlCursor = cursor;
}

//! Platform-specific explanations and fixes for connectivity failures.

use super::http::HttpError;

/// Name of the setting the user has to flip when the OS blocks LAN access.
#[cfg(target_os = "macos")]
pub const LOCAL_NETWORK_HINT: &str = "macOS is blocking local network access for this app. Allow Sloplight under System Settings \u{2192} Privacy & Security \u{2192} Local Network, then relaunch. When started from a terminal, the terminal app needs the permission instead.";

/// A deep link that opens the relevant privacy pane in System Settings.
#[cfg(target_os = "macos")]
pub const LOCAL_NETWORK_SETTINGS_URL: &str = "x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension?Privacy_LocalNetwork";

pub fn resolution_for(e: &HttpError) -> Option<&'static str> {
    #[cfg(target_os = "macos")]
    {
        match e {
            HttpError::HostUnreachable => Some(LOCAL_NETWORK_HINT),
            _ => None,
        }
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = e;
        None
    }
}

/// One-line reminder for the discovery empty state on gated platforms.
pub fn discovery_hint() -> Option<&'static str> {
    #[cfg(target_os = "macos")]
    {
        Some("Nothing showing up? Sloplight needs the Local Network permission in System Settings \u{2192} Privacy & Security.")
    }
    #[cfg(not(target_os = "macos"))]
    {
        None
    }
}

/// Lets the app with this pid take the activation from us (macOS only lets
/// an app activate itself when the active app hands it over): the stream
/// helper, to bring a window the user just launched to the front.
pub fn yield_activation_to(pid: u32) {
    #[cfg(target_os = "macos")]
    {
        use objc2_app_kit::{NSApplication, NSRunningApplication};
        let Some(mtm) = objc2::MainThreadMarker::new() else { return };
        let Some(target) = NSRunningApplication::runningApplicationWithProcessIdentifier(pid as i32) else { return };
        NSApplication::sharedApplication(mtm).yieldActivationToApplication(&target);
    }
    #[cfg(not(target_os = "macos"))]
    let _ = pid;
}

/// Brings the app with this pid to the front (the stream helper's window)
pub fn activate_app(pid: u32) {
    #[cfg(target_os = "macos")]
    {
        use objc2_app_kit::{NSApplicationActivationOptions, NSRunningApplication};
        yield_activation_to(pid);
        if let Some(target) = NSRunningApplication::runningApplicationWithProcessIdentifier(pid as i32) {
            target.activateWithOptions(NSApplicationActivationOptions::empty());
        }
    }
    #[cfg(not(target_os = "macos"))]
    let _ = pid;
}

/// Where the pointer is, in global points from the top-left of the main
/// screen (GPUI's display coordinates)
pub fn mouse_location() -> Option<(f32, f32)> {
    #[cfg(target_os = "macos")]
    {
        use objc2_app_kit::{NSEvent, NSScreen};
        let mtm = objc2::MainThreadMarker::new()?;
        let p = NSEvent::mouseLocation();
        let main = NSScreen::screens(mtm).firstObject()?;
        Some((p.x as f32, (main.frame().size.height - p.y) as f32))
    }
    #[cfg(not(target_os = "macos"))]
    None
}

/// No window-server shadow for this window (a transparent panel draws its own
/// around what it shows, not around its whole frame)
pub fn hide_window_shadow(window: &gpui::Window) {
    #[cfg(target_os = "macos")]
    {
        use raw_window_handle::{HasWindowHandle, RawWindowHandle};
        let Ok(handle) = HasWindowHandle::window_handle(window) else { return };
        let RawWindowHandle::AppKit(h) = handle.as_raw() else { return };
        // SAFETY: the view is the window's own content view, alive while the window is
        unsafe {
            let view = h.ns_view.as_ptr().cast::<objc2::runtime::AnyObject>();
            let ns_window: *mut objc2::runtime::AnyObject = objc2::msg_send![view, window];
            if !ns_window.is_null() {
                let _: () = objc2::msg_send![ns_window, setHasShadow: false];
            }
        }
    }
    #[cfg(not(target_os = "macos"))]
    let _ = window;
}

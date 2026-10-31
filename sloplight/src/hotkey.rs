//! System-wide shortcuts (macOS: Carbon hot keys, which need no
//! Accessibility permission). Pressing one anywhere sends its id down the
//! channel given to `listen`.

use futures::channel::mpsc::UnboundedSender;

/// The shortcut as Sloplight shows it ("alt-space" → "⌥Space")
pub fn display(binding: &str) -> String {
    let Ok(k) = gpui::Keystroke::parse_with_os(binding, gpui::OperatingSystem::Mac) else { return binding.to_string() };
    let mut s = String::new();
    if k.modifiers.control {
        s.push('⌃');
    }
    if k.modifiers.alt {
        s.push('⌥');
    }
    if k.modifiers.shift {
        s.push('⇧');
    }
    if k.modifiers.platform {
        s.push('⌘');
    }
    let key = match k.key.as_str() {
        "space" => "Space".to_string(),
        "enter" => "Return".to_string(),
        "tab" => "Tab".to_string(),
        "backspace" => "Delete".to_string(),
        "escape" => "Esc".to_string(),
        other if other.starts_with('f') && other.len() > 1 && other[1..].chars().all(|c| c.is_ascii_digit()) => other.to_uppercase(),
        other => other.to_uppercase(),
    };
    s.push_str(&key);
    s
}

/// A keystroke usable as a system-wide shortcut: a key the hot key API
/// knows, with Command, Control or Option (or a function key alone)
pub fn binding_of(k: &gpui::Keystroke) -> Option<String> {
    keycode(&k.key)?;
    let function = k.key.starts_with('f') && k.key.len() > 1 && k.key[1..].chars().all(|c| c.is_ascii_digit());
    if !(k.modifiers.platform || k.modifiers.control || k.modifiers.alt || function) {
        return None;
    }
    let mut s = String::new();
    if k.modifiers.control {
        s.push_str("ctrl-");
    }
    if k.modifiers.alt {
        s.push_str("alt-");
    }
    if k.modifiers.shift {
        s.push_str("shift-");
    }
    if k.modifiers.platform {
        s.push_str("cmd-");
    }
    s.push_str(&k.key);
    Some(s)
}

/// The virtual key code of a key (ANSI layout positions)
fn keycode(key: &str) -> Option<u32> {
    Some(match key {
        "a" => 0, "s" => 1, "d" => 2, "f" => 3, "h" => 4, "g" => 5, "z" => 6, "x" => 7, "c" => 8, "v" => 9,
        "b" => 11, "q" => 12, "w" => 13, "e" => 14, "r" => 15, "y" => 16, "t" => 17, "1" => 18, "2" => 19,
        "3" => 20, "4" => 21, "6" => 22, "5" => 23, "=" => 24, "9" => 25, "7" => 26, "-" => 27, "8" => 28,
        "0" => 29, "]" => 30, "o" => 31, "u" => 32, "[" => 33, "i" => 34, "p" => 35, "enter" => 36, "l" => 37,
        "j" => 38, "'" => 39, "k" => 40, ";" => 41, "\\" => 42, "," => 43, "/" => 44, "n" => 45, "m" => 46,
        "." => 47, "tab" => 48, "space" => 49, "`" => 50, "backspace" => 51,
        "f1" => 122, "f2" => 120, "f3" => 99, "f4" => 118, "f5" => 96, "f6" => 97, "f7" => 98, "f8" => 100,
        "f9" => 101, "f10" => 109, "f11" => 103, "f12" => 111, "f13" => 105, "f14" => 107, "f15" => 113,
        "f16" => 106, "f17" => 64, "f18" => 79, "f19" => 80, "f20" => 90,
        _ => return None,
    })
}

#[cfg(target_os = "macos")]
mod carbon {
    use std::ffi::c_void;
    use std::sync::{Mutex, OnceLock};

    use futures::channel::mpsc::UnboundedSender;

    #[repr(C)]
    pub struct EventTypeSpec {
        pub event_class: u32,
        pub event_kind: u32,
    }

    #[repr(C)]
    pub struct EventHotKeyId {
        pub signature: u32,
        pub id: u32,
    }

    pub type Handler = extern "C" fn(next: *mut c_void, event: *mut c_void, user: *mut c_void) -> i32;

    #[link(name = "Carbon", kind = "framework")]
    unsafe extern "C" {
        pub fn GetApplicationEventTarget() -> *mut c_void;
        pub fn InstallEventHandler(target: *mut c_void, handler: Handler, count: u32, types: *const EventTypeSpec, user: *mut c_void, out: *mut *mut c_void) -> i32;
        pub fn RegisterEventHotKey(code: u32, modifiers: u32, id: EventHotKeyId, target: *mut c_void, options: u32, out: *mut *mut c_void) -> i32;
        pub fn GetEventParameter(event: *mut c_void, name: u32, desired: u32, actual: *mut u32, size: usize, actual_size: *mut usize, data: *mut c_void) -> i32;
        pub fn UnregisterEventHotKey(hot_key: *mut c_void) -> i32;
    }

    pub const CLASS_KEYBOARD: u32 = u32::from_be_bytes(*b"keyb");
    pub const PARAM_DIRECT_OBJECT: u32 = u32::from_be_bytes(*b"----");
    pub const TYPE_HOT_KEY_ID: u32 = u32::from_be_bytes(*b"hkid");
    pub const HOT_KEY_PRESSED: u32 = 5;
    pub const CMD: u32 = 0x0100;
    pub const SHIFT: u32 = 0x0200;
    pub const OPTION: u32 = 0x0800;
    pub const CONTROL: u32 = 0x1000;

    pub static SENDER: OnceLock<Mutex<UnboundedSender<u32>>> = OnceLock::new();

    pub extern "C" fn on_hot_key(_next: *mut c_void, event: *mut c_void, _user: *mut c_void) -> i32 {
        let mut id = EventHotKeyId { signature: 0, id: 0 };
        let status = unsafe {
            GetEventParameter(event, PARAM_DIRECT_OBJECT, TYPE_HOT_KEY_ID, std::ptr::null_mut(), std::mem::size_of::<EventHotKeyId>(), std::ptr::null_mut(), (&mut id as *mut EventHotKeyId).cast())
        };
        if status == 0
            && let Some(tx) = SENDER.get() {
                let _ = tx.lock().unwrap().unbounded_send(id.id);
            }
        0
    }
}

/// Sends a shortcut's id for every press of it. Once, at startup (main thread).
pub fn listen(tx: UnboundedSender<u32>) {
    #[cfg(target_os = "macos")]
    unsafe {
        if carbon::SENDER.set(std::sync::Mutex::new(tx)).is_err() {
            return;
        }
        let spec = carbon::EventTypeSpec { event_class: carbon::CLASS_KEYBOARD, event_kind: carbon::HOT_KEY_PRESSED };
        let status = carbon::InstallEventHandler(carbon::GetApplicationEventTarget(), carbon::on_hot_key, 1, &spec, std::ptr::null_mut(), std::ptr::null_mut());
        if status != 0 {
            log::error!("hot key handler: OSStatus {status}");
        }
    }
    #[cfg(not(target_os = "macos"))]
    let _ = tx;
}

/// A registered shortcut; unregistered when dropped
pub struct HotKey {
    #[cfg(target_os = "macos")]
    handle: *mut std::ffi::c_void,
}

impl HotKey {
    /// Registers a binding ("alt-space") system-wide under this id (main thread)
    pub fn register(binding: &str, id: u32) -> Result<HotKey, String> {
        let k = gpui::Keystroke::parse_with_os(binding, gpui::OperatingSystem::Mac).map_err(|e| e.to_string())?;
        let code = keycode(&k.key).ok_or_else(|| format!("{} can't be a system-wide shortcut", k.key))?;
        #[cfg(target_os = "macos")]
        unsafe {
            let mut modifiers = 0;
            if k.modifiers.platform {
                modifiers |= carbon::CMD;
            }
            if k.modifiers.shift {
                modifiers |= carbon::SHIFT;
            }
            if k.modifiers.alt {
                modifiers |= carbon::OPTION;
            }
            if k.modifiers.control {
                modifiers |= carbon::CONTROL;
            }
            let id = carbon::EventHotKeyId { signature: u32::from_be_bytes(*b"slop"), id };
            let mut handle = std::ptr::null_mut();
            let status = carbon::RegisterEventHotKey(code, modifiers, id, carbon::GetApplicationEventTarget(), 0, &mut handle);
            if status != 0 {
                // (-9878: another app has this shortcut)
                return Err(if status == -9878 { "another app uses this shortcut".into() } else { format!("OSStatus {status}") });
            }
            Ok(HotKey { handle })
        }
        #[cfg(not(target_os = "macos"))]
        {
            let _ = (code, id);
            Err("system-wide shortcuts are macOS only".into())
        }
    }
}

impl Drop for HotKey {
    fn drop(&mut self) {
        #[cfg(target_os = "macos")]
        unsafe {
            carbon::UnregisterEventHotKey(self.handle);
        }
    }
}

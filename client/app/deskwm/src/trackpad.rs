//! The trackpad's two-finger gestures as the host's own touchpad's, for desk
//! windows and the stream window alike.

use std::cell::Cell;

use objc2::msg_send;
use objc2_app_kit::{NSEvent, NSEventPhase, NSTouchPhase, NSView};
use objc2_foundation::{NSSize, NSUserDefaults};

use crate::ffi;

thread_local! {
    static TRACKPAD: Cell<Trackpad> = const { Cell::new(Trackpad { down: false, gesture: false }) };
}

#[derive(Clone, Copy)]
struct Trackpad {
    down: bool,     // two fingers on it, going to the host
    gesture: bool,  // the last scroll gesture went as fingers (its momentum too)
}

/// Two fingers on the trackpad go to the host as they are, on a touchpad of
/// the same size: Windows pans, zooms and coasts with its own touchpad
/// handling, exactly as with a touchpad of its own. One finger is the
/// pointer; three and more are macOS's gestures. A gesture starts only while
/// `active` and `may_start()` (`on_start` runs then); it ends when `active`
/// goes false.
pub fn touches(e: &NSEvent, view: Option<&NSView>, active: bool, may_start: impl FnOnce() -> bool, on_start: impl FnOnce()) {
    let set = e.touchesMatchingPhase_inView(NSTouchPhase::Touching, view);
    let touches: Vec<_> = set.allObjects().iter().collect();
    let mut t = TRACKPAD.with(|t| t.get());
    if active && touches.len() == 2 && (t.down || may_start()) {
        // As the fingers are on the touchpad, y down; with macOS's natural
        // scrolling off, as if turned around (Windows' default is natural)
        let natural = natural_scrolling();
        let mut contacts = [ffi::TrackpadContact { id: 0, x: 0.0, y: 0.0 }; 2];
        let mut size = NSSize::new(0.0, 0.0);
        for (c, touch) in contacts.iter_mut().zip(&touches) {
            let p = touch.normalizedPosition();
            let (x, y) = if natural { (p.x, 1.0 - p.y) } else { (1.0 - p.x, p.y) };
            // The same finger has an equal identity from event to event
            let identity = touch.identity();
            let id: usize = unsafe { msg_send![&*identity, hash] };
            *c = ffi::TrackpadContact { id: id as u32, x: x as f32, y: y as f32 };
            size = touch.deviceSize();
        }
        // (device size in points: 72 to the inch)
        let mm = |pt: f64| (pt * 25.4 / 72.0) as f32;
        if !t.down {
            on_start();
        }
        ffi::trackpad(&contacts, mm(size.width), mm(size.height), e.timestamp());
        t = Trackpad { down: true, gesture: true };
    } else if t.down {
        ffi::trackpad(&[], 0.0, 0.0, e.timestamp());
        t.down = false;
    }
    TRACKPAD.with(|c| c.set(t));
}

/// Is this scroll event the trackpad's, already on the host as fingers
/// (with the host's own momentum after)?
pub fn trackpad_scroll(e: &NSEvent) -> bool {
    if !ffi::trackpad_supported() || !e.hasPreciseScrollingDeltas() {
        return false;
    }
    let mut t = TRACKPAD.with(|t| t.get());
    let momentum = !e.momentumPhase().is_empty();
    if !momentum && e.phase().contains(NSEventPhase::Began) && !t.down {
        // A scroll gesture without fingers coming here (a Magic Mouse)
        t.gesture = false;
        TRACKPAD.with(|c| c.set(t));
    }
    t.down || (momentum && t.gesture)
}

fn natural_scrolling() -> bool {
    let defaults = NSUserDefaults::standardUserDefaults();
    let key = objc2_foundation::ns_string!("com.apple.swipescrolldirection");
    defaults.objectForKey(key).is_none() || defaults.boolForKey(key)
}

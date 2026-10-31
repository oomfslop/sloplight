//! The system-wide shortcuts' palettes: the app launcher or the window picker
//! as a panel of its own over every app (like Spotlight), on the screen with
//! the pointer. Clicking elsewhere closes it.

use gpui::{AnyWindowHandle, AppContext, Context, InteractiveElement, IntoElement, ParentElement, Styled, Window, div, px};

use crate::app::Root;
use crate::backend::Host;
use crate::theme::{Theme, type_scale};
use crate::ui::ToastKind;

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum PanelKind {
    Launcher,
    Windows,
}

impl PanelKind {
    pub const ALL: [PanelKind; 2] = [PanelKind::Launcher, PanelKind::Windows];

    /// Its system-wide shortcut's id (and slot in `Root::hotkeys`)
    pub fn index(self) -> usize {
        self as usize
    }

    pub fn from_index(i: u32) -> Option<PanelKind> {
        PanelKind::ALL.get(i as usize).copied()
    }

    /// Its shortcut in the settings ("alt-space"; empty: none)
    pub fn binding(self, s: &crate::settings::Settings) -> &String {
        match self {
            PanelKind::Launcher => &s.launcher_hotkey,
            PanelKind::Windows => &s.windows_hotkey,
        }
    }

    pub fn binding_mut(self, s: &mut crate::settings::Settings) -> &mut String {
        match self {
            PanelKind::Launcher => &mut s.launcher_hotkey,
            PanelKind::Windows => &mut s.windows_hotkey,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            PanelKind::Launcher => "launcher",
            PanelKind::Windows => "window picker",
        }
    }

    /// The panel of this kind, when one is open
    fn handle(self, root: &Root) -> Option<AnyWindowHandle> {
        match self {
            PanelKind::Launcher => root.launcher.as_ref().and_then(|l| l.panel),
            PanelKind::Windows => root.window_picker.as_ref().and_then(|p| p.panel),
        }
    }

    fn close(self, root: &mut Root, cx: &mut Context<Root>) {
        match self {
            PanelKind::Launcher => crate::views::launcher::close(root, cx),
            PanelKind::Windows => crate::views::window_picker::close(root, cx),
        }
    }
}

/// The host a panel works with: the one kept ready, else the selected one
fn host(root: &Root) -> Option<Host> {
    let usable = |h: &&Host| h.is_online() && h.is_paired() && Root::host_supports_windows(h);
    root.standby_host().filter(|h| usable(&h)).or_else(|| root.selected().filter(usable).cloned())
}

/// A shortcut was pressed: its panel opens, or closes when it is open
pub fn toggle(root: &mut Root, kind: PanelKind, cx: &mut Context<Root>) {
    if kind.handle(root).is_some() {
        kind.close(root, cx);
        return;
    }
    let Some(host) = host(root) else {
        cx.activate(true);
        root.toast(ToastKind::Warning, "No host online that streams windows", cx);
        return;
    };
    root.resume_polling();
    let entity = cx.entity();
    // (not while Root is being updated: opening the panel updates it)
    cx.defer(move |cx| {
        let row = match kind {
            PanelKind::Launcher => crate::views::launcher::ROW,
            PanelKind::Windows => crate::views::window_picker::ROW,
        };
        // (room around the card for its shadow)
        let height = HEADER + SHOWN as f32 * row + 8. + FOOTER + 2. + 2. * MARGIN;
        let width = WIDTH + 2. * MARGIN;
        let mouse = crate::backend::platform::mouse_location();
        let display = cx
            .displays()
            .into_iter()
            .find(|d| mouse.is_some_and(|(x, y)| d.bounds().contains(&gpui::point(px(x), px(y)))))
            .or_else(|| cx.primary_display());
        let bounds = display.as_ref().map(|d| d.bounds()).unwrap_or_default();
        // (relative to the display)
        let origin = gpui::point(px(((f32::from(bounds.size.width) - width) / 2.).max(0.)), px(f32::from(bounds.size.height) * 0.2));
        let options = gpui::WindowOptions {
            window_bounds: Some(gpui::WindowBounds::Windowed(gpui::Bounds::new(origin, gpui::size(px(width), px(height))))),
            titlebar: None,
            kind: gpui::WindowKind::PopUp,
            is_movable: false,
            is_resizable: false,
            is_minimizable: false,
            display_id: display.map(|d| d.id()),
            window_background: gpui::WindowBackgroundAppearance::Transparent,
            ..Default::default()
        };
        let uuid = host.uuid.clone();
        let opened = cx.open_window(options, |window, cx| {
            let handle = window.window_handle();
            // The card draws its own shadow: one around the whole (mostly
            // transparent) window would outline empty space
            crate::backend::platform::hide_window_shadow(window);
            let panel = cx.new(|cx| {
                cx.observe(&entity, |_, _, cx| cx.notify()).detach();
                Panel { root: entity.clone(), kind }
            });
            entity.update(cx, |root, cx| match kind {
                PanelKind::Launcher => crate::views::launcher::open_panel(root, uuid, handle, window, cx),
                PanelKind::Windows => crate::views::window_picker::open_panel(root, uuid, handle, window, cx),
            });
            // Clicked elsewhere: gone
            panel.update(cx, |_, cx| {
                cx.observe_window_activation(window, move |this, window, cx| {
                    if !window.is_window_active() {
                        let kind = this.kind;
                        this.root.update(cx, |root, cx| {
                            if kind.handle(root) == Some(handle) {
                                kind.close(root, cx);
                            }
                        });
                    }
                })
                .detach();
            });
            panel
        });
        if let Err(e) = opened {
            log::error!("panel: {e}");
        }
    });
}

/// Removes a panel's window (later: not from inside its own update)
pub fn remove(handle: AnyWindowHandle, cx: &mut Context<Root>) {
    cx.defer(move |cx| {
        let _ = handle.update(cx, |_, window, _| window.remove_window());
    });
}

/// Rows a panel has room for
pub const SHOWN: usize = 12;
pub const WIDTH: f32 = 600.;
/// Transparent room around the card for its shadow
const MARGIN: f32 = 24.;
const HEADER: f32 = 48.;
const FOOTER: f32 = 28.;

/// A panel's window: the palette's card and nothing else
pub struct Panel {
    root: gpui::Entity<Root>,
    kind: PanelKind,
}

impl gpui::Render for Panel {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.global::<Theme>().clone();
        let kind = self.kind;
        let card = self.root.update(cx, |root, cx| {
            kind.handle(root)?;
            match kind {
                PanelKind::Launcher => crate::views::launcher::render(root, &theme, window, cx),
                PanelKind::Windows => crate::views::window_picker::render(root, &theme, window, cx),
            }
        });
        // (text styled like the main window's, which the card relies on)
        div()
            .size_full()
            .p(px(MARGIN))
            .font(crate::theme::font(&theme.ui_font, gpui::FontWeight::NORMAL))
            .text_size(type_scale::BASE)
            .line_height(type_scale::LH_BASE)
            .text_color(theme.text)
            // (the card stops its own clicks: this is the empty space around it)
            .on_mouse_down(
                gpui::MouseButton::Left,
                cx.listener(move |this, _, _, cx| {
                    this.root.update(cx, |root, cx| {
                        if kind.handle(root).is_some() {
                            kind.close(root, cx);
                        }
                    });
                }),
            )
            .children(card)
    }
}

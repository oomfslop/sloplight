//! Window picker (`Shift+Cmd+O`, or the header's Windows button): search the
//! host's open windows and stream one as a Mac window, or bring a streamed
//! one to the front. Looks like the app launcher; its system-wide shortcut
//! shows it as a panel of its own over every app.

use gpui::{AnyElement, Context, IntoElement, KeyDownEvent, ParentElement, Styled, Window, div, prelude::*, px};

use crate::app::{Root, ViewMode, fuzzy_score};
use crate::assets::icon;
use crate::backend::http::HostWindow;
use crate::theme::{Theme, type_scale};
use crate::ui::*;

pub struct WindowPickerState {
    pub host_uuid: String,
    pub input: gpui::Entity<TextInput>,
    pub query: String,
    pub selected: usize,
    /// Rows that fit in the window at the last render
    pub visible: usize,
    /// Shown in a panel of its own (the system-wide shortcut), not over the main window
    pub panel: Option<gpui::AnyWindowHandle>,
}

const SHOWN: usize = crate::views::panel::SHOWN;
/// Height of a row
pub const ROW: f32 = 44.;

/// Opens the picker for a host (the selected one when `uuid` is None)
pub fn open(root: &mut Root, uuid: Option<String>, window: &mut Window, cx: &mut Context<Root>) {
    if root.view == ViewMode::Settings || root.dialog.is_some() {
        return;
    }
    open_in(root, uuid, None, window, cx);
}

/// Opens the picker in a panel window (see `panel`)
pub fn open_panel(root: &mut Root, uuid: String, panel: gpui::AnyWindowHandle, window: &mut Window, cx: &mut Context<Root>) {
    open_in(root, Some(uuid), Some(panel), window, cx);
}

fn open_in(root: &mut Root, uuid: Option<String>, panel: Option<gpui::AnyWindowHandle>, window: &mut Window, cx: &mut Context<Root>) {
    // (one palette at a time: a panel goes when this opens elsewhere)
    if root.window_picker.as_ref().is_some_and(|p| p.panel.is_some() && p.panel != panel) {
        close(root, cx);
    }
    if root.launcher.is_some() {
        crate::views::launcher::close(root, cx);
    }
    let Some(host) = uuid.and_then(|u| root.host_by_uuid(&u).cloned()).or_else(|| root.selected().cloned()) else { return };
    if !(host.is_online() && host.is_paired() && Root::host_supports_windows(&host)) {
        root.toast(ToastKind::Warning, format!("{} can't stream windows", host.name), cx);
        return;
    }
    let input = cx.new(|cx| {
        let mut t = TextInput::new(cx, "Search windows", InputKind::Text);
        t.height = 44.;
        t.text_size = type_scale::LG;
        t.bare = true;
        t
    });
    cx.subscribe(&input, |this: &mut Root, input, event: &TextInputEvent, cx| match event {
        TextInputEvent::Changed => {
            if let Some(p) = &mut this.window_picker {
                p.query = input.read(cx).text().to_string();
                p.selected = 0;
            }
            cx.notify();
        }
        TextInputEvent::Submit => pick_selected(this, cx),
        TextInputEvent::Escape => close(this, cx),
        TextInputEvent::Arrow(d) => {
            let n = items(this).len().min(this.window_picker.as_ref().map(|p| p.visible).unwrap_or(SHOWN));
            if let Some(p) = &mut this.window_picker
                && n > 0 {
                    p.selected = ((p.selected as isize + *d as isize).rem_euclid(n as isize)) as usize;
                }
            cx.notify();
        }
        _ => {}
    })
    .detach();
    let focus = input.read(cx).focus_handle.clone();
    window.focus(&focus, cx);
    if !root.windows.loading {
        root.refresh_host_windows(&host.uuid, cx);
    }
    root.window_picker = Some(WindowPickerState { host_uuid: host.uuid.clone(), input, query: String::new(), selected: 0, visible: SHOWN, panel });
    root.palette = None;
    root.popup = None;
    cx.notify();
}

pub fn close(root: &mut Root, cx: &mut Context<Root>) {
    match root.window_picker.take().and_then(|p| p.panel) {
        Some(panel) => crate::views::panel::remove(panel, cx),
        None => root.focus_main(cx),
    }
}

pub fn handle_key(root: &mut Root, event: &KeyDownEvent, _window: &mut Window, cx: &mut Context<Root>) {
    if event.keystroke.key == "escape" {
        close(root, cx);
    }
}

/// Streams the selected window, focused, or brings it to the front when it
/// is streamed already
fn pick_selected(root: &mut Root, cx: &mut Context<Root>) {
    let list = items(root);
    let Some(p) = &root.window_picker else { return };
    let Some(w) = list.into_iter().nth(p.selected) else { return };
    let uuid = p.host_uuid.clone();
    let from_panel = p.panel.is_some();
    close(root, cx);
    if from_panel {
        // Active, so the stream can be handed the activation (macOS lets
        // only the active app give it away)
        cx.activate(true);
    }
    if root.window_streaming(&w.hwnd) {
        root.focus_streamed_window(&w.hwnd);
    } else {
        root.stream_window(&uuid, w, true, cx);
    }
    cx.notify();
}

/// The host's windows in its stacking order; with a query, the matches by
/// title or program, best first
fn items(root: &Root) -> Vec<HostWindow> {
    let Some(p) = &root.window_picker else { return Vec::new() };
    if root.windows.host_uuid != p.host_uuid {
        return Vec::new();
    }
    let q = p.query.trim();
    let list = &root.windows.windows;
    if q.is_empty() {
        return list.clone();
    }
    let mut scored: Vec<(i32, HostWindow)> = list
        .iter()
        .filter_map(|w| {
            let s = fuzzy_score(&w.title, q).map(|s| s + 10).max(fuzzy_score(&w.process, q))?;
            Some((s, w.clone()))
        })
        .collect();
    scored.sort_by(|a, b| b.0.cmp(&a.0));
    scored.into_iter().map(|(_, w)| w).collect()
}

pub fn render(root: &mut Root, theme: &Theme, window: &mut Window, cx: &mut Context<Root>) -> Option<AnyElement> {
    let p = root.window_picker.as_ref()?;
    // Windows come and go on the host: keep the list fresh while open
    let uuid = p.host_uuid.clone();
    let stale = root.windows.host_uuid != uuid || root.windows.fetched_at.map(|t| t.elapsed().as_secs() >= 2).unwrap_or(true);
    if stale && !root.windows.loading {
        root.refresh_host_windows(&uuid, cx);
    }
    // As many rows as fit under the search field in this window (the panel
    // is made to fit them all)
    let panel = root.window_picker.as_ref().is_some_and(|p| p.panel.is_some());
    let height = f32::from(window.bounds().size.height);
    let top = if panel { 0. } else { (height * 0.12).min(110.) };
    let fit = if panel { SHOWN } else { ((height - top - 44. - 28. - 8. - 24.) / ROW).floor().max(3.) as usize };
    let mut list = items(root);
    list.truncate(fit);
    if let Some(p) = &mut root.window_picker {
        p.visible = fit;
    }
    let p = root.window_picker.as_ref()?;
    let host_name = root.host_by_uuid(&p.host_uuid).map(|h| h.name.clone()).unwrap_or_default();
    let selected = p.selected.min(list.len().saturating_sub(1));
    let input = p.input.clone();
    let state = &root.windows;
    let have_list = state.host_uuid == p.host_uuid && (state.fetched_at.is_some() || !state.windows.is_empty());
    let mut rows = div().flex().flex_col().p(px(4.));
    if !have_list && state.error.is_none() {
        rows = rows.child(
            div()
                .h(px(36.))
                .px(px(8.))
                .flex()
                .items_center()
                .gap(px(8.))
                .child(icon_el(icon::LOADER, 14., theme.text_muted))
                .child(text_sm(theme, format!("Asking {host_name} for its windows…"))),
        );
    } else if let Some(err) = state.error.as_ref().filter(|_| list.is_empty()) {
        rows = rows.child(div().h(px(36.)).px(px(8.)).flex().items_center().text_size(type_scale::SM).text_color(theme.warning).child(format!("Couldn't list windows: {err}")));
    } else if list.is_empty() {
        let empty = if p.query.trim().is_empty() { "No windows open on the host" } else { "No matching windows" };
        rows = rows.child(div().h(px(36.)).px(px(8.)).flex().items_center().child(text_sm(theme, empty)));
    }
    let mut selected_streaming = false;
    for (i, w) in list.into_iter().enumerate() {
        let is_sel = i == selected;
        let streaming = root.window_streaming(&w.hwnd);
        if is_sel {
            selected_streaming = streaming;
        }
        let hover_bg = theme.element_hover;
        let glyph: AnyElement = match root.windows.icons.get(&w.icon) {
            Some(path) => gpui::img(path.clone()).size(px(22.)).flex_none().into_any_element(),
            None => div().size(px(22.)).flex().items_center().justify_center().flex_none().child(icon_el(icon::APP_WINDOW, 16., theme.text_muted)).into_any_element(),
        };
        let detail = format!("{}{}", w.process, if w.state == "minimized" { " · minimized" } else { "" });
        let hwnd = w.hwnd.clone();
        rows = rows.child(
            div()
                .id(gpui::ElementId::NamedInteger("window-row".into(), i as u64))
                .flex()
                .items_center()
                .gap(px(10.))
                .h(px(ROW))
                .px(px(8.))
                .rounded(px(5.))
                .when(is_sel, |d| d.bg(theme.selection))
                .when(!is_sel, |d| d.hover(move |s| s.bg(hover_bg)))
                .cursor_pointer()
                .child(glyph)
                .child(
                    div()
                        .flex()
                        .flex_col()
                        .flex_1()
                        .min_w(px(0.))
                        .child(div().whitespace_nowrap().overflow_hidden().text_ellipsis().child(w.title.clone()))
                        .child(div().whitespace_nowrap().overflow_hidden().text_ellipsis().text_size(type_scale::XS).text_color(theme.text_muted).child(detail)),
                )
                .when(streaming, |d| {
                    d.child(div().text_size(type_scale::XS).text_color(theme.accent).child("streaming")).child(
                        icon_button(gpui::ElementId::NamedInteger("window-stop".into(), i as u64), icon::X)
                            .size(20., 12.)
                            .tooltip("Stop streaming this window", None)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                cx.stop_propagation();
                                this.close_window_stream(&hwnd, cx);
                            })),
                    )
                })
                .when(is_sel, |d| d.child(kbd(theme, "⏎")))
                .on_mouse_down(gpui::MouseButton::Left, cx.listener(move |this, _, _, cx| {
                    if let Some(p) = &mut this.window_picker {
                        p.selected = i;
                    }
                    pick_selected(this, cx);
                })),
        );
    }
    let host_uuid = p.host_uuid.clone();
    let header = div()
        .flex()
        .items_center()
        .gap(px(10.))
        .px(px(14.))
        .h(px(48.))
        .border_b_1()
        .border_color(theme.border)
        .child(icon_el(icon::SEARCH, 16., theme.text_muted))
        .child(div().flex_1().child(input))
        .child(
            button("window-picker-launch", "Launch app…")
                .compact()
                .ghost()
                .icon(icon::APP_WINDOW)
                .kbd("⌘O")
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.window_picker = None;
                    this.pending_launcher = Some(host_uuid.clone());
                    cx.notify();
                })),
        );
    let footer = div()
        .flex()
        .items_center()
        .gap(px(12.))
        .px(px(10.))
        .h(px(28.))
        .border_t_1()
        .border_color(theme.border)
        .text_size(type_scale::XS)
        .text_color(theme.text_muted)
        .child(div().flex().items_center().gap(px(4.)).child(kbd(theme, "⏎")).child(if selected_streaming { "bring to front" } else { "stream" }))
        .child(div().flex().items_center().gap(px(4.)).child(kbd(theme, "↑↓")).child("choose"))
        .child(div().flex().items_center().gap(px(4.)).child(kbd(theme, "esc")).child("close"));
    if panel {
        return Some(crate::views::launcher::card("window-picker", theme, header, rows, footer).shadow(crate::views::launcher::card_shadow(theme)).into_any_element());
    }
    Some(crate::views::launcher::frame("window-picker", theme, top, header, rows, footer, close, cx))
}

package app.sloplight.ui.settings

import app.sloplight.ui.settings.Setting.Action
import app.sloplight.ui.settings.Setting.Choice
import app.sloplight.ui.settings.Setting.Link
import app.sloplight.ui.settings.Setting.Slider
import app.sloplight.ui.settings.Setting.Text
import app.sloplight.ui.settings.Setting.Toggle
import com.limelight.R

/** Pref keys the settings logic needs by name (as in PreferenceConfiguration) */
internal object Keys {
    const val RESOLUTION = "list_resolution"
    const val FPS = "list_fps"
    const val BITRATE = "seekbar_bitrate_kbps"
    const val UNLOCK_FPS = "checkbox_unlock_fps"
    const val ENABLE_HDR = "checkbox_enable_hdr"
    const val LANGUAGE = "list_languages"
    const val CUSTOM_RESOLUTION = "edit_diy_w_h"
    const val CUSTOM_BITRATE = "edit_diy_bitrate"
    const val CUSTOM_REFRESH_RATE = "custom_refresh_rate"
    const val DEFAULT_RESOLUTION = "1920x1080"
    const val DEFAULT_FPS = "60"
}

/** Every setting, by category and section (most important first) */
internal object SettingsCatalog {
    val groups: List<SettingGroup> = listOf(
        SettingGroup(
            key = "category_video_settings",
            title = R.string.settings_group_video,
            summary = R.string.settings_group_video_summary,
            sections = listOf(
                SettingSection(R.string.settings_section_quality, listOf(
                    Choice(Keys.RESOLUTION, R.string.title_resolution_list, R.string.summary_resolution_list,
                        default = Keys.DEFAULT_RESOLUTION, entries = R.array.resolution_names, values = R.array.resolution_values,
                        source = ChoiceSource.RESOLUTION, effect = ChangeEffect.RESOLUTION),
                    Choice(Keys.FPS, R.string.title_fps_list, R.string.summary_fps_list,
                        default = Keys.DEFAULT_FPS, entries = R.array.fps_names, values = R.array.fps_values,
                        source = ChoiceSource.FPS, effect = ChangeEffect.FPS),
                    Slider(Keys.BITRATE, R.string.title_seekbar_bitrate, R.string.summary_seekbar_bitrate,
                        default = null, min = 500, max = 300000, step = 500, divisor = 1000,
                        suffixRes = R.string.suffix_seekbar_bitrate_mbps, dialogMessage = R.string.summary_seekbar_bitrate),
                    Slider("seekbar_metered_bitrate_kbps", R.string.title_seekbar_metered_bitrate, R.string.summary_seekbar_metered_bitrate,
                        default = null, min = 0, max = 300000, step = 500, divisor = 1000,
                        suffixRes = R.string.suffix_seekbar_bitrate_mbps, dialogMessage = R.string.summary_seekbar_metered_bitrate),
                    Choice("video_format", R.string.title_video_format, R.string.summary_video_format,
                        default = "auto", entries = R.array.video_format_names, values = R.array.video_format_values,
                        shortEntries = R.array.settings_short_video_format),
                    Toggle(Keys.ENABLE_HDR, R.string.title_enable_hdr, R.string.summary_enable_hdr, default = false, visibleWhen = Feature.HDR10),
                    Toggle("checkbox_full_range", R.string.title_full_range, R.string.summary_full_range, default = false),
                    Toggle("checkbox_efficient_encoding", R.string.title_checkbox_efficient_encoding, R.string.summary_checkbox_efficient_encoding, default = true),
                    Toggle("checkbox_quiet_idle", R.string.title_checkbox_quiet_idle, R.string.summary_checkbox_quiet_idle, default = true),
                )),
                SettingSection(R.string.settings_section_latency, listOf(
                    Choice("frame_pacing", R.string.title_frame_pacing, R.string.summary_frame_pacing,
                        default = "latency", entries = R.array.video_frame_pacing_names, values = R.array.video_frame_pacing_values),
                    Toggle(Keys.UNLOCK_FPS, R.string.title_unlock_fps, R.string.summary_unlock_fps, default = false),
                    Toggle("pref_low_latency_frame_balance", R.string.title_low_latency_frame_balance, R.string.summary_low_latency_frame_balance, default = false),
                    Toggle("checkbox_ultra_low_latency", R.string.title_checkbox_ultra_low_latency, R.string.summary_checkbox_ultra_low_latency, default = false),
                    Toggle("checkbox_forceTightThresholds", R.string.title_checkbox_forceTightThresholds, R.string.summary_checkbox_forceTightThresholds, default = false),
                    Toggle("checkbox_reduce_refresh_rate", R.string.title_checkbox_reduce_refresh_rate,
                        R.string.summary_checkbox_reduce_refresh_rate, default = false),
                    Toggle("checkbox_prevent_packet_loss", R.string.title_prevent_packet_loss, R.string.summary_prevent_packet_loss, default = false),
                )),
                SettingSection(R.string.settings_section_screen, listOf(
                    Choice("list_video_scale_mode", R.string.title_video_scale_mode, R.string.summary_video_scale_mode,
                        default = "fit", entries = R.array.video_scale_mode_names, values = R.array.video_scale_mode_values,
                        shortEntries = R.array.video_scale_mode_names),
                    Toggle("checkbox_auto_orientation", R.string.title_auto_orientation, R.string.summary_auto_orientation, default = false),
                    Toggle("checkbox_auto_invert_video_resolution", R.string.title_checkbox_auto_invert_video_resolution,
                        R.string.summary_checkbox_auto_invert_video_resolution, default = true, enabledWhen = "checkbox_auto_orientation"),
                    Toggle("checkbox_enable_view_top_center", R.string.title_top_center_display, R.string.summary_top_center_display, default = false),
                    Toggle("checkbox_enforce_display_mode", R.string.title_checkbox_enforce_display_mode, R.string.summary_checkbox_enforce_display_mode, default = false),
                    Toggle("checkbox_enable_fullexdisplay", R.string.title_fullexdisplay_mode, R.string.summary_fullexdisplay_mode, default = false),
                )),
                SettingSection(R.string.settings_section_custom, listOf(
                    Text(Keys.CUSTOM_RESOLUTION, R.string.title_custom_resolution, R.string.summary_custom_resolution,
                        default = "1920x1080", kind = TextKind.RESOLUTION, dialogTitle = R.string.dialog_custom_resolution),
                    Text(Keys.CUSTOM_BITRATE, R.string.title_manual_enter_bitrate, R.string.summary_manual_enter_bitrate,
                        default = "20", kind = TextKind.BITRATE, dialogTitle = R.string.title_manual_enter_bitrate,
                        dialogMessage = R.string.dialog_manual_enter_bitrate),
                    Text(Keys.CUSTOM_REFRESH_RATE, R.string.title_custom_refresh_rate, R.string.summary_custom_refresh_rate,
                        default = "59.94", kind = TextKind.REFRESH_RATE, dialogTitle = R.string.title_custom_refresh_rate,
                        dialogMessage = R.string.dialog_custom_refresh_rate),
                )),
                SettingSection(R.string.settings_section_3d, listOf(
                    Choice("render_mode_list", R.string.title_render_mode, R.string.summary_render_mode,
                        default = "0", entries = R.array.render_mode_names, values = R.array.render_mode_values,
                        shortEntries = R.array.settings_short_render_mode),
                    Slider("parallax_depth", R.string.title_render_mode_strength_title, R.string.title_render_mode_strength_description,
                        default = 50, min = 1, max = 100, suffix = "%"),
                    Slider("convergence_ratio", R.string.title_render_mode_convergence_title, R.string.title_render_mode_convergence_description,
                        default = 50, min = 0, max = 100, suffix = "%"),
                    Slider("balance_shift", R.string.title_render_mode_balance_shift_title, R.string.title_render_mode_balance_shift_description,
                        default = 50, min = 0, max = 100, suffix = "%"),
                )),
            ),
        ),
        SettingGroup(
            key = "category_audio_settings",
            title = R.string.settings_group_audio,
            summary = R.string.settings_group_audio_summary,
            sections = listOf(
                SettingSection(0, listOf(
                    Choice("list_audio_config", R.string.title_audio_config_list, R.string.summary_audio_config_list,
                        default = "2", entries = R.array.audio_config_names, values = R.array.audio_config_values,
                        shortEntries = R.array.settings_short_audio_config),
                    Toggle("checkbox_enable_audiofx", R.string.title_checkbox_enable_audiofx, R.string.summary_checkbox_enable_audiofx, default = false),
                    Toggle("checkbox_host_audio", R.string.title_checkbox_host_audio, R.string.summary_checkbox_host_audio, default = false),
                )),
            ),
        ),
        SettingGroup(
            key = "category_gamepad_settings",
            title = R.string.settings_group_gamepad,
            summary = R.string.settings_group_gamepad_summary,
            sections = listOf(
                SettingSection(R.string.settings_section_basics, listOf(
                    Toggle("checkbox_multi_controller", R.string.title_checkbox_multi_controller, R.string.summary_checkbox_multi_controller, default = true),
                    Slider("seekbar_deadzone", R.string.title_seekbar_deadzone, R.string.summary_seekbar_deadzone,
                        default = 5, min = -20, max = 20, suffixRes = R.string.suffix_seekbar_deadzone),
                    Toggle("checkbox_flip_face_buttons", R.string.title_checkbox_flip_face_buttons, R.string.summary_checkbox_flip_face_buttons, default = false),
                    Toggle("checkbox_back_as_guide", R.string.title_back_as_guide, R.string.summary_back_as_guide, default = false),
                    Toggle("checkbox_gamepad_enable_battery_report", R.string.title_checkbox_gamepad_enable_battery_report,
                        R.string.summary_checkbox_gamepad_enable_battery_report, default = true),
                    Toggle("checkbox_enable_joyconfix", R.string.title_joyconfix, R.string.summary_joyconfix, default = false),
                )),
                SettingSection(R.string.settings_section_usb, listOf(
                    Toggle("checkbox_usb_driver", R.string.title_checkbox_xb1_driver, R.string.summary_checkbox_xb1_driver, default = true,
                        visibleWhen = Feature.USB_HOST),
                    Toggle("checkbox_usb_bind_all", R.string.title_checkbox_usb_bind_all, R.string.summary_checkbox_usb_bind_all, default = false,
                        enabledWhen = "checkbox_usb_driver", visibleWhen = Feature.USB_HOST),
                )),
                SettingSection(R.string.settings_section_mouse_emulation, listOf(
                    Toggle("checkbox_mouse_emulation", R.string.title_checkbox_mouse_emulation, R.string.summary_checkbox_mouse_emulation, default = true),
                    Choice("analog_scrolling", R.string.title_analog_scrolling, R.string.summary_analog_scrolling,
                        default = "right", entries = R.array.analog_scrolling_names, values = R.array.analog_scrolling_values,
                        shortEntries = R.array.settings_short_analog_scrolling,
                        enabledWhen = "checkbox_mouse_emulation"),
                    Toggle("checkbox_gamepad_touchpad_as_mouse", R.string.title_checkbox_gamepad_touchpad_as_mouse,
                        R.string.summary_checkbox_gamepad_touchpad_as_mouse, default = false),
                )),
                SettingSection(R.string.settings_section_motion, listOf(
                    Toggle("checkbox_gamepad_motion_sensors", R.string.title_checkbox_gamepad_motion_sensors,
                        R.string.summary_checkbox_gamepad_motion_sensors, default = true, visibleWhen = Feature.CONTROLLER_SENSORS),
                    Toggle("checkbox_gamepad_motion_fallback", R.string.title_checkbox_gamepad_motion_fallback,
                        R.string.summary_checkbox_gamepad_motion_fallback, default = false, visibleWhen = Feature.DEVICE_MOTION),
                    Toggle("checkbox_force_device_motion", R.string.title_checkbox_force_device_motion, R.string.summary_checkbox_force_device_motion,
                        default = false, enabledWhen = "checkbox_gamepad_motion_fallback", visibleWhen = Feature.DEVICE_MOTION),
                )),
                SettingSection(R.string.settings_section_rumble, listOf(
                    Toggle("checkbox_enable_rumble", R.string.title_enable_rumble, R.string.summary_enable_rumble, default = true),
                    Toggle("checkbox_vibrate_fallback", R.string.title_checkbox_vibrate_fallback, R.string.summary_checkbox_vibrate_fallback,
                        default = false, enabledWhen = "checkbox_enable_rumble", visibleWhen = Feature.VIBRATOR),
                    Toggle("checkbox_enable_device_rumble", R.string.title_device_rumble, R.string.summary_device_rumble,
                        default = false, enabledWhen = "checkbox_vibrate_fallback", visibleWhen = Feature.VIBRATOR),
                    Slider("seekbar_vibrate_fallback_strength", R.string.title_seekbar_vibrate_fallback_strength,
                        R.string.summary_seekbar_vibrate_fallback_strength, default = 100, min = 1, max = 200,
                        suffixRes = R.string.suffix_seekbar_vibrate_fallback_strength,
                        enabledWhen = "checkbox_vibrate_fallback", visibleWhen = Feature.VIBRATOR_AMPLITUDE),
                )),
            ),
        ),
        SettingGroup(
            key = "category_input_settings",
            title = R.string.settings_group_input,
            summary = R.string.settings_group_input_summary,
            sections = listOf(
                SettingSection(R.string.settings_section_touchscreen, listOf(
                    Choice("mouse_mode_list", R.string.title_touchscreen_mode, R.string.summary_touchscreen_mode,
                        default = "0", entries = R.array.mouse_mode_names, values = R.array.mouse_mode_values),
                    Toggle("checkbox_multi_touch_gestures", R.string.title_checkbox_multi_touch_gestures, R.string.summary_checkbox_multi_touch_gestures, default = false),
                    Toggle("checkbox_remember_mouse_mode", R.string.title_checkbox_remember_mouse_mode, R.string.summary_checkbox_remember_mouse_mode,
                        default = false, enabledWhen = "checkbox_mouse_emulation"),
                )),
                SettingSection(R.string.settings_section_touch_sensitivity, listOf(
                    Toggle("checkbox_enable_touch_sensitivity", R.string.title_touch_sensitivity, R.string.summary_touch_sensitivity, default = false),
                    Toggle("checkbox_enable_global_touch_sensitivity", R.string.title_global_touch_sensitivity, R.string.summary_global_touch_sensitivity,
                        default = false, enabledWhen = "checkbox_enable_touch_sensitivity"),
                    Toggle("checkbox_enable_touch_sensitivity_rotation_auto", R.string.title_auto_touch_sensitivity, R.string.summary_auto_touch_sensitivity,
                        default = true, enabledWhen = "checkbox_enable_touch_sensitivity"),
                    Slider("seekbar_touch_sensitivity_opacity_x", R.string.title_touch_sensitivity_x, R.string.summary_touch_sensitivity_x,
                        default = 100, min = 10, max = 300, step = 10, suffix = "%", enabledWhen = "checkbox_enable_touch_sensitivity"),
                    Slider("seekbar_touch_sensitivity_opacity_y", R.string.title_touch_sensitivity_y, R.string.summary_touch_sensitivity_y,
                        default = 100, min = 10, max = 300, step = 10, suffix = "%", enabledWhen = "checkbox_enable_touch_sensitivity"),
                )),
                SettingSection(R.string.settings_section_trackpad_mode, listOf(
                    Slider("seekbar_touchpad_sensitivity_opacity", R.string.title_touchpad_sensitivity_x, R.string.summary_touchpad_sensitivity_x,
                        default = 100, min = 10, max = 300, step = 10, suffix = "%"),
                    Slider("seekbar_touchpad_sensitivity_y_opacity", R.string.title_touchpad_sensitivity_y, R.string.summary_touchpad_sensitivity_y,
                        default = 100, min = 10, max = 300, step = 10, suffix = "%"),
                )),
                SettingSection(R.string.settings_section_mouse, listOf(
                    Toggle("checkbox_mouse_local_cursor", R.string.title_checkbox_mouse_local_cursor, R.string.summary_checkbox_mouse_local_cursor, default = false),
                    Toggle("checkbox_mouse_nav_buttons", R.string.title_checkbox_mouse_nav_buttons, R.string.summary_checkbox_mouse_nav_buttons, default = false),
                    Toggle("checkbox_absolute_mouse_mode", R.string.title_checkbox_absolute_mouse_mode, R.string.summary_checkbox_absolute_mouse_mode,
                        default = false, visibleWhen = Feature.ABSOLUTE_MOUSE),
                )),
                SettingSection(R.string.settings_section_physical_trackpad, listOf(
                    Slider("seekbar_trackpad_sensitivity_x", R.string.title_trackpad_sensitivity_x, R.string.summary_trackpad_sensitivity_x,
                        default = 100, min = -200, max = 200, step = 10, suffix = "%"),
                    Slider("seekbar_trackpad_sensitivity_y", R.string.title_trackpad_sensitivity_y, R.string.summary_trackpad_sensitivity_y,
                        default = 100, min = -200, max = 200, step = 10, suffix = "%"),
                    Toggle("checkbox_trackpad_swap_axis", R.string.title_trackpad_swap_axis, R.string.summary_trackpad_swap_axis, default = false),
                    Toggle("checkbox_trackpad_drag_drop_vibration", R.string.title_trackpad_drag_drop_vibration,
                        R.string.summary_trackpad_drag_drop_vibration, default = false),
                    Slider("seekbar_trackpad_drag_drop_threshold", R.string.title_trackpad_drag_drop_threshold,
                        R.string.summary_trackpad_drag_drop_threshold, default = 250, min = 0, max = 2000, suffix = "ms"),
                )),
            ),
        ),
        SettingGroup(
            key = "category_keyboard",
            title = R.string.settings_group_keyboard,
            summary = R.string.settings_group_keyboard_summary,
            sections = listOf(
                SettingSection(R.string.settings_section_physical_keyboard, listOf(
                    Toggle("checkbox_force_qwerty", R.string.title_checkbox_force_qwerty, R.string.summary_checkbox_force_qwerty, default = true),
                    Toggle("checkbox_back_as_meta", R.string.title_checkbox_back_as_meta, R.string.summary_back_as_meta, default = false),
                    Toggle("checkbox_right_alt_as_meta", R.string.title_checkbox_right_alt_as_meta, R.string.summary_right_alt_as_meta, default = false),
                    Toggle("checkbox_ignore_synth_events", R.string.title_checkbox_ignore_synth_events, R.string.summary_ignore_synth_events, default = false),
                )),
                SettingSection(R.string.settings_section_onscreen_keyboard, listOf(
                    Toggle("checkbox_enable_commit_text", R.string.title_enable_commit_text, R.string.summary_enable_commit_text, default = false),
                    Toggle("checkbox_enable_sticky_modifier_key_virtual_keyboard", R.string.title_checkbox_enable_sticky_modifier_key_virtual_keyboard,
                        R.string.summary_checkbox_enable_sticky_modifier_key_virtual_keyboard, default = true),
                    Slider("seekbar_keyboard_axi_opacity", R.string.title_keyboard_opacity, R.string.summary_osc_opacity,
                        default = 90, min = 0, max = 100, suffix = "%"),
                    Choice("list_onscreen_keyboard_align_mode", R.string.title_onscreen_keyboard_align_mode, R.string.summary_onscreen_keyboard_align_mode,
                        default = "center", entries = R.array.onscreen_keyboard_align_modes, values = R.array.onscreen_keyboard_align_values,
                        shortEntries = R.array.settings_short_align),
                    Toggle("onscreen_keyboard_autofit", R.string.title_onscreen_keyboard_autofit, R.string.summary_onscreen_keyboard_autofit, default = false),
                    Slider("seekbar_onscreen_keyboard_height", R.string.title_onscreen_keyboard_height, R.string.summary_onscreen_keyboard_height,
                        default = 200, min = 150, max = 400, step = 10, suffix = "dp", enabledWhen = "onscreen_keyboard_autofit"),
                    Slider("seekbar_onscreen_keyboard_width", R.string.title_onscreen_keyboard_width, R.string.summary_onscreen_keyboard_width,
                        default = 1000, min = 150, max = 1000, step = 10, suffix = "dp", enabledWhen = "onscreen_keyboard_autofit"),
                )),
                SettingSection(R.string.settings_section_custom_keys, listOf(
                    Action("import_special_button_file", R.string.title_import_special_button, R.string.summary_import_special_button,
                        action = SettingAction.IMPORT_SPECIAL_BUTTONS, hiddenInProfile = true),
                    Link("option_help_custom_keys", R.string.title_special_key_help, R.string.summary_special_key_help,
                        url = "https://github.com/ClassicOldSong/moonlight-android/wiki/Custom-Special-Keys", hiddenInProfile = true),
                )),
            ),
        ),
        SettingGroup(
            key = "category_onscreen_controls",
            title = R.string.settings_group_onscreen,
            summary = R.string.settings_group_onscreen_summary,
            visibleWhen = Feature.TOUCHSCREEN,
            sections = listOf(
                SettingSection(R.string.settings_section_virtual_gamepad, listOf(
                    Toggle("checkbox_show_onscreen_controls", R.string.title_checkbox_show_onscreen_controls,
                        R.string.summary_checkbox_show_onscreen_controls, default = false),
                    Toggle("checkbox_hide_osc_when_has_gamepad", R.string.title_hide_osc_when_has_gamepad, R.string.summary_hide_osc_when_has_gamepad,
                        default = true, enabledWhen = "checkbox_show_onscreen_controls"),
                    Toggle("checkbox_onscreen_style_official", R.string.title_checkbox_onscreen_style_official,
                        R.string.summary_checkbox_onscreen_style_official, default = false, enabledWhen = "checkbox_show_onscreen_controls"),
                    Toggle("checkbox_vibrate_osc", R.string.title_checkbox_vibrate_osc, R.string.summary_checkbox_vibrate_osc,
                        default = true, enabledWhen = "checkbox_show_onscreen_controls", visibleWhen = Feature.VIBRATOR),
                    Toggle("checkbox_only_show_L3R3", R.string.title_only_l3r3, R.string.summary_only_l3r3,
                        default = false, enabledWhen = "checkbox_show_onscreen_controls"),
                    Toggle("checkbox_show_guide_button", R.string.title_show_guide_button, R.string.summary_show_guide_button,
                        default = true, enabledWhen = "checkbox_show_onscreen_controls"),
                    Slider("seekbar_osc_opacity", R.string.dialog_title_osc_opacity, R.string.summary_osc_opacity,
                        default = 90, min = 0, max = 100, suffixRes = R.string.suffix_osc_opacity,
                        dialogMessage = R.string.summary_osc_opacity, enabledWhen = "checkbox_show_onscreen_controls"),
                    Toggle("checkbox_enable_analog_stick_new", R.string.title_free_analog_stick, R.string.summary_free_analog_stick,
                        default = false, enabledWhen = "checkbox_show_onscreen_controls"),
                    Slider("seekbar_osc_free_analog_stick_opacity", R.string.title_free_analog_stick_opacity, R.string.summary_free_analog_stick_opacity,
                        default = 20, min = 0, max = 100, suffixRes = R.string.suffix_osc_opacity,
                        dialogMessage = R.string.summary_osc_opacity, enabledWhen = "checkbox_enable_analog_stick_new"),
                    Action("option_reset_osc_preference", R.string.title_reset_osc, R.string.summary_reset_osc,
                        action = SettingAction.RESET_OSC, hiddenInProfile = true),
                )),
                SettingSection(R.string.settings_section_virtual_keys, listOf(
                    Toggle("checkbox_enable_keyboard", R.string.title_show_virtual_key, R.string.summary_show_virtual_key, default = false),
                    Choice("keyboard_axi_list", R.string.title_default_layout_scheme, R.string.summary_default_layout_scheme,
                        default = "OSC_Keyboard", entries = R.array.keyboard_axi_names, values = R.array.keyboard_axi_values,
                        enabledWhen = "checkbox_enable_keyboard"),
                    Toggle("checkbox_enable_keyboard_square", R.string.title_square_buttons, R.string.summary_square_buttons,
                        default = false, enabledWhen = "checkbox_enable_keyboard"),
                    Toggle("checkbox_vibrate_keyboard", R.string.title_checkbox_vibrate_osc, R.string.summary_vibrate_keyboard,
                        default = false, visibleWhen = Feature.VIBRATOR),
                    Toggle("checkbox_enable_clear_default_special_button", R.string.title_clear_default_special_button,
                        R.string.summary_clear_default_special_button, default = false),
                    Action("option_reset_osc", R.string.title_reset_keyboard_layout, R.string.summary_reset_keyboard_layout,
                        action = SettingAction.RESET_KEYBOARD, enabledWhen = "checkbox_enable_keyboard"),
                    Action("import_keyboard_file", R.string.title_import_keyboard_layout, R.string.summary_import_keyboard_layout,
                        action = SettingAction.IMPORT_KEYBOARD, hiddenInProfile = true),
                    Action("export_keyboard_file", R.string.title_export_keyboard_layout, R.string.summary_export_keyboard_layout,
                        action = SettingAction.EXPORT_KEYBOARD, hiddenInProfile = true),
                )),
            ),
        ),
        SettingGroup(
            key = "category_ui_settings",
            title = R.string.settings_group_ui,
            summary = R.string.settings_group_ui_summary,
            sections = listOf(
                SettingSection(R.string.settings_section_behavior, listOf(
                    Toggle("checkbox_resume_without_confirm", R.string.title_checkbox_resume_without_confirm,
                        R.string.summary_checkbox_resume_without_confirm, default = false),
                    Toggle("checkbox_enable_quit_dialog", R.string.title_quit_dialog, R.string.summary_quit_dialog, default = true),
                    Toggle("checkbox_enable_floating_button", R.string.title_floating_button, R.string.summary_floating_button,
                        default = false, enabledWhen = "checkbox_enable_quit_dialog"),
                    Toggle("checkbox_full_screen", R.string.title_checkbox_full_screen, R.string.summary_checkbox_full_screen, default = true),
                    Toggle("checkbox_enable_pip", R.string.title_checkbox_enable_pip, R.string.summary_checkbox_enable_pip,
                        default = false, visibleWhen = Feature.PIP),
                    Toggle("checkbox_disable_warnings", R.string.title_checkbox_disable_warnings, R.string.summary_checkbox_disable_warnings, default = false),
                    Toggle("checkbox_enable_post_stream_toast", R.string.title_enable_post_stream_toast, R.string.summary_enable_post_stream_toast, default = false),
                )),
                SettingSection(R.string.settings_section_zoom, listOf(
                    Toggle("checkbox_show_overlay_zoom_toggle_button", R.string.title_show_overlay_zoom_toggle_button,
                        R.string.summary_show_overlay_zoom_toggle_button, default = false),
                    Toggle("checkbox_remember_zoom_pan", R.string.title_checkbox_remember_zoom_pan, R.string.summary_checkbox_remember_zoom_pan, default = false),
                )),
                SettingSection(R.string.settings_section_perf_overlay, listOf(
                    Toggle("checkbox_enable_perf_overlay", R.string.title_enable_perf_overlay, R.string.summary_enable_perf_overlay, default = false),
                    Toggle("checkbox_enable_perf_overlay_lite", R.string.title_checkbox_enable_perf_overlay_lite,
                        R.string.summary_checkbox_enable_perf_overlay_lite, default = false, enabledWhen = "checkbox_enable_perf_overlay"),
                    Toggle("checkbox_enable_perf_overlay_lite_dialog", R.string.title_checkbox_enable_perf_overlay_lite_dialog,
                        R.string.summary_checkbox_enable_perf_overlay_lite_dialog, default = false, enabledWhen = "checkbox_enable_perf_overlay_lite"),
                    Toggle("checkbox_enable_perf_overlay_bottom", R.string.title_checkbox_enable_perf_overlay_bottom,
                        R.string.summary_checkbox_enable_perf_overlay_bottom, default = false, enabledWhen = "checkbox_enable_perf_overlay"),
                    Toggle("checkbox_enable_perf_logging", R.string.title_checkbox_enable_perf_logging, R.string.summary_checkbox_enable_perf_logging,
                        default = false, effect = ChangeEffect.PERF_LOGGING, enabledWhen = "checkbox_enable_perf_overlay"),
                    Action("share_performance_logs", R.string.title_share_performance_logs_button, R.string.summary_share_performance_logs,
                        action = SettingAction.SHARE_PERF_LOGS, enabledWhen = "checkbox_enable_perf_logging"),
                    Link("option_view_shared_pref_logs", R.string.title_performance_link, R.string.summary_performance_link,
                        url = "https://github.com/ClassicOldSong/moonlight-android/wiki/Performance-Statistics-Collection"),
                )),
                SettingSection(R.string.settings_section_clipboard, listOf(
                    Toggle("checkbox_smart_clipboard_sync", R.string.title_smart_clipboard_sync, R.string.summary_smart_clipboard_sync, default = false),
                    Toggle("checkbox_smart_clipboard_sync_toast", R.string.title_smart_clipboard_sync_toast, R.string.summary_smart_clipboard_sync_toast, default = true),
                    Toggle("checkbox_hide_clipboard_content", R.string.title_hide_clipboard_content, R.string.summary_hide_clipboard_content, default = true),
                )),
            ),
        ),
        SettingGroup(
            key = "category_host_settings",
            title = R.string.settings_group_host,
            summary = R.string.settings_group_host_summary,
            sections = listOf(
                SettingSection(R.string.settings_section_host_display, listOf(
                    Toggle("checkbox_use_virtual_display", R.string.title_checkbox_use_virtual_display, R.string.summary_checkbox_use_virtual_display, default = true),
                    Choice("list_desktop_scale", R.string.title_desktop_scale, R.string.summary_desktop_scale,
                        default = "auto", entries = R.array.desktop_scale_names, values = R.array.desktop_scale_values),
                    Slider("seekbar_resolution_scale_factor", R.string.title_resolution_scale_factor, R.string.summary_resolution_scale_factor,
                        default = 100, min = 20, max = 200, suffix = "%"),
                )),
                SettingSection(R.string.settings_section_host_games, listOf(
                    Toggle("checkbox_enable_sops", R.string.title_checkbox_enable_sops, R.string.summary_checkbox_enable_sops, default = true),
                )),
            ),
        ),
        SettingGroup(
            key = "category_settings_misc",
            title = R.string.settings_group_misc,
            summary = R.string.settings_group_misc_summary,
            sections = listOf(
                SettingSection(R.string.settings_section_app, listOf(
                    Choice(Keys.LANGUAGE, R.string.title_language_list, R.string.summary_language_list,
                        default = "default", entries = R.array.language_names, values = R.array.language_values,
                        icon = R.drawable.ic_language),
                )),
                SettingSection(R.string.settings_section_about, listOf(
                    Action("pref_debug_info", R.string.title_debug_info, R.string.summary_debug_info, action = SettingAction.DEBUG_INFO),
                )),
            ),
        ),
    )

    /** Every setting by key */
    val byKey: Map<String, Setting> = groups.flatMap { it.settings }.associateBy { it.key }
}

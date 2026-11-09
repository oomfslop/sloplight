/**
 * @file src/web_ui.h
 * @brief The browser interface, built into the executable.
 */
#pragma once

#include <span>
#include <string_view>

namespace web_ui {
  struct file_t {
    std::string_view path;
    std::string_view data;
  };

  /**
   * @brief Every file of the built interface, sorted by path relative to its root ("index.html", "assets/app.js").
   */
  extern const std::span<const file_t> files;
}  // namespace web_ui

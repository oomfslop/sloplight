# embed_web_ui.cmake - Turn the built browser interface into a C++ source.
# Input:  WEB_DIR  - directory Vite built the interface into
# Output: OUT_FILE - C++ source defining web_ui::files, sorted by path

file(GLOB_RECURSE paths LIST_DIRECTORIES FALSE RELATIVE "${WEB_DIR}" "${WEB_DIR}/*")
list(SORT paths)
if(NOT "index.html" IN_LIST paths)
    message(FATAL_ERROR "No built web UI in ${WEB_DIR}")
endif()

set(definitions "")
set(entries "")
set(index 0)
foreach(path IN LISTS paths)
    if(path MATCHES "[\"\\\\]")
        message(FATAL_ERROR "Unsupported web UI file name: ${path}")
    endif()
    file(READ "${WEB_DIR}/${path}" data HEX)
    string(REGEX REPLACE "(..)" "\\\\x\\1" data "${data}")
    string(APPEND definitions "    constexpr char file_${index}[] = \"${data}\";\n")
    string(APPEND entries "    {\"${path}\", {file_${index}, sizeof(file_${index}) - 1}},\n")
    math(EXPR index "${index} + 1")
endforeach()

file(WRITE "${OUT_FILE}" "#include \"src/web_ui.h\"

#include <array>

namespace web_ui {
  namespace {
${definitions}
    constexpr std::array<file_t, ${index}> sorted_files {{
${entries}    }};
  }  // namespace

  const std::span<const file_t> files {sorted_files};
}  // namespace web_ui
")

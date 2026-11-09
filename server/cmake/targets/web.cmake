set(SUNSHINE_WEB_SOURCE_DIR "${SUNSHINE_SOURCE_ASSETS_DIR}/common/assets/web")
set(SUNSHINE_WEB_OUTPUT_DIR "${CMAKE_BINARY_DIR}/web_ui")
set(SUNSHINE_WEB_EMBED_SOURCE "${CMAKE_BINARY_DIR}/generated/web_ui_files.cpp")
set(SUNSHINE_WEB_EMBED_SCRIPT "${CMAKE_SOURCE_DIR}/cmake/scripts/embed_web_ui.cmake")

set(SUNSHINE_WEB_SOURCES)
# npm ci creates and replaces node_modules while this target runs.  Do not use
# CONFIGURE_DEPENDS for these globs: CMake would record the generated directory
# as part of the source tree, re-run configuration on the next build, and make
# the MSI step rebuild the native targets a second time.  The committed web
# files are still explicit dependencies of the web build command below, and a
# normal configure picks up newly added files.
file(GLOB SUNSHINE_WEB_ROOT_SOURCES
    LIST_DIRECTORIES TRUE
    "${SUNSHINE_WEB_SOURCE_DIR}/*")
list(FILTER SUNSHINE_WEB_ROOT_SOURCES EXCLUDE REGEX "/(node_modules|dist|\\.vite|\\.cache|coverage)/?$")
foreach(SUNSHINE_WEB_ROOT_SOURCE IN LISTS SUNSHINE_WEB_ROOT_SOURCES)
    if(IS_DIRECTORY "${SUNSHINE_WEB_ROOT_SOURCE}")
        file(GLOB_RECURSE SUNSHINE_WEB_SUBDIR_SOURCES
            LIST_DIRECTORIES FALSE
            "${SUNSHINE_WEB_ROOT_SOURCE}/*")
        list(FILTER SUNSHINE_WEB_SUBDIR_SOURCES EXCLUDE REGEX "/(node_modules|dist|\\.vite|\\.cache|coverage)/")
        list(APPEND SUNSHINE_WEB_SOURCES ${SUNSHINE_WEB_SUBDIR_SOURCES})
    else()
        list(APPEND SUNSHINE_WEB_SOURCES "${SUNSHINE_WEB_ROOT_SOURCE}")
    endif()
endforeach()

find_program(SUNSHINE_NPM_EXECUTABLE NAMES npm.cmd npm)
if(NOT SUNSHINE_NPM_EXECUTABLE)
    message(FATAL_ERROR "npm is required to build the Vibeshine browser interface")
endif()

add_custom_command(
    OUTPUT "${SUNSHINE_WEB_EMBED_SOURCE}"
    COMMAND "${SUNSHINE_NPM_EXECUTABLE}" ci --ignore-scripts --no-audit --no-fund --prefer-offline
    COMMAND "${CMAKE_COMMAND}" -E env
            "SUNSHINE_WEB_OUTPUT_DIR=${SUNSHINE_WEB_OUTPUT_DIR}"
            "${SUNSHINE_NPM_EXECUTABLE}" run build
    COMMAND "${CMAKE_COMMAND}"
            "-DWEB_DIR=${SUNSHINE_WEB_OUTPUT_DIR}"
            "-DOUT_FILE=${SUNSHINE_WEB_EMBED_SOURCE}"
            -P "${SUNSHINE_WEB_EMBED_SCRIPT}"
    WORKING_DIRECTORY "${SUNSHINE_WEB_SOURCE_DIR}"
    DEPENDS ${SUNSHINE_WEB_SOURCES} "${SUNSHINE_WEB_EMBED_SCRIPT}"
    COMMENT "Building the Vibeshine browser interface"
    USES_TERMINAL
)
add_custom_target(web_ui DEPENDS "${SUNSHINE_WEB_EMBED_SOURCE}")

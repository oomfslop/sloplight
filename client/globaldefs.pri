# Support debug and release builds from command line for CI
CONFIG += debug_and_release

# Ensure symbols are always generated
CONFIG += force_debug_info

# sccache can't cache -Zi objects (one PDB written by every compile); -Z7 keeps
# the debug info in each object, and the linker still writes the PDB. Nor can it
# take nmake's batched compiles (many sources, -Fo a directory): no_batch gives
# one cl per source with its own -Fo file
*-msvc:ccache {
    CONFIG += no_batch
    QMAKE_CFLAGS_RELEASE_WITH_DEBUGINFO ~= s/-Zi/-Z7/
    QMAKE_CXXFLAGS_RELEASE_WITH_DEBUGINFO ~= s/-Zi/-Z7/
    QMAKE_CFLAGS_DEBUG ~= s/-Zi/-Z7/
    QMAKE_CXXFLAGS_DEBUG ~= s/-Zi/-Z7/
}

# Disable asserts on release builds
CONFIG(release, debug|release) {
    DEFINES += NDEBUG
}

# Enable CFG, EHCont, and CET
*-msvc {
    QMAKE_CFLAGS += -guard:cf -guard:ehcont
    QMAKE_CXXFLAGS += -guard:cf -guard:ehcont
    QMAKE_LFLAGS += -guard:cf -guard:ehcont

    contains(QT_ARCH, x86_64) {
        QMAKE_LFLAGS += -cetcompat
    }
}

# Enable ASan for Linux or macOS
#CONFIG += sanitizer sanitize_address

# Enable ASan for Windows
#QMAKE_CFLAGS += -fsanitize=address
#QMAKE_CXXFLAGS += -fsanitize=address
#QMAKE_LFLAGS += -incremental:no

# Propagate environment variable flags
QMAKE_CFLAGS   += $$(CFLAGS)
QMAKE_CXXFLAGS += $$(CXXFLAGS)
QMAKE_LFLAGS   += $$(LDFLAGS)

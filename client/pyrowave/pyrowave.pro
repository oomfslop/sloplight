QT       -= core gui

TARGET = pyrowave
TEMPLATE = lib

# Build a static library
CONFIG += staticlib c++17

# Include global qmake defs
include(../globaldefs.pri)

# Upstream builds the Metal port with ARC
QMAKE_OBJECTIVE_CFLAGS += -fobjc-arc

INCLUDEPATH += $$PWD/pyrowave/metal

OBJECTIVE_SOURCES += \
    pyrowave/metal/pyrowave_common.mm \
    pyrowave/metal/pyrowave_decoder.mm

SOURCES += \
    pyrowave/metal/pyrowave_bitstream.cpp

HEADERS += \
    pyrowave/metal/pyrowave_metal.h

#include "clipboard.h"

#import <AppKit/AppKit.h>
#import <CoreServices/CoreServices.h>

namespace Clipboard
{

// Chromium/Electron pack DataTransfer custom types into one pickle, named
// "Chromium Web Custom MIME Data Format" on Windows
static NSPasteboardType const kChromiumCustomData = @"org.chromium.web-custom-data";

static const QVector<QPair<QString, NSPasteboardType>> kTranslatedTypes = {
    { "text/plain", NSPasteboardTypeString },
    { "text/html", NSPasteboardTypeHTML },
    { "image/png", NSPasteboardTypePNG },
    { "text/rtf", NSPasteboardTypeRTF },
    { "application/x-chromium-web-custom-data", kChromiumCustomData },
};

static NSPasteboardType nativeTypeFor(const QString& type)
{
    for (const auto& translated : kTranslatedTypes) {
        if (translated.first == type) {
            return translated.second;
        }
    }
    return type.toNSString();
}

qint64 getChangeCount()
{
    return [NSPasteboard generalPasteboard].changeCount;
}

QVector<Format> read(qsizetype maxBytes)
{
    @autoreleasepool {
        NSPasteboard* pasteboard = [NSPasteboard generalPasteboard];
        QVector<Format> formats;
        qsizetype total = 0;
        auto add = [&](const QString& type, NSData* data) {
            if (data == nil || data.length == 0 || total + type.size() + (qsizetype)data.length > maxBytes) {
                return;
            }
            total += type.size() + data.length;
            formats.append({ type, QByteArray::fromNSData(data) });
        };

        for (const auto& translated : kTranslatedTypes) {
            NSData* data = [pasteboard dataForType:translated.second];
            if (data == nil && translated.second == NSPasteboardTypePNG) {
                NSData* tiff = [pasteboard dataForType:NSPasteboardTypeTIFF];
                if (tiff != nil) {
                    data = [[NSBitmapImageRep imageRepWithData:tiff] representationUsingType:NSBitmapImageFileTypePNG properties:@{}];
                }
            }
            add(translated.first, data);
        }

        NSMutableSet<NSPasteboardType>* skipped = [NSMutableSet setWithObject:NSPasteboardTypeTIFF];
        for (const auto& translated : kTranslatedTypes) {
            [skipped addObject:translated.second];
        }
        NSArray<NSPasteboardType>* types = pasteboard.types;
        for (NSPasteboardType type in types) {
            // AppKit lists each type under its legacy pasteboard name as well
            // (dyn.* or "Apple HTML pasteboard type" next to public.html)
            CFStringRef uti = UTTypeCreatePreferredIdentifierForTag(kUTTagClassNSPboardType, (__bridge CFStringRef)type, nullptr);
            const bool alias = uti != nullptr && ![type isEqualToString:(__bridge NSString*)uti] &&
                               [types containsObject:(__bridge NSString*)uti];
            if (uti != nullptr) {
                CFRelease(uti);
            }
            if (alias || [type hasPrefix:@"dyn."] || [skipped containsObject:type]) {
                continue;
            }
            add(QString::fromNSString(type), [pasteboard dataForType:type]);
        }
        return formats;
    }
}

void write(const QVector<Format>& formats)
{
    @autoreleasepool {
        NSPasteboard* pasteboard = [NSPasteboard generalPasteboard];
        [pasteboard clearContents];
        for (const Format& format : formats) {
            [pasteboard setData:format.data.toNSData() forType:nativeTypeFor(format.type)];
        }
    }
}

}

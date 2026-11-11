#pragma once

#include <QByteArray>
#include <QString>
#include <QVector>

// The local clipboard in the host's vocabulary: "text/plain" (UTF-8),
// "text/html", "image/png", "text/rtf", "application/x-chromium-web-custom-data",
// or a native format name passed through unchanged. Main thread only.
namespace Clipboard
{
struct Format
{
    QString type;
    QByteArray data;
};

// Changes whenever the clipboard does, including through write()
qint64 getChangeCount();

// Translated formats first, at most maxBytes of types and data in total
QVector<Format> read(qsizetype maxBytes);

void write(const QVector<Format>& formats);
}

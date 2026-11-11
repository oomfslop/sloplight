#include "clipboard.h"

#ifndef Q_OS_DARWIN
#include <QBuffer>
#include <QClipboard>
#include <QGuiApplication>
#include <QImage>
#include <QMimeData>

namespace Clipboard
{

static qint64 s_ChangeCount = 0;

qint64 getChangeCount()
{
    static bool watching = false;
    if (!watching) {
        QObject::connect(QGuiApplication::clipboard(), &QClipboard::dataChanged, [] { s_ChangeCount++; });
        watching = true;
    }
    return s_ChangeCount;
}

QVector<Format> read(qsizetype maxBytes)
{
    const QMimeData* mime = QGuiApplication::clipboard()->mimeData();
    QVector<Format> formats;
    if (mime == nullptr) {
        return formats;
    }
    qsizetype total = 0;
    auto add = [&](const QString& type, const QByteArray& data) {
        if (data.isEmpty() || total + type.size() + data.size() > maxBytes) {
            return;
        }
        total += type.size() + data.size();
        formats.append({ type, data });
    };

    if (mime->hasText()) {
        add("text/plain", mime->text().toUtf8());
    }
    if (mime->hasHtml()) {
        add("text/html", mime->html().toUtf8());
    }
    if (mime->hasImage()) {
        QByteArray png;
        QBuffer buffer(&png);
        buffer.open(QIODevice::WriteOnly);
        qvariant_cast<QImage>(mime->imageData()).save(&buffer, "PNG");
        add("image/png", png);
    }
    add("text/rtf", mime->data("text/rtf"));
    return formats;
}

void write(const QVector<Format>& formats)
{
    auto mime = new QMimeData;
    for (const Format& format : formats) {
        if (format.type == "text/plain") {
            mime->setText(QString::fromUtf8(format.data));
        }
        else if (format.type == "text/html") {
            mime->setHtml(QString::fromUtf8(format.data));
        }
        else if (format.type == "image/png") {
            mime->setImageData(QImage::fromData(format.data, "PNG"));
        }
        else {
            mime->setData(format.type, format.data);
        }
    }
    QGuiApplication::clipboard()->setMimeData(mime);
}

}
#endif

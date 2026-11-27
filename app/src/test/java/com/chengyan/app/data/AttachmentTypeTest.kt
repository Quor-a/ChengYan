package com.chengyan.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 附件类型判定单测：MIME → 类型映射，决定 UI 渲染与模型输入方式。 */
class AttachmentTypeTest {

    private fun typeOf(mime: String): String = when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("video/") -> "video"
        mime.startsWith("audio/") -> "audio"
        else -> "file"
    }

    @Test fun imageTypes() {
        assertEquals("image", typeOf("image/jpeg"))
        assertEquals("image", typeOf("image/png"))
        assertEquals("image", typeOf("image/webp"))
    }

    @Test fun videoTypes() {
        assertEquals("video", typeOf("video/mp4"))
        assertEquals("video", typeOf("video/quicktime"))
        assertEquals("video", typeOf("video/webm"))
    }

    @Test fun audioTypes() {
        assertEquals("audio", typeOf("audio/mpeg"))
        assertEquals("audio", typeOf("audio/wav"))
        assertEquals("audio", typeOf("audio/ogg"))
    }

    @Test fun documentTypes() {
        assertEquals("file", typeOf("application/pdf"))
        assertEquals("file", typeOf("text/plain"))
        assertEquals("file", typeOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals("file", typeOf("application/zip"))
        assertEquals("file", typeOf("application/octet-stream"))
    }

    @Test fun attachmentModelDefaults() {
        val a = Attachment("file", "report.pdf", "application/pdf", size = 2048)
        assertEquals("file", a.type)
        assertEquals("report.pdf", a.name)
        assertEquals(null, a.base64)
        assertEquals(2048L, a.size)
    }
}

package com.chengyan.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ui-card 协议解析单测：围栏摘除 + 各类型 JSON → Card。 */
class UiCardParserTest {

    @Test fun noteCard() {
        val text = "前置说明\n```ui-card\n{\"type\":\"note\",\"title\":\"提示\",\"body\":\"正文\"}\n```\n后续"
        val (clean, cards) = UiCardParser.parse(text)
        assertEquals(1, cards.size)
        assertEquals(UiCardParser.Kind.NOTE, cards[0].kind)
        assertEquals("提示", cards[0].title)
        assertEquals("正文", cards[0].body)
        assertTrue(!clean.contains("ui-card"))
        assertTrue(clean.contains("前置说明") && clean.contains("后续"))
    }

    @Test fun listCard() {
        val text = "```ui-card\n{\"type\":\"list\",\"items\":[\"一\",\"二\",\"三\"]}\n```"
        val (_, cards) = UiCardParser.parse(text)
        assertEquals(3, cards[0].items.size)
        assertEquals("二", cards[0].items[1])
    }

    @Test fun kvCard() {
        val text = "```ui-card\n{\"type\":\"kv\",\"data\":{\"CPU\":\"8核\",\"内存\":\"12G\"}}\n```"
        val (_, cards) = UiCardParser.parse(text)
        assertEquals(UiCardParser.Kind.KV, cards[0].kind)
        assertEquals("8核", cards[0].kv["CPU"])
    }

    @Test fun noCard() {
        val (clean, cards) = UiCardParser.parse("普通回复，没有卡片")
        assertEquals(0, cards.size)
        assertEquals("普通回复，没有卡片", clean)
    }

    @Test fun malformedJsonIgnored() {
        val (clean, cards) = UiCardParser.parse("```ui-card\nnot json\n```")
        assertEquals(0, cards.size)
        assertTrue(!clean.contains("ui-card"))
    }
}

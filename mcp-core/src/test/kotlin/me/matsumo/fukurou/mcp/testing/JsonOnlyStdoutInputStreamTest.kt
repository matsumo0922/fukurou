package me.matsumo.fukurou.mcp.testing

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonOnlyStdoutInputStreamTest {

    @Test
    fun readBytesPassesJsonLineThrough() {
        val payload = """{"jsonrpc":"2.0","id":1}""" + "\n"
        val stream = JsonOnlyStdoutInputStream(ByteArrayInputStream(payload.encodeToByteArray()))

        assertContentEquals(
            expected = payload.encodeToByteArray(),
            actual = stream.readBytes(),
        )
    }

    @Test
    fun readBytesRejectsNonJsonLine() {
        val payload = "kotlin-logging: initializing...\n"
        val stream = JsonOnlyStdoutInputStream(ByteArrayInputStream(payload.encodeToByteArray()))

        assertFailsWith<IOException> {
            stream.readBytes()
        }
    }

    @Test
    fun readBytesAcceptsCrLfJsonLine() {
        val payload = """{"jsonrpc":"2.0","id":1}""" + "\r\n"
        val stream = JsonOnlyStdoutInputStream(ByteArrayInputStream(payload.encodeToByteArray()))

        assertContentEquals(
            expected = payload.encodeToByteArray(),
            actual = stream.readBytes(),
        )
    }

    @Test
    fun singleByteReadValidatesJsonLine() {
        val payload = """{"jsonrpc":"2.0","id":1}""" + "\n"
        val stream = JsonOnlyStdoutInputStream(ByteArrayInputStream(payload.encodeToByteArray()))
        val output = mutableListOf<Byte>()

        while (true) {
            val value = stream.read()
            if (value == -1) break

            output += value.toByte()
        }

        assertContentEquals(
            expected = payload.encodeToByteArray(),
            actual = output.toByteArray(),
        )
    }

    @Test
    fun singleByteReadRejectsNonJsonLine() {
        val payload = "not json\n"
        val stream = JsonOnlyStdoutInputStream(ByteArrayInputStream(payload.encodeToByteArray()))

        val thrown = assertFailsWith<IOException> {
            while (true) {
                if (stream.read() == -1) break
            }
        }

        assertEquals("MCP server stdout emitted a non-JSON line.", thrown.message)
    }
}

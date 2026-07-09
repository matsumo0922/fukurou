package me.matsumo.fukurou.mcp.testing

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * MCP server process の raw stdout を、SDK client が読む前に JSON line として検証する stream。
 *
 * stdio transport の stdout は JSON-RPC 専用である。
 * 空行や logger 出力のような非 JSON 行を検出した時点で smoke を失敗させる。
 */
class JsonOnlyStdoutInputStream(
    private val delegate: InputStream,
    private val json: Json = Json,
) : InputStream() {

    private val lineBuffer = ByteArrayOutputStream()

    override fun read(): Int {
        val value = delegate.read()
        if (value == -1) {
            validateEndOfStream()
            return value
        }

        acceptByte(value)

        return value
    }

    override fun read(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val readCount = delegate.read(bytes, offset, length)
        if (readCount == -1) {
            validateEndOfStream()
            return readCount
        }

        for (index in offset until offset + readCount) {
            acceptByte(bytes[index].toInt() and BYTE_MASK)
        }

        return readCount
    }

    override fun close() {
        delegate.close()
    }

    private fun acceptByte(value: Int) {
        when (value) {
            LINE_FEED -> validateLine()
            CARRIAGE_RETURN -> Unit
            else -> lineBuffer.write(value)
        }
    }

    private fun validateLine() {
        val line = lineBuffer.toByteArray().decodeToString()
        lineBuffer.reset()

        try {
            json.parseToJsonElement(line)
        } catch (exception: SerializationException) {
            throw IOException("MCP server stdout emitted a non-JSON line.", exception)
        }
    }

    private fun validateEndOfStream() {
        if (lineBuffer.size() > 0) {
            throw IOException("MCP server stdout ended with an incomplete JSON line.")
        }
    }

    private companion object {

        private const val BYTE_MASK = 0xff
        private const val LINE_FEED = '\n'.code
        private const val CARRIAGE_RETURN = '\r'.code
    }
}

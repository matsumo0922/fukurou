package me.matsumo.fukurou

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * HTTP API の wire JSON 契約。
 *
 * default 値と null を明示し、実応答と OpenAPI の shape を揃える。
 */
@OptIn(ExperimentalSerializationApi::class)
internal val ApiJson = Json {
    encodeDefaults = true
    explicitNulls = true
}

package me.matsumo.fukurou

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi

/** redacted operations monitoring route を定義する。 */
@OptIn(ExperimentalKtorApi::class)
internal fun Route.monitoringRoute(service: MonitoringSnapshotService) {
    get("/ops/monitoring") {
        call.respond(service.snapshot())
    }.describe {
        summary = "運用監視 snapshot を取得する"
        description = "daemon、LLM provider、reconciler、未解決 gap、backup/restore の allowlist 済み snapshot を返します。source の一部が利用できない場合も HTTP 200 と component-local UNKNOWN を返し、readiness や取引判断は変更しません。"
        tag("ops")
        responses {
            HttpStatusCode.OK {
                description = "schemaVersion 1 の redacted monitoring snapshot です。"
                schema = jsonSchema<OpsMonitoringResponse>()
            }
        }
    }
}

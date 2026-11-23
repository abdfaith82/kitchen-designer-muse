package com.kitchendesigner.render

import com.kitchendesigner.model.Project
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * [CloudRenderClient] backed by the render server HTTP API (see
 * `server/openapi.yaml` in this repo):
 *
 *  - `POST /v1/renders`                -> 201 `{"id","status","progress"}`
 *  - `GET  /v1/renders/{id}`           -> 200 full job JSON, 404 for unknown ids
 *  - `POST /v1/renders/{id}/cancel`    -> 200 when the job existed
 *
 * Pure JVM: uses only [java.net.HttpURLConnection] — no Android imports and no
 * JSON library, so it compiles with a standalone `kotlinc`. The scene JSON is
 * hand-built with a [StringBuilder] in the same shapes as the Unity
 * `LoadProject` payload (see `UnityBridgeBase.toJson`), and responses are read
 * with small indexOf-based extractors. That parser is a deliberate
 * no-dependency choice: the API shape is fixed and flat, so pulling in a JSON
 * library just for four string fields is not worth the dependency. It only
 * understands flat `"key":"value"` / `"key":null` fields of that fixed shape.
 *
 * Error policy (never crash the caller):
 *  - On any network/HTTP error, if [stubFallback] is non-null the call is
 *    delegated to it (so the UI keeps working offline / against the stub).
 *  - Otherwise [submit] and [status] return a [RenderJob] with
 *    [RenderJobStatus.FAILED] and a descriptive `errorMessage`, and [cancel]
 *    returns `false`. Nothing in this class throws for I/O reasons.
 *
 * [baseUrl] may have a trailing slash; it is normalised away.
 */
class HttpCloudRenderClient(
    baseUrl: String,
    private val stubFallback: CloudRenderClient? = null,
) : CloudRenderClient {

    private val baseUrl: String = baseUrl.trimEnd('/')

    override fun submit(request: RenderRequest): RenderJob {
        return try {
            val (code, body) = http("POST", "/v1/renders", buildSubmitJson(request))
            if (code !in 200..299) {
                return failureOrFallback("", "submit failed: HTTP $code") { stubFallback?.submit(request) }
            }
            val id = extractString(body, "id")
                ?: return failureOrFallback("", "submit failed: no id in response") { stubFallback?.submit(request) }
            RenderJob(id, parseStatus(extractString(body, "status")),
                errorMessage = extractString(body, "errorMessage"),
                presetName = extractString(body, "preset"),
                resolutionWidth = extractInt(body, "resolutionWidth"),
                samplesPerPixel = extractInt(body, "samplesPerPixel"),
                denoiser = extractBoolean(body, "denoiser"))
        } catch (e: IOException) {
            failureOrFallback("", "submit failed: ${e.message}") { stubFallback?.submit(request) }
        }
    }

    override fun status(jobId: String): RenderJob {
        return try {
            val (code, body) = http("GET", "/v1/renders/$jobId", null)
            when {
                code == 404 -> RenderJob(jobId, RenderJobStatus.FAILED, errorMessage = "unknown job")
                code !in 200..299 ->
                    failureOrFallback(jobId, "status failed: HTTP $code") { stubFallback?.status(jobId) }
                else -> RenderJob(
                    id = extractString(body, "id") ?: jobId,
                    status = parseStatus(extractString(body, "status")),
                    resultUrl = extractString(body, "resultUrl")?.let { resolveUrl(it) },
                    errorMessage = extractString(body, "errorMessage"),
                    // The server echoes the resolved preset: a flat "preset"
                    // name plus the renderPreset metadata block, whose flat
                    // numeric/boolean fields these extractors can read.
                    presetName = extractString(body, "preset"),
                    resolutionWidth = extractInt(body, "resolutionWidth"),
                    samplesPerPixel = extractInt(body, "samplesPerPixel"),
                    denoiser = extractBoolean(body, "denoiser"),
                )
            }
        } catch (e: IOException) {
            failureOrFallback(jobId, "status failed: ${e.message}") { stubFallback?.status(jobId) }
        }
    }

    override fun cancel(jobId: String): Boolean {
        return try {
            val (code, _) = http("POST", "/v1/renders/$jobId/cancel", null)
            if (code == 200) true
            else stubFallback?.cancel(jobId) ?: false
        } catch (e: IOException) {
            stubFallback?.cancel(jobId) ?: false
        }
    }

    // -- HTTP ---------------------------------------------------------------

    private fun http(method: String, path: String, jsonBody: String?): Pair<Int, String> {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            if (jsonBody != null) {
                val bytes = jsonBody.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            return code to body
        } finally {
            conn.disconnect()
        }
    }

    /** Relative result URLs ("/v1/renders/...") are resolved against [baseUrl]. */
    private fun resolveUrl(url: String): String =
        if (url.startsWith("/")) baseUrl + url else url

    private fun failureOrFallback(id: String, message: String, fallback: () -> RenderJob?): RenderJob =
        fallback() ?: RenderJob(id = id, status = RenderJobStatus.FAILED, errorMessage = message)

    // -- Request JSON (hand-built, Unity LoadProject shapes) -----------------

    internal fun buildSubmitJson(request: RenderRequest): String {
        val p: Project = request.project
        val spec = request.effectivePreset
        return buildString {
            append("{\"projectId\":\"").append(escape(p.id)).append("\",")
            append("\"quality\":\"").append(request.quality.name).append("\",")
            append("\"cameraName\":\"").append(escape(request.cameraName)).append("\",")
            append("\"lightingMode\":\"").append(escape(request.lightingMode)).append("\",")
            // Render preset: name + explicit fields, matching the server
            // contract (server/openapi.yaml). The values come from a
            // validated RenderPresetSpec, so the server accepts them; an
            // invalid hand-built payload would be rejected with HTTP 400.
            append("\"preset\":\"").append(spec.name).append("\",")
            append("\"resolutionWidth\":").append(spec.resolutionWidth).append(",")
            append("\"samplesPerPixel\":").append(spec.samplesPerPixel).append(",")
            append("\"denoiser\":").append(spec.denoiser).append(",")
            append("\"scene\":{")
            append("\"projectId\":\"").append(escape(p.id)).append("\",")
            append("\"room\":{\"widthMm\":").append(p.room.widthMm)
            append(",\"depthMm\":").append(p.room.depthMm)
            append(",\"ceilingHeightMm\":").append(p.room.ceilingHeightMm).append("},")
            append("\"units\":[")
            p.units.forEachIndexed { i, u ->
                if (i > 0) append(",")
                append("{\"catalogId\":\"").append(escape(u.unit.catalogId)).append("\",\"xMm\":").append(u.position.xMm)
                append(",\"yMm\":").append(u.position.yMm).append(",\"rotationDeg\":").append(u.rotationDeg)
                append(",\"materialId\":\"").append(escape(u.unit.materialId)).append("\"}")
            }
            append("]}}")
        }
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
    }

    // -- Response parsing (deliberate no-dependency, fixed flat shape) -------

    private fun parseStatus(raw: String?): RenderJobStatus = when (raw) {
        "QUEUED" -> RenderJobStatus.QUEUED
        "RUNNING" -> RenderJobStatus.RUNNING
        "SUCCEEDED" -> RenderJobStatus.SUCCEEDED
        else -> RenderJobStatus.FAILED // "FAILED" and anything unknown
    }

    /**
     * Extracts a flat `"key":"value"` string field, honouring `\"` escapes.
     * Returns null when the key is absent or its value is JSON `null`.
     */
    private fun extractString(json: String, key: String): String? {
        val needle = "\"$key\""
        var idx = json.indexOf(needle)
        if (idx < 0) return null
        idx = json.indexOf(':', idx + needle.length)
        if (idx < 0) return null
        idx++
        while (idx < json.length && json[idx].isWhitespace()) idx++
        if (idx >= json.length) return null
        if (json.startsWith("null", idx)) return null
        if (json[idx] != '"') return null
        idx++
        val out = StringBuilder()
        while (idx < json.length) {
            val c = json[idx]
            when {
                c == '\\' && idx + 1 < json.length -> { out.append(json[idx + 1]); idx += 2 }
                c == '"' -> return out.toString()
                else -> { out.append(c); idx++ }
            }
        }
        return null
    }

    /** Index just past the `:` of the first `"key":` occurrence, or -1. */
    private fun valueStart(json: String, key: String): Int {
        val needle = "\"$key\""
        var idx = json.indexOf(needle)
        if (idx < 0) return -1
        idx = json.indexOf(':', idx + needle.length)
        if (idx < 0) return -1
        idx++
        while (idx < json.length && json[idx].isWhitespace()) idx++
        return idx
    }

    /** Extracts a flat `"key":123` integer field; null when absent/null/non-numeric. */
    private fun extractInt(json: String, key: String): Int? {
        val idx = valueStart(json, key)
        if (idx < 0 || idx >= json.length) return null
        var end = idx
        if (json[end] == '-') end++
        while (end < json.length && json[end].isDigit()) end++
        if (end == idx) return null
        return json.substring(idx, end).toIntOrNull()
    }

    /** Extracts a flat `"key":true/false` boolean field; null when absent/null. */
    private fun extractBoolean(json: String, key: String): Boolean? {
        val idx = valueStart(json, key)
        if (idx < 0) return null
        return when {
            json.startsWith("true", idx) -> true
            json.startsWith("false", idx) -> false
            else -> null
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000
    }
}

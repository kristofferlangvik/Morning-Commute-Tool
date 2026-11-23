package io.github.morningcommutetool.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.OffsetDateTime

/** Entur's open APIs. No key needed; every request carries an ET-Client-Name header. */
object Entur {
    private const val CLIENT = "hobby-morningcommutetool"
    private const val JOURNEY_PLANNER = "https://api.entur.io/journey-planner/v3/graphql"
    private const val GEOCODER = "https://api.entur.io/geocoder/v1/autocomplete"
    private const val VEHICLES = "https://api.entur.io/realtime/v2/vehicles/graphql"

    class ApiException(msg: String) : IOException(msg)

    data class StationHit(val id: String, val name: String, val detail: String, val isRail: Boolean)

    data class Progress(
        /** e.g. "Between Moss and Rygge". */
        val position: String,
        val stopsAway: Int?,
        val latitude: Double?,
        val longitude: Double?,
    )

    // ---------- plumbing ----------

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun http(url: String, body: String?): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("ET-Client-Name", CLIENT)
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiException("Entur returned HTTP $code")
            text
        } finally {
            c.disconnect()
        }
    }

    private suspend fun gql(url: String, query: String, vars: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("query", query.replace('§', '$'))
            put("variables", vars)
        }
        val root = json.parseToJsonElement(http(url, body.toString())).jsonObject
        val errors = root["errors"] as? JsonArray
        if (errors != null && errors.isNotEmpty()) {
            val msg = errors.joinToString("; ") { it.obj?.str("message") ?: "?" }
            throw ApiException("Entur: $msg")
        }
        return root["data"]?.obj ?: throw ApiException("Entur: empty response")
    }

    private val JsonElement.obj: JsonObject? get() = this as? JsonObject
    private fun JsonObject.o(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.a(k: String): List<JsonObject> =
        (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject.str(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.time(k: String): Long? = str(k)?.let {
        try { OffsetDateTime.parse(it).toInstant().toEpochMilli() } catch (e: Exception) { null }
    }

    private fun multilingual(arr: List<JsonObject>): String {
        if (arr.isEmpty()) return ""
        val no = arr.firstOrNull { (it.str("language") ?: "") in setOf("no", "nob", "nor", "nb") }
        return (no ?: arr[0]).str("value") ?: ""
    }

    // ---------- station search ----------

    suspend fun searchStations(text: String): List<StationHit> {
        val q = URLEncoder.encode(text, "UTF-8")
        val root = json.parseToJsonElement(http("$GEOCODER?text=$q&size=20&lang=no&layers=venue", null)).jsonObject
        return root.a("features").mapNotNull { f ->
            val p = f.o("properties") ?: return@mapNotNull null
            val id = p.str("id") ?: return@mapNotNull null
            if (!id.startsWith("NSR:StopPlace:")) return@mapNotNull null
            val cats = (p["category"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val isRail = "railStation" in cats
            val kind = when {
                isRail -> "Train station"
                cats.any { it.contains("metro", true) } -> "Metro"
                cats.any { it.contains("tram", true) } -> "Tram"
                cats.any { it.contains("ferry", true) || it.contains("harbour", true) } -> "Ferry"
                else -> "Bus stop"
            }
            StationHit(id, p.str("name") ?: id, listOfNotNull(kind, p.str("locality")).joinToString(" · "), isRail)
        }.distinctBy { it.id }.sortedByDescending { it.isRail }
    }

    // ---------- departure board ----------

    private const val BOARD_QUERY = """
query(§id: String!) {
  stopPlace(id: §id) {
    estimatedCalls(numberOfDepartures: 40, timeRange: 21600, arrivalDeparture: departures,
                   includeCancelledTrips: true) {
      aimedDepartureTime expectedDepartureTime realtime cancellation date
      quay { publicCode }
      destinationDisplay { frontText }
      serviceJourney { id line { publicCode transportMode } }
      situations { id summary { value language } description { value language } }
    }
  }
}"""

    suspend fun board(stationId: String): Board {
        val data = gql(JOURNEY_PLANNER, BOARD_QUERY, buildJsonObject { put("id", stationId) })
        val sp = data.o("stopPlace") ?: throw ApiException("Station not found")
        val deps = sp.a("estimatedCalls").mapNotNull { parseCall(it) }.sortedBy { it.effective }
        return Board(stationId, deps, System.currentTimeMillis())
    }

    internal fun parseCall(c: JsonObject): Departure? {
        val sj = c.o("serviceJourney") ?: return null
        val sjId = sj.str("id") ?: return null
        val aimed = c.time("aimedDepartureTime") ?: return null
        val line = sj.o("line")
        return Departure(
            serviceJourneyId = sjId,
            date = c.str("date") ?: formatDate(aimed),
            line = line?.str("publicCode") ?: "?",
            mode = line?.str("transportMode") ?: "rail",
            destination = c.o("destinationDisplay")?.str("frontText") ?: "",
            aimed = aimed,
            expected = c.time("expectedDepartureTime"),
            track = c.o("quay")?.str("publicCode"),
            cancelled = c.bool("cancellation"),
            realtime = c.bool("realtime"),
            notices = c.a("situations").mapNotNull { s ->
                val id = s.str("id") ?: return@mapNotNull null
                Notice(id, multilingual(s.a("summary")), multilingual(s.a("description")))
            },
        )
    }

    private fun formatDate(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(OSLO).toLocalDate().toString()

    // ---------- where is one train ----------

    private const val JOURNEY_QUERY = """
query(§id: String!, §date: Date) {
  serviceJourney(id: §id) {
    estimatedCalls(date: §date) {
      quay { name stopPlace { id name } }
      aimedArrivalTime aimedDepartureTime expectedArrivalTime expectedDepartureTime
      actualArrivalTime actualDepartureTime
    }
  }
}"""

    suspend fun progress(serviceJourneyId: String, date: String, stationId: String): Progress {
        val data = gql(JOURNEY_PLANNER, JOURNEY_QUERY, buildJsonObject {
            put("id", serviceJourneyId); put("date", date)
        })
        val calls = data.o("serviceJourney")?.a("estimatedCalls").orEmpty()
        if (calls.isEmpty()) throw ApiException("No stop data for this train")
        val position = describePosition(calls, System.currentTimeMillis())
        val target = calls.indexOfFirst { it.o("quay")?.o("stopPlace")?.str("id") == stationId }
        val last = lastPassed(calls)
        val stopsAway = if (target >= 0 && last.index < target) target - last.index - (if (last.atStop) 0 else 1) else null
        val pos = vehicle(serviceJourneyId)
        return Progress(position, stopsAway?.coerceAtLeast(0), pos?.first, pos?.second)
    }

    private data class Passed(val index: Int, val atStop: Boolean, val estimated: Boolean)

    private fun lastPassed(calls: List<JsonObject>, now: Long = System.currentTimeMillis()): Passed {
        var last = -1
        var atStop = false
        calls.forEachIndexed { i, c ->
            if (c.str("actualDepartureTime") != null) { last = i; atStop = false }
            else if (c.str("actualArrivalTime") != null) { last = i; atStop = true }
        }
        if (last >= 0) return Passed(last, atStop, false)
        calls.forEachIndexed { i, c ->
            val t = c.time("expectedDepartureTime") ?: c.time("aimedDepartureTime")
                ?: c.time("expectedArrivalTime") ?: c.time("aimedArrivalTime")
            if (t != null && t < now) last = i
        }
        return Passed(last, false, true)
    }

    internal fun describePosition(calls: List<JsonObject>, now: Long): String {
        fun name(i: Int) = calls[i].o("quay")?.o("stopPlace")?.str("name") ?: calls[i].o("quay")?.str("name") ?: "?"
        val p = lastPassed(calls, now)
        val prefix = if (p.estimated) "Probably " else ""
        return when {
            p.index < 0 -> "Not yet left ${name(0)}"
            p.index == calls.size - 1 -> "${prefix}arrived at ${name(p.index)}".replaceFirstChar { it.uppercase() }
            p.atStop -> "At ${name(p.index)}"
            else -> "${prefix}between ${name(p.index)} and ${name(p.index + 1)}".replaceFirstChar { it.uppercase() }
        }
    }

    /** GPS position where the operator reports it. */
    private suspend fun vehicle(serviceJourneyId: String): Pair<Double, Double>? = try {
        val q = "query(§id: String!) { vehicles(serviceJourneyId: §id) { location { latitude longitude } } }"
        val data = gql(VEHICLES, q, buildJsonObject { put("id", serviceJourneyId) })
        val loc = data["vehicles"]?.jsonArray?.firstOrNull()?.jsonObject?.o("location")
        val lat = (loc?.get("latitude") as? JsonPrimitive)?.doubleOrNull
        val lon = (loc?.get("longitude") as? JsonPrimitive)?.doubleOrNull
        if (lat != null && lon != null) Pair(lat, lon) else null
    } catch (e: Exception) {
        null
    }
}

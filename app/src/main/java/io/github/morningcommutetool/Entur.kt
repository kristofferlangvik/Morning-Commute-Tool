package io.github.morningcommutetool

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Talks to Entur's open APIs (no key needed, only an ET-Client-Name header). */
object Entur {
    private const val CLIENT = "hobby-morningcommutetool"
    private const val JOURNEY_PLANNER = "https://api.entur.io/journey-planner/v3/graphql"
    private const val GEOCODER = "https://api.entur.io/geocoder/v1/autocomplete"
    private const val VEHICLES = "https://api.entur.io/realtime/v2/vehicles/graphql"
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    open class ApiException(msg: String) : Exception(msg)

    /** The followed train no longer serves the user's stations. */
    class RouteChanged(msg: String) : ApiException(msg)

    data class Stop(val id: String, val name: String, val detail: String)

    data class Departure(
        val serviceJourneyId: String,
        val date: String,
        val line: String,
        val destination: String?,
        val aimedDep: OffsetDateTime,
        val expectedDep: OffsetDateTime?,
        val platform: String?,
        val aimedArr: OffsetDateTime?,
        val expectedArr: OffsetDateTime?,
        val cancelled: Boolean,
    )

    data class Situation(val id: String, val summary: String, val description: String)

    data class JourneyStatus(
        val line: String,
        val fromName: String,
        val toName: String,
        val aimedDep: OffsetDateTime,
        val expectedDep: OffsetDateTime?,
        val actualDep: OffsetDateTime?,
        val platform: String?,
        val aimedArr: OffsetDateTime,
        val expectedArr: OffsetDateTime?,
        val actualArr: OffsetDateTime?,
        val depCancelled: Boolean,
        val arrCancelled: Boolean,
        val position: String,
        val situations: List<Situation>,
    )

    // ---------- HTTP helpers ----------

    private fun http(url: String, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 20000
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
            if (code !in 200..299) throw ApiException("HTTP $code: ${text.take(200)}")
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun gql(url: String, query: String, vars: JSONObject): JSONObject {
        val body = JSONObject().put("query", query.replace('§', '$')).put("variables", vars)
        val json = JSONObject(http(url, body.toString()))
        val errs = json.optJSONArray("errors")
        if (errs != null && errs.length() > 0) {
            val msgs = (0 until errs.length()).joinToString("; ") {
                errs.getJSONObject(it).optString("message")
            }
            throw ApiException("Entur: $msgs")
        }
        return json.optJSONObject("data") ?: throw ApiException("Entur: empty response")
    }

    private fun JSONObject.s(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.o(key: String): JSONObject? = optJSONObject(key)

    private fun multilingual(arr: JSONArray?): String {
        if (arr == null || arr.length() == 0) return ""
        val items = (0 until arr.length()).map { arr.getJSONObject(it) }
        val no = items.firstOrNull { (it.s("language") ?: "") in setOf("no", "nob", "nor", "nb") }
        return (no ?: items[0]).s("value") ?: ""
    }

    // ---------- Station search ----------

    fun searchStops(text: String): List<Stop> {
        val q = URLEncoder.encode(text, "UTF-8")
        val json = JSONObject(http("$GEOCODER?text=$q&size=15&lang=no&layers=venue", null))
        val feats = json.optJSONArray("features") ?: return emptyList()
        val out = mutableListOf<Stop>()
        for (i in 0 until feats.length()) {
            val p = feats.getJSONObject(i).o("properties") ?: continue
            val id = p.s("id") ?: continue
            if (!id.startsWith("NSR:StopPlace:")) continue
            val cats = p.optJSONArray("category")
            val catText = if (cats == null) "" else (0 until cats.length()).joinToString(", ") { cats.getString(it) }
            val detail = listOfNotNull(p.s("locality"), catText.ifBlank { null }).joinToString(" · ")
            out.add(Stop(id, p.s("name") ?: id, detail))
        }
        // Train stations first
        return out.sortedByDescending { it.detail.contains("rail", ignoreCase = true) }
    }

    // ---------- Departures between two stations ----------

    private const val TRIP_QUERY = """
query(§from: String!, §to: String!, §dt: DateTime) {
  trip(from: {place: §from}, to: {place: §to}, dateTime: §dt, numTripPatterns: 10,
       modes: {transportModes: [{transportMode: rail}]}) {
    tripPatterns {
      legs {
        mode
        line { publicCode }
        serviceJourney { id }
        fromEstimatedCall {
          aimedDepartureTime expectedDepartureTime cancellation
          quay { publicCode }
          destinationDisplay { frontText }
        }
        toEstimatedCall { aimedArrivalTime expectedArrivalTime cancellation }
      }
    }
  }
}"""

    /** Direct trains only (one rail leg, no changes). */
    fun departures(fromId: String, toId: String, at: OffsetDateTime): List<Departure> {
        val vars = JSONObject().put("from", fromId).put("to", toId).put("dt", at.format(ISO))
        val data = gql(JOURNEY_PLANNER, TRIP_QUERY, vars)
        val patterns = data.o("trip")?.optJSONArray("tripPatterns") ?: return emptyList()
        val out = LinkedHashMap<String, Departure>()
        for (i in 0 until patterns.length()) {
            val legs = patterns.getJSONObject(i).optJSONArray("legs") ?: continue
            val transit = (0 until legs.length()).map { legs.getJSONObject(it) }
                .filter { it.s("mode") != "foot" }
            if (transit.size != 1 || transit[0].s("mode") != "rail") continue
            val leg = transit[0]
            val sj = leg.o("serviceJourney")?.s("id") ?: continue
            val fc = leg.o("fromEstimatedCall") ?: continue
            val tc = leg.o("toEstimatedCall")
            val aimed = Fmt.parse(fc.s("aimedDepartureTime")) ?: continue
            if (out.containsKey(sj)) continue
            out[sj] = Departure(
                serviceJourneyId = sj,
                date = aimed.atZoneSameInstant(Fmt.OSLO).toLocalDate().toString(),
                line = leg.o("line")?.s("publicCode") ?: "Train",
                destination = fc.o("destinationDisplay")?.s("frontText"),
                aimedDep = aimed,
                expectedDep = Fmt.parse(fc.s("expectedDepartureTime")),
                platform = fc.o("quay")?.s("publicCode"),
                aimedArr = Fmt.parse(tc?.s("aimedArrivalTime")),
                expectedArr = Fmt.parse(tc?.s("expectedArrivalTime")),
                cancelled = fc.optBoolean("cancellation") || (tc?.optBoolean("cancellation") ?: false),
            )
        }
        return out.values.sortedBy { it.aimedDep }
    }

    // ---------- Live status of one train ----------

    private const val JOURNEY_QUERY = """
query(§id: String!, §date: Date) {
  serviceJourney(id: §id) {
    line { publicCode }
    estimatedCalls(date: §date) {
      quay { name publicCode stopPlace { id name } }
      aimedArrivalTime expectedArrivalTime actualArrivalTime
      aimedDepartureTime expectedDepartureTime actualDepartureTime
      cancellation
      situations {
        id
        summary { value language }
        description { value language }
      }
    }
  }
}"""

    fun journeyStatus(sjId: String, date: String, fromId: String, toId: String): JourneyStatus {
        val data = gql(JOURNEY_PLANNER, JOURNEY_QUERY, JSONObject().put("id", sjId).put("date", date))
        val sj = data.o("serviceJourney") ?: throw ApiException("Train not found")
        val arr = sj.optJSONArray("estimatedCalls")
        if (arr == null || arr.length() == 0) throw ApiException("No stop data for this train")
        val calls = (0 until arr.length()).map { arr.getJSONObject(it) }

        fun stopId(c: JSONObject) = c.o("quay")?.o("stopPlace")?.s("id")
        fun stopName(c: JSONObject) = c.o("quay")?.o("stopPlace")?.s("name") ?: c.o("quay")?.s("name") ?: "?"

        val fi = calls.indexOfFirst { stopId(it) == fromId }
        if (fi < 0) throw RouteChanged("This train no longer stops at your departure station.")
        var ti = -1
        for (k in fi + 1 until calls.size) if (stopId(calls[k]) == toId) { ti = k; break }
        if (ti < 0) throw RouteChanged("This train no longer stops at your destination.")

        val from = calls[fi]
        val to = calls[ti]
        val aimedDep = Fmt.parse(from.s("aimedDepartureTime")) ?: throw ApiException("Missing departure time")
        val aimedArr = Fmt.parse(to.s("aimedArrivalTime")) ?: Fmt.parse(to.s("aimedDepartureTime"))
            ?: throw ApiException("Missing arrival time")

        val situations = LinkedHashMap<String, Situation>()
        for (k in fi..ti) {
            val sits = calls[k].optJSONArray("situations") ?: continue
            for (j in 0 until sits.length()) {
                val s = sits.getJSONObject(j)
                val id = s.s("id") ?: continue
                situations.getOrPut(id) {
                    Situation(id, multilingual(s.optJSONArray("summary")), multilingual(s.optJSONArray("description")))
                }
            }
        }

        return JourneyStatus(
            line = sj.o("line")?.s("publicCode") ?: "Train",
            fromName = stopName(from),
            toName = stopName(to),
            aimedDep = aimedDep,
            expectedDep = Fmt.parse(from.s("expectedDepartureTime")),
            actualDep = Fmt.parse(from.s("actualDepartureTime")),
            platform = from.o("quay")?.s("publicCode"),
            aimedArr = aimedArr,
            expectedArr = Fmt.parse(to.s("expectedArrivalTime")),
            actualArr = Fmt.parse(to.s("actualArrivalTime")),
            depCancelled = from.optBoolean("cancellation"),
            arrCancelled = to.optBoolean("cancellation"),
            position = describePosition(calls, ::stopName),
            situations = situations.values.toList(),
        )
    }

    /** Where is the train, based on which stops it has actually passed. */
    private fun describePosition(calls: List<JSONObject>, name: (JSONObject) -> String): String {
        var last = -1
        for (k in calls.indices) {
            val c = calls[k]
            if (!c.isNull("actualDepartureTime") || !c.isNull("actualArrivalTime")) last = k
        }
        if (last >= 0) {
            return when {
                last == calls.size - 1 -> "Arrived at ${name(calls[last])}"
                calls[last].isNull("actualDepartureTime") -> "At ${name(calls[last])}"
                else -> "Between ${name(calls[last])} and ${name(calls[last + 1])}"
            }
        }
        // No reported passings: estimate from expected times
        val now = OffsetDateTime.now()
        var est = -1
        for (k in calls.indices) {
            val c = calls[k]
            val t = Fmt.parse(c.s("expectedDepartureTime")) ?: Fmt.parse(c.s("aimedDepartureTime"))
                ?: Fmt.parse(c.s("expectedArrivalTime")) ?: Fmt.parse(c.s("aimedArrivalTime"))
            if (t != null && t.isBefore(now)) est = k
        }
        return when {
            est < 0 -> "Not yet left ${name(calls[0])}"
            est == calls.size - 1 -> "Probably arrived at ${name(calls[est])}"
            else -> "Probably between ${name(calls[est])} and ${name(calls[est + 1])}"
        }
    }

    // ---------- GPS position (only where the operator reports it) ----------

    fun vehiclePosition(sjId: String): Pair<Double, Double>? = try {
        val q = "query(§id: String!) { vehicles(serviceJourneyId: §id) { location { latitude longitude } } }"
        val data = gql(VEHICLES, q, JSONObject().put("id", sjId))
        val arr = data.optJSONArray("vehicles")
        val loc = if (arr == null || arr.length() == 0) null else arr.getJSONObject(0).o("location")
        if (loc == null) null else Pair(loc.getDouble("latitude"), loc.getDouble("longitude"))
    } catch (e: Exception) {
        null
    }
}

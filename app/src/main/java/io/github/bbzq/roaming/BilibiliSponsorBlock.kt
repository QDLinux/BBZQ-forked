package io.github.bbzq.roaming

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class BilibiliSponsorBlock(
    private val bvid: String,
    private val cid: String,
) {
    fun getSegments(): List<Segment>? {
        return try {
            val prefix = bvid.trim().sha256().take(4)
            val request = Request.Builder()
                .url("$BASE_URL$prefix?category=sponsor")
                .header("origin", "BiliRoaming")
                .header("x-ext-version", "1.7.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body.string()
                if (body.isEmpty()) return null
                parseSegments(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSegments(json: String): List<Segment>? {
        try {
            val jsonArray = JSONArray(json)
            val segments = mutableListOf<Segment>()
            if (jsonArray.length() == 0) return null

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.optString("videoID") != bvid) continue

                val segmentsArray = obj.getJSONArray("segments")
                for (j in 0 until segmentsArray.length()) {
                    val item = segmentsArray.getJSONObject(j)
                    val segmentArray = item.getJSONArray("segment")
                    segments.add(
                        Segment(
                            segment = floatArrayOf(
                                segmentArray.getDouble(0).toFloat(),
                                segmentArray.getDouble(1).toFloat(),
                            ),
                            cid = item.optString("cid"),
                            uuid = item.optString("UUID"),
                            category = item.optString("category"),
                            actionType = item.optString("actionType"),
                            videoDuration = item.optInt("videoDuration"),
                        ),
                    )
                }
                break
            }
            return segments
                .filter { it.cid == cid }
                .sortedBy { it.segment[0] }
        } catch (_: JSONException) {
            return null
        }
    }

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray())
            .joinToString("") { "%02x".format(it) }

    data class Segment(
        val segment: FloatArray,
        val cid: String,
        val uuid: String,
        val category: String,
        val actionType: String,
        val videoDuration: Int,
    )

    private companion object {
        private const val BASE_URL = "https://bsbsb.top/api/skipSegments/"

        private val httpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}

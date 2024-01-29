import com.google.gson.Gson
import dev.dqw4w9wgxcq.pathfinder.commons.TilePathfinder
import dev.dqw4w9wgxcq.pathfinder.commons.domain.Point
import dev.dqw4w9wgxcq.pathfinder.commons.domain.Position
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class RemoteTilePathfinder(private val url: String) : TilePathfinder {
    private val okHttp = OkHttpClient()
    private val gson = Gson()

    override fun findPath(plane: Int, start: Point, end: Point): List<Point>? {
        data class FindPathResponse(val path: MutableList<Point>?)

        val reqJson = gson.toJson(
            mapOf(
                "plane" to plane,
                "start" to start,
                "end" to end
            )
        )

        val request = Request.Builder()
            .url("$url/find-path")
            .post(reqJson.toJsonRequestBody())
            .build()

        val response = okHttp.newCall(request).execute()
            .also { if (!it.isSuccessful) throw IOException("Unexpected code $it") }
            .body!!
            .string()
            .let { gson.fromJson(it, FindPathResponse::class.java) }

        return response.path
    }

    override fun distances(start: Position, ends: MutableSet<Point>): Map<Point, Int> {
        data class Distance(val point: Point, val distance: Int)
        data class FindDistancesResponse(val distances: List<Distance>)

        val reqJson = gson.toJson(
            mapOf(
                "plane" to start.plane,
                "start" to start.toPoint(),
                "ends" to ends
            )
        )

        val request = Request.Builder()
            .url("$url/find-distances")
            .post(reqJson.toJsonRequestBody())
            .build()

        val response = okHttp.newCall(request).execute()
            .also { if (!it.isSuccessful) throw IOException("Unexpected code $it") }
            .body!!
            .string()
            .let { gson.fromJson(it, FindDistancesResponse::class.java) }

        return response.distances.associate { it.point to it.distance }
    }

    override fun isRemote(): Boolean {
        return true
    }
}

private val JSON = "application/json; charset=utf-8".toMediaType()

fun String.toJsonRequestBody(): RequestBody {
    return this.toRequestBody(JSON)
}
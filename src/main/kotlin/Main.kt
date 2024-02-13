import com.google.gson.Gson
import dev.dqw4w9wgxcq.pathfinder.commons.TilePathfinder
import dev.dqw4w9wgxcq.pathfinder.commons.domain.Agent
import dev.dqw4w9wgxcq.pathfinder.commons.domain.Point
import dev.dqw4w9wgxcq.pathfinder.commons.domain.Position
import dev.dqw4w9wgxcq.pathfinder.commons.store.GraphStore
import dev.dqw4w9wgxcq.pathfinder.commons.store.LinkStore
import dev.dqw4w9wgxcq.pathfinder.pathfinding.Pathfinding
import dev.dqw4w9wgxcq.pathfinder.pathfinding.PathfindingResult
import io.javalin.Javalin
import io.javalin.http.HttpStatus
import io.javalin.http.bodyValidator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.exitProcess

const val PATHFINDING_TIMEOUT = 10L//seconds
const val CAPACITY = 10

val DEFAULT_AGENT = Agent(99, null, null)

fun main() {
    println("================Starting server================")

    val log = LoggerFactory.getLogger("main")

    val defaultPort = 8080
    val port = System.getenv("PORT")
        ?.toInt()
        .also {
            if (it == null)
                log.info("PORT env not set, using $defaultPort")
            else
                log.info("PORT: $it")
        }
        ?: defaultPort

    val defaultTileServiceAddress = "http://localhost:8081"
    val tileServiceAddress = System.getenv("TILE_SERVICE_ADDRESS")
        .also {
            if (it == null)
                log.info("TILE_SERVICE_ADDRESS env not set, using '${defaultTileServiceAddress}'")
            else
                log.info("TILE_SERVICE_ADDRESS: $it")
        }
        ?: defaultTileServiceAddress

    val linksZip = File("links.zip")
    if (!linksZip.exists()) {
        log.error("links.zip not found")
        exitProcess(1)
    }

    val graphZip = File("graph.zip")
    if (!graphZip.exists()) {
        log.error("graph.zip not found")
        exitProcess(1)
    }

    val linkStore = LinkStore.load(linksZip)
    val graphStore = GraphStore.load(graphZip, linkStore.links)

    System.gc()

    val tilePathfinder = RemoteTilePathfinder(tileServiceAddress)
    val pathfinding = Pathfinding.create(graphStore, tilePathfinder)

    //cached thread pool instead of fixed thread pool because we may have more jobs than threads in the event of a race
    val exe = Executors.newCachedThreadPool() as ThreadPoolExecutor

    Javalin
        .create { config ->
            config.plugins.enableCors { it ->
                it.add {
                    it.reflectClientOrigin = true
                    it.maxAge = 86400
                }
            }
        }
        .get("/") { it.result("Hello World") }
        .post("/find-path") { ctx ->
            data class Req(val start: Position, val finish: Position, val agent: Agent?)
            data class Res(val time: Long, val result: PathfindingResult)

            val req = ctx.bodyValidator<Req>().get()

            if (exe.activeCount >= CAPACITY) {
                ctx.status(HttpStatus.SERVICE_UNAVAILABLE)
                ctx.header("Retry-After", "5")
                ctx.result("server at capacity, try again in a few seconds")
                return@post
            }

            val job = exe.submit<Pair<PathfindingResult, Long>> {
                val startTime = System.currentTimeMillis()
                val result = pathfinding.findPath(
                    req.start,
                    req.finish,
                    req.agent ?: DEFAULT_AGENT
                )
                Pair(result, System.currentTimeMillis() - startTime)
            }

            val (result, time) = try {
                job.get(PATHFINDING_TIMEOUT, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                log.info("pathfinding timed out for request: $req")
                ctx.status(HttpStatus.SERVICE_UNAVAILABLE)
                ctx.header("Retry-After", "120")
                ctx.result("pathfinding timed out (after ${PATHFINDING_TIMEOUT}s)")
                return@post
            }

            ctx.json(Res(time, result))
        }
        .get("/stats") { ctx ->
            data class Res(val activeCount: Int)
            ctx.json(Res(exe.activeCount))
        }
        .start(port)
}

class RemoteTilePathfinder(private val url: String) : TilePathfinder {
    private val okHttp = OkHttpClient()

    override fun findPath(plane: Int, start: Point, end: Point): List<Point>? {
        data class FindPathResponse(val path: MutableList<Point>?)

        val request = Request.Builder()
            .url("$url/find-path")
            .post(
                mapOf(
                    "plane" to plane,
                    "start" to start,
                    "end" to end
                ).toJsonRequestBody()
            )
            .build()

        val response = okHttp.newCall(request)
            .execute()
            .also { if (!it.isSuccessful) throw IOException("Unexpected code $it") }
            .body!!
            .string()
            .let { GSON.fromJson(it, FindPathResponse::class.java) }

        return response.path
    }

    override fun distances(from: Position, tos: MutableSet<Point>): Map<Point, Int> {
        data class Distance(val point: Point, val distance: Int)
        data class FindDistancesResponse(val distances: List<Distance>)

        val request = Request.Builder()
            .url("$url/find-distances")
            .post(
                mapOf(
                    "plane" to from.plane,
                    "start" to from.toPoint(),
                    "ends" to tos
                ).toJsonRequestBody()
            )
            .build()

        val response = okHttp.newCall(request)
            .execute()
            .also { if (!it.isSuccessful) throw IOException("Unexpected code $it") }
            .body!!
            .string()
            .let { GSON.fromJson(it, FindDistancesResponse::class.java) }

        return response.distances.associate { it.point to it.distance }
    }

    override fun isRemote(): Boolean {
        return true
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val GSON = Gson()

        fun Map<String, Any?>.toJsonRequestBody(): RequestBody {
            return GSON.toJson(this).toJsonRequestBody()
        }

        fun String.toJsonRequestBody(): RequestBody {
            return this.toRequestBody(JSON_MEDIA_TYPE)
        }
    }
}

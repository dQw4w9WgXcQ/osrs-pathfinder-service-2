import dev.dqw4w9wgxcq.pathfinder.commons.domain.Agent
import dev.dqw4w9wgxcq.pathfinder.commons.domain.Position
import dev.dqw4w9wgxcq.pathfinder.commons.store.GraphStore
import dev.dqw4w9wgxcq.pathfinder.commons.store.LinkStore
import dev.dqw4w9wgxcq.pathfinder.pathfinding.Pathfinding
import dev.dqw4w9wgxcq.pathfinder.pathfinding.PathfindingResult
import io.javalin.Javalin
import io.javalin.http.HttpStatus
import io.javalin.http.bodyValidator
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.exitProcess

const val PATHFINDING_TIMEOUT = 10L//seconds
const val CAPACITY = 10
val DEFAULT_AGENT = Agent(99, null, null)

data class FindPathRequest(val start: Position, val finish: Position, val agent: Agent?)
data class FindPathResponse(val time: Long, val result: PathfindingResult)

class PathfindingTimeoutException(cause: TimeoutException) : RuntimeException(cause)

private val log = LoggerFactory.getLogger("MainKt")

fun main() {
    println("================Starting server================")

    val port = System.getenv("PORT")?.toInt()
    log.info("PORT: $port")
    val tileServiceAddr = System.getenv("TILE_SERVICE")
        ?: throw IllegalArgumentException("TILE_SERVICE env not set")
    log.info("TILE_SERVICE: $tileServiceAddr")

    val linkFile = File("links.zip")
    if (!linkFile.exists()) {
        println("links.zip not found")
        exitProcess(1)
    }

    val graphFile = File("graph.zip")
    if (!graphFile.exists()) {
        println("graph.zip not found")
        exitProcess(1)
    }

    val linkStore = LinkStore.load(linkFile)
    val graphStore = GraphStore.load(graphFile, linkStore.links)

    System.gc()

    val tilePathfinder = RemoteTilePathfinder("http://$tileServiceAddr")
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
            val req = ctx.bodyValidator<FindPathRequest>().get()

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
                throw PathfindingTimeoutException(e)
            }

            ctx.json(FindPathResponse(time, result))
        }
        .exception(PathfindingTimeoutException::class.java) { _, ctx ->
            ctx.status(HttpStatus.SERVICE_UNAVAILABLE)
            ctx.header("Retry-After", "120")
            ctx.result("pathfinding timed out (after ${PATHFINDING_TIMEOUT}s)")
        }
        .start(port ?: 8080)
}

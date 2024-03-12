import dev.dqw4w9wgxcq.pathfinder.commons.domain.Agent
import dev.dqw4w9wgxcq.pathfinder.commons.domain.Position
import dev.dqw4w9wgxcq.pathfinder.commons.domain.pathfinding.PathfinderResult
import dev.dqw4w9wgxcq.pathfinder.commons.store.GraphStore
import dev.dqw4w9wgxcq.pathfinder.commons.store.LinkStore
import dev.dqw4w9wgxcq.pathfinder.pathfinder.Algo
import dev.dqw4w9wgxcq.pathfinder.pathfinder.Pathfinder
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

const val DEFAULT_TILE_SERVICE_ADDRESS = "http://localhost:8081"
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

    val tileServiceAddress = System.getenv("TILE_SERVICE_ADDRESS")
        .also {
            if (it == null)
                log.info("TILE_SERVICE_ADDRESS env not set, using '${DEFAULT_TILE_SERVICE_ADDRESS}'")
            else
                log.info("TILE_SERVICE_ADDRESS: $it")
        }
        ?: DEFAULT_TILE_SERVICE_ADDRESS

    val redisHost = System.getenv("REDIS_HOST")
        .also {
            if (it == null)
                log.info("REDIS_HOST env not set, using 'localhost'")
            else
                log.info("REDIS_HOST: $it")
        }
        ?: "localhost"

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

    val pathfinding = Pathfinder(graphStore, tileServiceAddress, redisHost, 6379)

    //cached thread pool instead of fixed thread pool because we may have more jobs than threads in the event of a race
    val exe = Executors.newCachedThreadPool() as ThreadPoolExecutor

    Javalin
        .create { config ->
            config.plugins.enableCors { it ->
                it.add {
                    it.reflectClientOrigin = true
                    it.maxAge = 7200
                }
            }
        }
        .get("/") { it.result("Hello World") }
        .post("/find-path") { ctx ->
            data class Req(val start: Position, val end: Position, val agent: Agent?, val algo: Algo?)
            data class Res(val time: Long, val result: PathfinderResult)

            val req = ctx.bodyValidator<Req>().get()

            log.info("start: ${req.start}, end: ${req.end}")

            if (exe.activeCount >= CAPACITY) {
                ctx.status(HttpStatus.SERVICE_UNAVAILABLE)
                ctx.header("Retry-After", "5")
                ctx.result("server at capacity, try again in a few seconds")
                return@post
            }

            val job = exe.submit<Pair<PathfinderResult, Long>> {
                val startTime = System.currentTimeMillis()
                val result = pathfinding.findPath(
                    req.start,
                    req.end,
                    req.agent ?: DEFAULT_AGENT,
                    req.algo ?: Algo.A_STAR
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

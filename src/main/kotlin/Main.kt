import dev.dqw4w9wgxcq.pathfinder.commons.domain.Agent
import dev.dqw4w9wgxcq.pathfinder.commons.domain.Position
import dev.dqw4w9wgxcq.pathfinder.commons.domain.step.Step
import dev.dqw4w9wgxcq.pathfinder.commons.store.GraphStore
import dev.dqw4w9wgxcq.pathfinder.commons.store.LinkStore
import dev.dqw4w9wgxcq.pathfinder.pathfinding.Pathfinding
import dev.dqw4w9wgxcq.pathfinder.pathfinding.PathfindingResult
import io.javalin.Javalin
import io.javalin.community.ssl.SSLPlugin
import io.javalin.http.HttpStatus
import io.javalin.http.bodyValidator
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.HelpFormatter
import org.apache.commons.cli.Option
import org.apache.commons.cli.Options
import org.apache.commons.cli.ParseException
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.exitProcess

const val PATHFINDING_TIMEOUT = 10L//seconds
const val CAPACITY = 10
val DEFAULT_AGENT = Agent(99, null, null)

enum class PathfindingResultType {
    SUCCESS,
    UNREACHABLE,
    BLOCKED
}

data class PathRequest(val start: Position, val finish: Position, val agent: Agent?)
data class PathResponse(
    val type: PathfindingResultType,
    val time: Long,
    val start: Position?,
    val finish: Position?,
    val steps: List<Step>?,
)

class PathfindingTimeoutException : RuntimeException()

private val log = LoggerFactory.getLogger("MainKt")

fun main(args: Array<String>) {
    println("================Starting server================")

    val certPathOpt = Option("c", "certDir", true, "Path to dir containing fullchain.pem and privkey.pem")
    val options = Options().addOption(certPathOpt)
    val cmd = try {
        DefaultParser().parse(options, args)
    } catch (e: ParseException) {
        log.error("failed to parse args", e)
        HelpFormatter().printHelp("asdf", options)
        exitProcess(1)
    }

    val certDir = cmd.getOptionValue(certPathOpt)
    log.info("certDir: $certDir")

    val javalin = Javalin.create { config ->
        if (certDir != null) {
            config.plugins.register(SSLPlugin {
                it.pemFromPath(
                    "$certDir/fullchain.pem",
                    "$certDir/privkey.pem"
                )
                it.insecurePort = 8080
            })
        }

        config.plugins.enableCors { it ->
            it.add {
                it.reflectClientOrigin = true
                it.maxAge = 86400
            }
        }
    }

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
    val links = linkStore.links
    val graphStore = GraphStore.load(graphFile, links)
    val tilePathfinder = RemoteTilePathfinder("http://localhost:3000")
    val pathfinding = Pathfinding.create(graphStore, tilePathfinder)
    //cached thread pool instead of fixed thread pool because we may have more jobs than threads in the event of a race
    val exe = Executors.newCachedThreadPool() as ThreadPoolExecutor

    javalin
        .get("/") {
            it.result("Hello World")
        }
        .post("/find-path") { ctx ->
            log.info("ip:${ctx.ip()} xForwardedFor:${ctx.header("X-Forwarded-For")} userAgent:${ctx.userAgent()} body:\n${ctx.body()}")

            //todo: currently deserializes incorrectly if a nested primitive field is missing (finish.y will be 0)
            // {
            //    "start": {
            //        "x": 3200,
            //        "y": 3200,
            //        "plane": 0
            //    },
            //    "finish": {
            //        "x": 3201,
            //        "plane": 0
            //    }
            // }
            val req = ctx.bodyValidator<PathRequest>().get()

            if (exe.activeCount >= CAPACITY) {
                ctx.status(HttpStatus.SERVICE_UNAVAILABLE)
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
                throw PathfindingTimeoutException()
            } catch (e: ExecutionException) {
                log.error("pathfinding failed", e)
                throw e
            }

            //todo embed response instead of using DTO
            val response = when (result) {
                is PathfindingResult.Success -> PathResponse(
                    PathfindingResultType.SUCCESS,
                    time,
                    result.start,
                    result.finish,
                    result.steps
                )

                is PathfindingResult.Unreachable -> PathResponse(
                    PathfindingResultType.UNREACHABLE,
                    time,
                    result.start,
                    result.finish,
                    null
                )

                is PathfindingResult.Blocked -> PathResponse(
                    PathfindingResultType.BLOCKED,
                    time,
                    result.start,
                    result.finish,
                    null
                )
            }

            ctx.json(response)
        }
        .exception(PathfindingTimeoutException::class.java) { _, ctx ->
            ctx.status(HttpStatus.SERVICE_UNAVAILABLE)
            ctx.result("pathfinding timed out (after ${PATHFINDING_TIMEOUT}s)")
        }
        .start()//if using ssl plugin, port will be ignored and will listen on 80/443 (by defualt)
}

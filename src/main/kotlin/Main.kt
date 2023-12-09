import dev.dqw4w9wgxcq.pathfinder.commons.domain.Agent
import dev.dqw4w9wgxcq.pathfinder.commons.domain.Position
import dev.dqw4w9wgxcq.pathfinder.pathfinding.Pathfinding
import dev.dqw4w9wgxcq.pathfinder.pathfinding.PathfindingResult
import dev.dqw4w9wgxcq.pathfinder.pathfinding.store.GraphStore
import dev.dqw4w9wgxcq.pathfinder.pathfinding.store.LinkStore
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
import java.io.FileInputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.exitProcess

const val PATHFINDING_TIMEOUT = 10L//seconds
val DEFAULT_AGENT = Agent(99, null, null)

data class PathReq(val start: Position, val finish: Position, val agent: Agent?)
class PathfindingTimeoutException(req: PathReq) : RuntimeException("pathfinding timed out for request: $req")

object Main {
    private val log = LoggerFactory.getLogger(this::class.java)

    @JvmStatic
    fun main(args: Array<String>) {
        println("================Starting server================")

        val certPathOpt = Option("c", "certDir", true, "Path to dir containing fullchain.pem and privkey.pem")
        val options = Options().addOption(certPathOpt)
        val cmd = try {
            DefaultParser().parse(options, args)
        } catch (e: ParseException) {
            log.debug("failed to parse args", e)
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
                it.add { it.anyHost() }
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

        val links = LinkStore.load(FileInputStream(linkFile)).links
        val graphStore = GraphStore.load(FileInputStream(graphFile), links)
        val pathfinding = Pathfinding.create(graphStore)
        val exe = Executors.newFixedThreadPool(10)

        javalin
            .get("/") {
                it.result("Hello World")
            }
            .post("/request-path") { ctx ->
                log.info("${ctx.ip()} - ${ctx.header("X-Forwarded-For")} - ${ctx.userAgent()}\n${ctx.body()}")

                ctx.header("Access-Control-Max-Age", "600")

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
                val req = ctx.bodyValidator<PathReq>().get()

                val job = exe.submit<PathfindingResult> {
                    pathfinding.findPath(
                        req.start,
                        req.finish,
                        req.agent ?: DEFAULT_AGENT
                    )
                }

                val result = try {
                    job.get(PATHFINDING_TIMEOUT, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    log.error("pathfinding timed out", e)
                    throw PathfindingTimeoutException(req)
                } catch (e: ExecutionException) {
                    log.error("pathfinding failed", e)
                    throw e
                }

                ctx.json(result)
            }
            .exception(PathfindingTimeoutException::class.java) { _, ctx ->
                ctx.status(HttpStatus.SERVICE_UNAVAILABLE)
                ctx.result("pathfinding timed out (after ${PATHFINDING_TIMEOUT}s)")
            }
            .start()//if using ssl plugin, port will be ignored and will listen on 80/443 (by defualt)
    }
}
import dev.dqw4w9wgxcq.pathfinder.commons.domain.Agent
import dev.dqw4w9wgxcq.pathfinder.commons.domain.Position
import dev.dqw4w9wgxcq.pathfinder.pathfinding.Pathfinding
import dev.dqw4w9wgxcq.pathfinder.pathfinding.PathfindingResult
import dev.dqw4w9wgxcq.pathfinder.pathfinding.store.GraphStore
import dev.dqw4w9wgxcq.pathfinder.pathfinding.store.LinkStore
import io.javalin.Javalin
import io.javalin.community.ssl.SSLPlugin
import io.javalin.http.bodyValidator
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.HelpFormatter
import org.apache.commons.cli.Option
import org.apache.commons.cli.Options
import org.apache.commons.cli.ParseException
import java.io.File
import java.io.FileInputStream
import java.util.Date
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import kotlin.system.exitProcess

const val PATHFINDING_TIMEOUT = 10L//seconds
val DEFAULT_AGENT = Agent(99, null, null)

data class PathReq(val start: Position, val finish: Position, val agent: Agent?)
class PathfindingTimeoutException(req: PathReq) :
    RuntimeException("pathfinding timed out for request: $req")

fun main(args: Array<String>) {
    println("================Starting server================")

    val certPathOpt = Option("c", "certDir", true, "Path to dir containing fullchain.pem and privkey.pem")
    val options = Options().addOption(certPathOpt)
    val cmd = try {
        DefaultParser().parse(options, args)
    } catch (e: ParseException) {
        println(e.message)
        HelpFormatter().printHelp("asdf", options)
        exitProcess(1)
    }

    val certDir = cmd.getOptionValue("c")
    println("certDir: $certDir")

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
    val exe = Executors.newFixedThreadPool(4)//4 vcpu vps

    javalin
        .get("/") {
            it.result("Hello World")
        }
        .post("/request-path") { ctx ->
            println(Date().toString() + " - " + ctx.ip() + " " + ctx.userAgent() + " " + ctx.header("X-Forwarded-For") + "\n" + ctx.body())

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
                job.get(PATHFINDING_TIMEOUT, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                e.printStackTrace()
                throw PathfindingTimeoutException(req)
            } catch (e: ExecutionException) {
                println("something went wrong")
                throw e
            }

            ctx.json(result)
        }
        .exception(PathfindingTimeoutException::class.java) { _, ctx ->
            ctx.status(408)
            ctx.result("pathfinding timed out (after ${PATHFINDING_TIMEOUT}s)")
        }
        .start()//if using ssl plugin, port will be ignored and will listen on 80/443 (by defualt)
}
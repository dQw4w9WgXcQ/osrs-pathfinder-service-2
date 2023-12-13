package dev.dqw4w9wgxcq

import PathRequest
import PathfindingResultType
import com.google.gson.GsonBuilder
import dev.dqw4w9wgxcq.pathfinder.commons.domain.Position
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.Executors

data class PathResponse2(
    val type: PathfindingResultType,
    val time: Long,
    val start: Position?,
    val finish: Position?,
)

fun main() {
    val exe = Executors.newCachedThreadPool()

    val http = OkHttpClient.Builder().build()
    val gson = GsonBuilder().create()

    listOf(
        PathRequest(Position(3200, 3200, 0), Position(3201, 3201, 0), null),
        PathRequest(Position(3200, 3200, 0), Position(3201, 3201, 0), null),
        PathRequest(Position(3200, 3200, 0), Position(3201, 3201, 0), null),
        PathRequest(Position(3200, 3200, 0), Position(3201, 3201, 0), null),
        PathRequest(Position(3200, 3200, 0), Position(3201, 3201, 0), null),
        PathRequest(Position(3200, 3200, 0), Position(3201, 3201, 0), null),
        PathRequest(Position(3200, 3200, 0), Position(3201, 3201, 0), null),
    )
        .map {
            exe.submit {
                val reqBody = gson.toJson(it)
                    .toRequestBody("application/json".toMediaType())

                val req = Request.Builder()
                    .url("http://localhost:8080/request-path")
                    .post(reqBody)
                    .build()

                val resp = http.newCall(req).execute()

                val json = resp.body?.string()

                val pathResp = gson.fromJson(json, PathResponse2::class.java)
                println(pathResp.time)
            }
        }

    exe.shutdown()
}
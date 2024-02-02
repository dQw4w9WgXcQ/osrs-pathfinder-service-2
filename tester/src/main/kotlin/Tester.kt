package dev.dqw4w9wgxcq

import FindPathRequest
import com.google.gson.GsonBuilder
import dev.dqw4w9wgxcq.pathfinder.commons.domain.Position
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.Callable
import java.util.concurrent.Executors

fun main() {
    val exe = Executors.newCachedThreadPool()

    val http = OkHttpClient.Builder().build()
    val gson = GsonBuilder().create()

    listOf(
        FindPathRequest(Position(3200, 3200, 0), Position(3201, 3201, 0), null),
        FindPathRequest(Position(3200, 3200, 0), Position(3201, 3201, 0), null),
        FindPathRequest(Position(3200, 3200, 0), Position(3201, 3201, 0), null),
        FindPathRequest(Position(3200, 3200, 0), Position(3201, 3201, 0), null),
        FindPathRequest(Position(3200, 3200, 0), Position(3201, 3201, 0), null),
        FindPathRequest(Position(3200, 3200, 0), Position(3201, 3201, 0), null),
        FindPathRequest(Position(3200, 3200, 0), Position(3201, 3201, 0), null),
    )
        .map {
            exe.submit(Callable {
                val reqBody = gson.toJson(it)
                    .toRequestBody("application/json".toMediaType())

                val req = Request.Builder()
                    .url("http://localhost:8080/request-path")
                    .post(reqBody)
                    .build()

                val resp = http.newCall(req).execute()

                val json = resp.body?.string()

                return@Callable json
            })
        }
        .map {
            try {
                it.get()
            } catch (e: Exception) {
                e
            }
        }
        .forEach { println(it) }

    exe.shutdown()
}
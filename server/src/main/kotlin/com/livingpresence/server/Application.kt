package com.livingpresence.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.seconds

/** The route the apps call. Mirrored by `SonioxKeyProvider` in `composeApp`. */
const val TEMPORARY_KEY_PATH = "/v1/soniox/temporary-key"

/** A refusal the client can render or retry against. Never carries provider detail. */
@Serializable
data class ErrorResponse(val error: String)

fun main() {
    val config = ServerConfig.fromEnvironment()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(config)
    }.start(wait = true)
}

fun Application.module(
    config: ServerConfig,
    httpClient: HttpClient = HttpClient(CIO),
    authorizer: Authorizer = Authorizer.Open,
) {
    val tokens = SonioxTokenService(httpClient, config)
    val logger = log

    install(ContentNegotiation) { json() }

    install(CallLogging) {
        level = Level.INFO
        // The default format logs the full URI. This service has no query string
        // worth recording and one that would be worth *not* recording if it ever
        // grew one, so the line is reduced to method, path and status.
        format { call ->
            "${call.request.local.method.value} ${call.request.local.uri} -> ${call.response.status()}"
        }
    }

    if (config.allowedOrigins.isNotEmpty()) {
        install(CORS) {
            config.allowedOrigins.forEach { origin ->
                val scheme = origin.substringBefore("://", missingDelimiterValue = "https")
                allowHost(origin.substringAfter("://"), schemes = listOf(scheme))
            }
            allowMethod(HttpMethod.Post)
            allowHeader(HttpHeaders.ContentType)
        }
    }

    install(RateLimit) {
        register(RateLimitName(TEMPORARY_KEY_LIMIT)) {
            rateLimiter(
                limit = config.rateLimit,
                refillPeriod = config.rateLimitRefillSeconds.seconds,
            )
            requestKey { call -> call.clientKey() }
        }
    }

    install(StatusPages) {
        exception<SonioxException> { call, cause ->
            // Soniox's own status is not the client's status: a rejected *server*
            // key is a 401 from Soniox and a 502 to the app, because the app did
            // nothing wrong and retrying with different input cannot help.
            logger.error("Minting a temporary key failed: ${cause.message}")
            call.respond(
                HttpStatusCode.BadGateway,
                ErrorResponse("Could not obtain a transcription key right now."),
            )
        }
        exception<Throwable> { call, cause ->
            logger.error("Unhandled failure on ${call.request.local.uri}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Unexpected server error."),
            )
        }
    }

    routing {
        // Unlimited and unauthenticated on purpose: Fly's health check calls it.
        get("/health") { call.respond(HttpStatusCode.OK, mapOf("status" to "ok")) }

        rateLimit(RateLimitName(TEMPORARY_KEY_LIMIT)) {
            post(TEMPORARY_KEY_PATH) {
                val decision = authorizer.authorize(call)
                if (decision is AuthorizationDecision.Denied) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(decision.reason))
                    return@post
                }
                call.respond(HttpStatusCode.Created, tokens.mint())
            }
        }
    }
}

private const val TEMPORARY_KEY_LIMIT = "temporary-key"

/**
 * The identity the rate limiter counts against.
 *
 * Behind Fly's proxy every request arrives from the same edge address, so keying
 * on `remoteHost` would collapse all users into one bucket and turn the limiter
 * into a global cap that the first busy viewer exhausts for everyone. `Fly-Client-IP`
 * is set by the proxy itself and cannot be spoofed by the caller; the
 * `X-Forwarded-For` fallback exists for running behind something else, and is only
 * trusted because nothing in front of this service accepts a client-supplied one.
 */
internal fun ApplicationCall.clientKey(): String =
    request.headers["Fly-Client-IP"]
        ?: request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.origin.remoteHost

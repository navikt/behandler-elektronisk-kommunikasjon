package no.nav.syfo

import com.auth0.jwk.JwkProviderBuilder
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.application.Application
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.auth.Authentication
import io.ktor.auth.authenticate
import io.ktor.auth.jwt.JWTPrincipal
import io.ktor.auth.jwt.jwt
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpStatusCode.Companion.BadRequest
import io.ktor.jackson.jackson
import io.ktor.metrics.micrometer.MicrometerMetrics
import io.ktor.request.header
import io.ktor.response.respond
import io.ktor.routing.Route
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.KtorExperimentalAPI
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.logging.LogbackMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.prometheus.client.CollectorRegistry
import java.net.URL
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import no.nav.syfo.api.registerBehandlerApi
import no.nav.syfo.db.Database
import no.nav.syfo.db.DatabaseInterface
import no.nav.syfo.services.ElektroniskAbonomentService
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val objectMapper: ObjectMapper = ObjectMapper().apply {
    registerKotlinModule()
    registerModule(JavaTimeModule())
    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
}

const val NAV_CALLID = "Nav-CallId"

data class ApplicationState(var running: Boolean = true, var ready: Boolean = true)

val log: Logger = LoggerFactory.getLogger("no.nav.syfo.behandlerElektroniskKommunikasjon")

@KtorExperimentalAPI
fun main() {
    val environment = Environment()
    val vaultSecrets = objectMapper.readValue<VaultCredentials>(Paths.get("/var/run/secrets/nais.io/vault/credentials.json").toFile())

    val applicationState = ApplicationState()

    val authorizedUsers = listOf(
        environment.syfosmmottakClientId
    )

    val database = Database(environment, vaultSecrets)

    val behandlerService = ElektroniskAbonomentService(database)

    val applicationServer = embeddedServer(Netty, 8080) {
        // errorHandling()
        // callLogging()
        // setupAuth(environment, authorizedUsers)
        // setupContentNegotiation()
        // setupMetrics()
        initRouting(applicationState, behandlerService, database)

        applicationState.ready = true
    }.start(wait = true)

    Runtime.getRuntime().addShutdownHook(Thread {
        applicationState.ready = false
        applicationServer.stop(10, 10, TimeUnit.SECONDS)
    })
}

@KtorExperimentalAPI
fun Application.initRouting(
    applicationState: ApplicationState,
    elektroniskAbonomentService: ElektroniskAbonomentService,
    database: DatabaseInterface
) {
    routing {
            registerNaisApi(readynessCheck = { applicationState.ready }, livenessCheck = { applicationState.running })
            route("/api") {
                enforceCallId()
                authenticate {
                    registerBehandlerApi(elektroniskAbonomentService, database)
                }
            }
    }
}

private fun Application.setupMetrics() {
    install(MicrometerMetrics) {
        registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT, CollectorRegistry.defaultRegistry, Clock.SYSTEM)
        meterBinders = listOf(
            ClassLoaderMetrics(),
            JvmMemoryMetrics(),
            JvmGcMetrics(),
            ProcessorMetrics(),
            JvmThreadMetrics(),
            LogbackMetrics()
        )
    }
}

fun Application.errorHandling() {
    install(StatusPages) {
        exception<Throwable> { cause ->
            call.respond(HttpStatusCode.InternalServerError, cause.message ?: "Unknown error")

            log.error("Caught exception while trying to validate against rules", cause)
            throw cause
        }
    }
}

fun Route.enforceCallId() {
    intercept(ApplicationCallPipeline.Setup) {
        if (call.request.header(NAV_CALLID).isNullOrBlank()) {
            call.respond(BadRequest, "Mangler header `$NAV_CALLID`")
            log.warn("Mottatt kall som mangler callId: ${call.request.local.uri}")
            return@intercept finish()
        }
    }
}

fun Application.callLogging() {
    install(CallLogging) {
        mdc(NAV_CALLID) { it.request.header(NAV_CALLID) }
    }
}

fun Application.setupContentNegotiation() {
    install(ContentNegotiation) {
        jackson {
            registerKotlinModule()
            registerModule(JavaTimeModule())
            configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        }
    }
}

fun Application.setupAuth(environment: Environment, authorizedUsers: List<String>) {
    install(Authentication) {
        jwt {
            verifier(
                JwkProviderBuilder(URL(environment.jwkKeysUrl))
                    .cached(10, 24, TimeUnit.HOURS)
                    .rateLimited(10, 1, TimeUnit.MINUTES)
                    .build(), environment.jwtIssuer
            )
            realm = "behandler-elektronisk-kommunikasjon"
            validate { credentials ->
                val appid: String = credentials.payload.getClaim("appid").asString()
                log.info("authorization attempt for $appid")
                if (appid in authorizedUsers && credentials.payload.audience.contains(environment.clientId)) {
                    log.info("authorization ok")
                    return@validate JWTPrincipal(credentials.payload)
                }
                log.info("authorization failed")
                return@validate null
            }
        }
    }
}

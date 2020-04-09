package no.nav.syfo.application

import com.auth0.jwk.JwkProvider
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.auth.authenticate
import io.ktor.features.CallId
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.jackson.jackson
import io.ktor.response.respond
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.util.UUID
import no.nav.syfo.Environment
import no.nav.syfo.aksessering.api.registerBehandlerApi
import no.nav.syfo.application.api.registerNaisApi
import no.nav.syfo.application.authentication.setupAuth
import no.nav.syfo.log
import no.nav.syfo.services.PartnerInformasjonService

fun createApplicationEngine(
    env: Environment,
    applicationState: ApplicationState,
    jwkProvider: JwkProvider,
    partnerInformasjonService: PartnerInformasjonService
): ApplicationEngine =
        embeddedServer(Netty, env.applicationPort) {
            setupAuth(env, jwkProvider)
            routing {
                registerNaisApi(applicationState)
                route("/api") {
                    authenticate {
                        registerBehandlerApi(partnerInformasjonService)
                    }
                }
            }
            install(ContentNegotiation) {
                jackson {
                    registerKotlinModule()
                    registerModule(JavaTimeModule())
                    configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                }
            }
            install(CallId) {
                generate { UUID.randomUUID().toString() }
                verify { callId: String -> callId.isNotEmpty() }
                header(HttpHeaders.XCorrelationId)
            }
            install(StatusPages) {
                exception<Throwable> { cause ->
                    call.respond(HttpStatusCode.InternalServerError, cause.message ?: "Unknown error")

                    log.error("Caught exception", cause)
                    throw cause
                }
            }
        }

package com.egm.stellio.search.service

import com.egm.stellio.search.config.EntityServiceProperties
import com.egm.stellio.shared.model.JsonLdEntity
import com.egm.stellio.shared.util.JsonLdUtils
import org.springframework.http.codec.ClientCodecConfigurer
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.net.URI

@Component
class EntityService(
    entityServiceProperties: EntityServiceProperties
) {

    private final val consumer: (ClientCodecConfigurer) -> Unit =
        { configurer -> configurer.defaultCodecs().enableLoggingRequestDetails(true) }

    private var webClient = WebClient.builder()
        .exchangeStrategies(ExchangeStrategies.builder().codecs(consumer).build())
        .baseUrl(entityServiceProperties.url)
        .build()

    fun getEntityById(entityId: URI, bearerToken: String): Mono<JsonLdEntity> =
        webClient.get()
            .uri("/entities/$entityId")
            .header("Authorization", bearerToken)
            .retrieve()
            .bodyToMono(String::class.java)
            .map { JsonLdUtils.expandJsonLdEntity(it) }
}

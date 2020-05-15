package com.egm.stellio.search.service

import com.egm.stellio.search.model.*
import com.egm.stellio.search.service.MyPostgresqlContainer.DB_PASSWORD
import com.egm.stellio.search.service.MyPostgresqlContainer.DB_USER
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.springframework.boot.test.context.SpringBootTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import reactor.test.StepVerifier
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.*
import kotlin.random.Random

@SpringBootTest
@ActiveProfiles("test")
@Import(R2DBCConfiguration::class)
class AttributeInstanceServiceTests {

    @Autowired
    private lateinit var attributeInstanceService: AttributeInstanceService

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    private val observationDateTime = Instant.now().atZone(ZoneOffset.UTC)

    private lateinit var temporalEntityAttribute: TemporalEntityAttribute

    init {
        Flyway.configure()
            .dataSource(MyPostgresqlContainer.instance.jdbcUrl, DB_USER, DB_PASSWORD)
            .load()
            .migrate()
    }

    @BeforeAll
    fun createTemporalEntityAttribute() {
        temporalEntityAttribute = TemporalEntityAttribute(
            entityId = "urn:ngsi-ld:BeeHive:TESTC",
            type = "BeeHive",
            attributeName = "incoming",
            attributeValueType = TemporalEntityAttribute.AttributeValueType.MEASURE
        )

        databaseClient.insert()
            .into(TemporalEntityAttribute::class.java)
            .using(temporalEntityAttribute)
            .then()
            .block()
    }

    @AfterEach
    fun clearPreviousObservations() {
        databaseClient.delete()
            .from("attribute_instance")
            .fetch()
            .rowsUpdated()
            .block()
    }

    @Test
    fun `it should retrieve an observation and return the filled entity`() {

        val observation = gimmeAttributeInstance().copy(
            observedAt = observationDateTime,
            measuredValue = 12.4
        )
        attributeInstanceService.create(observation).block()

        val temporalQuery = TemporalQuery(emptyList(), TemporalQuery.Timerel.AFTER, Instant.now().atZone(ZoneOffset.UTC).minusHours(1), null, null, null)
        val enrichedEntity = attributeInstanceService.search(temporalQuery, temporalEntityAttribute)

        StepVerifier.create(enrichedEntity)
            .expectNextMatches {
                it.size == 1 &&
                    it[0]["attribute_name"] == "incoming" &&
                    it[0]["value"] == 12.4 &&
                    ZonedDateTime.parse(it[0]["observed_at"].toString()).toInstant().atZone(ZoneOffset.UTC) == observationDateTime &&
                    (it[0]["instance_id"] as String).startsWith("urn:ngsi-ld:Instance:")
            }
            .expectComplete()
            .verify()
    }

    @Test
    fun `it should retrieve all known observations and return the filled entity`() {

        (1..10).forEach { _ -> attributeInstanceService.create(gimmeAttributeInstance()).block() }

        val temporalQuery = TemporalQuery(emptyList(), TemporalQuery.Timerel.AFTER, Instant.now().atZone(ZoneOffset.UTC).minusHours(1),
            null, null, null)
        val enrichedEntity = attributeInstanceService.search(temporalQuery, temporalEntityAttribute)

        StepVerifier.create(enrichedEntity)
            .expectNextMatches {
                it.size == 10
            }
            .expectComplete()
            .verify()
    }

    @Test
    fun `it should aggregate all observations for a day and return the filled entity`() {

        (1..10).forEach { _ ->
            val attributeInstance = gimmeAttributeInstance().copy(measuredValue = 1.0)
            attributeInstanceService.create(attributeInstance).block()
        }

        val temporalQuery = TemporalQuery(emptyList(), TemporalQuery.Timerel.AFTER, Instant.now().atZone(ZoneOffset.UTC).minusHours(1),
            null, "1 day", TemporalQuery.Aggregate.SUM)
        val enrichedEntity = attributeInstanceService.search(temporalQuery, temporalEntityAttribute)

        StepVerifier.create(enrichedEntity)
            .expectNextMatches {
                it.size == 1 &&
                    it[0]["value"] == 10.0
            }
            .expectComplete()
            .verify()
    }

    @Test
    fun `it should only retrieve the temporal evolution of the provided temporal entity atttribute`() {

        val temporalEntityAttribute2 = TemporalEntityAttribute(
            entityId = "urn:ngsi-ld:BeeHive:TESTC",
            type = "BeeHive",
            attributeName = "outgoing",
            attributeValueType = TemporalEntityAttribute.AttributeValueType.MEASURE
        )

        databaseClient.insert()
            .into(TemporalEntityAttribute::class.java)
            .using(temporalEntityAttribute2)
            .then()
            .block()

        (1..10).forEach { _ -> attributeInstanceService.create(gimmeAttributeInstance()).block() }
        (1..5).forEach { _ ->
            attributeInstanceService.create(
                gimmeAttributeInstance().copy(temporalEntityAttribute = temporalEntityAttribute2.id)).block()
        }

        val temporalQuery = TemporalQuery(emptyList(), TemporalQuery.Timerel.AFTER, Instant.now().atZone(ZoneOffset.UTC).minusHours(1),
            null, null, null)
        val enrichedEntity = attributeInstanceService.search(temporalQuery, temporalEntityAttribute)

        StepVerifier.create(enrichedEntity)
            .expectNextMatches {
                it.size == 10
            }
            .expectComplete()
            .verify()
    }

    @Test
    fun `it should not retrieve temporal data if temporal entity does not match`() {

        (1..10).forEach { _ -> attributeInstanceService.create(gimmeAttributeInstance()).block() }

        val temporalQuery = TemporalQuery(emptyList(), TemporalQuery.Timerel.AFTER, Instant.now().atZone(ZoneOffset.UTC).minusHours(1),
            null, null, null)
        val enrichedEntity = attributeInstanceService.search(temporalQuery, temporalEntityAttribute.copy(id = UUID.randomUUID()))

        StepVerifier.create(enrichedEntity)
            .expectNextMatches {
                it.isEmpty()
            }
            .expectComplete()
            .verify()
    }

    private fun gimmeAttributeInstance(): AttributeInstance {
        return AttributeInstance(
            temporalEntityAttribute = temporalEntityAttribute.id,
            measuredValue = Random.nextDouble(),
            observedAt = Instant.now().atZone(ZoneOffset.UTC)
        )
    }
}

class KPostgreSQLContainer(imageName: String) : PostgreSQLContainer<KPostgreSQLContainer>(imageName)

object MyPostgresqlContainer {

    const val DB_NAME = "context_search_test"
    const val DB_USER = "datahub"
    const val DB_PASSWORD = "password"
    // TODO later extract it to a props file or load from env variable
    private const val TIMESCALE_IMAGE = "timescale/timescaledb-postgis:1.4.2-pg11"

    val instance by lazy { startPostgresqlContainer() }

    private fun startPostgresqlContainer() = KPostgreSQLContainer(TIMESCALE_IMAGE).apply {
        withDatabaseName(DB_NAME)
        withUsername(DB_USER)
        withPassword(DB_PASSWORD)

        start()
    }
}

@TestConfiguration
class R2DBCConfiguration {

    @Bean
    fun connectionFactory(): ConnectionFactory {
        val options = ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DATABASE, MyPostgresqlContainer.instance.databaseName)
            .option(ConnectionFactoryOptions.HOST, MyPostgresqlContainer.instance.containerIpAddress)
            .option(ConnectionFactoryOptions.PORT, MyPostgresqlContainer.instance.firstMappedPort)
            .option(ConnectionFactoryOptions.USER, DB_USER)
            .option(ConnectionFactoryOptions.PASSWORD, DB_PASSWORD)
            .option(ConnectionFactoryOptions.DRIVER, "postgresql")
            .build()

        return ConnectionFactories.get(options)
    }
}

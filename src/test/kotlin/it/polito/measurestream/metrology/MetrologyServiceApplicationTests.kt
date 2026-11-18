package it.polito.measurestream.metrology

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals

/**
 * Il servizio si avvia contro un PostgreSQL vero e Flyway applica le migrazioni.
 *
 * Nel container di prova lo schema non esiste, perche' sulla Pi lo crea lo script una tantum:
 * qui lo si lascia creare a Flyway, ed e' l'unica differenza rispetto all'esercizio.
 * Senza Docker la classe viene saltata, non fallisce.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["spring.flyway.create-schemas=true"])
class MetrologyServiceApplicationTests {

	@Autowired
	lateinit var jdbc: JdbcTemplate

	@Test
	fun `il contesto si avvia e la baseline e' applicata`() {
		val applied = jdbc.queryForObject(
			"SELECT count(*) FROM metrology.flyway_schema_history WHERE version = '1' AND success",
			Int::class.java,
		)
		assertEquals(1, applied)
	}

	@Test
	fun `la funzione di immutabilita' esiste`() {
		val found = jdbc.queryForObject(
			"SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace " +
				"WHERE n.nspname = 'metrology' AND p.proname = 'reject_change'",
			Int::class.java,
		)
		assertEquals(1, found)
	}
}

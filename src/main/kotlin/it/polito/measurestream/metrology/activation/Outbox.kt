package it.polito.measurestream.metrology.activation

import it.polito.measurestream.metrology.jobs.contract.CalibrationTopics
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Un messaggio da consegnare a Kafka, scritto nella transazione che lo genera: un'attivazione,
 * una revoca, un job per il motore. Se la transazione riesce il messaggio esiste, e il
 * pubblicatore lo consegna appena Kafka risponde; se fallisce, sparisce con lei.
 */
@Entity
@Table(name = "outbox_event")
class OutboxEvent(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@Column(nullable = false, length = 64) var topic: String,
	@Column(name = "event_key", length = 80) var eventKey: String? = null,
	/** Il JSON del messaggio; null e' un tombstone. */
	@Column(columnDefinition = "text") var payload: String? = null,
	@Column(name = "created_at", nullable = false) var createdAt: OffsetDateTime = OffsetDateTime.now(),
	@Column(name = "sent_at") var sentAt: OffsetDateTime? = null,
	@Column(nullable = false) var attempts: Int = 0,
	@Column(name = "last_error", length = 500) var lastError: String? = null,
)

interface OutboxEventRepository : JpaRepository<OutboxEvent, Long> {
	fun findTop100BySentAtIsNullOrderByIdAsc(): List<OutboxEvent>

	fun countBySentAtIsNull(): Long

	@Modifying
	@Query("delete from OutboxEvent e where e.sentAt is not null and e.sentAt < :before")
	fun deleteSentBefore(before: OffsetDateTime): Int
}

@Service
class Outbox(private val repository: OutboxEventRepository) {
	/** Accoda un messaggio nella transazione corrente, che deve esistere. */
	@Transactional(propagation = Propagation.MANDATORY)
	fun enqueue(topic: String, key: String?, payload: String?): OutboxEvent =
		repository.save(OutboxEvent(topic = topic, eventKey = key, payload = payload))
}

/**
 * Consegna l'outbox in ordine, aspettando la conferma del broker, e si ferma al primo errore:
 * spedire i successivi romperebbe l'ordine, e per un topic compattato l'ordine e' tutto (una
 * revoca consegnata prima della sua attivazione lascerebbe in vigore la taratura sbagliata).
 */
@Component
class OutboxPublisher(
	private val repository: OutboxEventRepository,
	private val kafka: KafkaTemplate<String, String>,
) {
	private val log = LoggerFactory.getLogger(OutboxPublisher::class.java)

	@Scheduled(fixedDelay = 2_000L, initialDelay = 5_000L)
	@Transactional
	fun publish() {
		for (event in repository.findTop100BySentAtIsNullOrderByIdAsc()) {
			try {
				// ProducerRecord, e non send(topic, key, value): il tombstone ha il valore nullo, che la
				// firma annotata di KafkaTemplate non ammette.
				kafka.send(ProducerRecord<String, String>(event.topic, event.eventKey, event.payload)).get(10, TimeUnit.SECONDS)
				event.sentAt = OffsetDateTime.now()
				event.lastError = null
			} catch (e: Exception) {
				event.attempts += 1
				event.lastError = (e.cause?.message ?: e.message)?.take(500)
				if (event.attempts == 1 || event.attempts % 100 == 0) {
					log.warn("Outbox: messaggio {} su {} non consegnato ({} tentativi): {}", event.id, event.topic, event.attempts, event.lastError)
				}
				break
			}
		}
	}

	@Scheduled(cron = "0 23 3 * * *")
	@Transactional
	fun purge() {
		val removed = repository.deleteSentBefore(OffsetDateTime.now().minusDays(30))
		if (removed > 0) log.info("Outbox: {} messaggi consegnati rimossi", removed)
	}
}

/**
 * I topic di cui questo servizio e' l'autore, piu' quello dei gradini che consuma.
 * `calibration-activated` e' compattato: Kafka tiene l'ultima attivazione di ogni esemplare, ed
 * e' cio' da cui sensor-manager ricostruisce lo stato. Lo dichiara anche sensor-manager, cosi'
 * chi parte per primo lo crea giusto. `calibrations` lo scrive il campione viaggiante: dichiararlo
 * qui evita di dipendere dalla creazione automatica del broker (se esiste gia', resta com'e').
 */
@Configuration
@EnableScheduling
class KafkaTopics {
	@Bean
	fun calibrationActivatedTopic() = TopicBuilder.name(ActivationEvent.TOPIC).partitions(1).replicas(1).compact().build()

	@Bean
	fun calibrationJobsTopic() = TopicBuilder.name(CalibrationTopics.JOBS).partitions(1).replicas(1).build()

	@Bean
	fun calibrationResultsTopic() = TopicBuilder.name(CalibrationTopics.RESULTS).partitions(1).replicas(1).build()

	@Bean
	fun calibrationStepsTopic() = TopicBuilder.name(CalibrationTopics.STEPS).partitions(1).replicas(1).build()
}

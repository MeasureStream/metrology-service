package it.polito.measurestream.metrology.common

import it.polito.measurestream.metrology.jobs.contract.ContractJson
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.security.MessageDigest
import java.time.OffsetDateTime
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Una riga del registro delle azioni: chi, cosa, su quale oggetto, com'era prima e com'e'
 * dopo (come impronta). Solo inserimenti: un trigger rifiuta UPDATE e DELETE [S §9].
 */
@Entity
@org.hibernate.annotations.Immutable
@Table(name = "audit_log")
class AuditLog(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@Column(nullable = false) var at: OffsetDateTime = OffsetDateTime.now(),
	@Column(name = "user_sub", length = 64) var userSub: String? = null,
	@Column(nullable = false, length = 64) var action: String,
	@Column(nullable = false, length = 40) var entity: String,
	@Column(name = "entity_id") var entityId: Long? = null,
	@Column(name = "before_sha", length = 64) var beforeSha: String? = null,
	@Column(name = "after_sha", length = 64) var afterSha: String? = null,
	@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var detail: Map<String, Any?>? = null,
)

interface AuditLogRepository : JpaRepository<AuditLog, Long> {
	fun findByEntityAndEntityIdOrderByAtAsc(entity: String, entityId: Long): List<AuditLog>

	/** Il registro di un periodo, per il pacchetto dell'ispettore (passo 5.5). */
	fun findAllByAtBetweenOrderByAtAsc(from: java.time.OffsetDateTime, to: java.time.OffsetDateTime): List<AuditLog>
}

/**
 * Ogni transizione di stato lascia una riga. Gira nella transazione di chi la chiama: se
 * l'azione fallisce, sparisce anche la sua traccia, e una traccia di qualcosa che non e'
 * successo sarebbe peggio di nessuna traccia.
 */
@Service
class AuditService(
	private val repository: AuditLogRepository,
	private val currentUser: CurrentUser,
) {
	@Transactional(propagation = Propagation.MANDATORY)
	fun record(
		action: String,
		entity: String,
		entityId: Long?,
		before: Any? = null,
		after: Any? = null,
		detail: Map<String, Any?>? = null,
		// Null e non `currentUser.subOrNull()`: il default di Kotlin si calcola sul proxy di
		// Spring, i cui campi sono vuoti. Il valore vero si risolve dentro il metodo.
		userSub: String? = null,
	) {
		repository.save(
			AuditLog(
				userSub = userSub ?: currentUser.subOrNull(),
				action = action,
				entity = entity,
				entityId = entityId,
				beforeSha = before?.let { sha256(it) },
				afterSha = after?.let { sha256(it) },
				detail = detail?.let { ContractJson.plain(it) },
			)
		)
	}

	fun history(entity: String, id: Long): List<AuditLog> = repository.findByEntityAndEntityIdOrderByAtAsc(entity, id)

	companion object {
		/** Impronta del JSON canonico (chiavi ordinate) di uno stato. */
		fun sha256(value: Any): String {
			val json = ContractJson.canonical(value)
			return MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
		}
	}
}

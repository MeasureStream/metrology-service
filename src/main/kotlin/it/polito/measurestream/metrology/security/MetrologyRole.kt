package it.polito.measurestream.metrology.security

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.OffsetDateTime
import org.springframework.data.jpa.repository.JpaRepository

/** I ruoli del sistema qualita' [S §9]. Il responsabile tecnico puo' tutto cio' che possono gli altri due. */
enum class MetrologyRoleType { TECHNICIAN, APPROVER, TECHNICAL_MANAGER }

/**
 * Un ruolo metrologico assegnato a una persona per un periodo. Non sta in Keycloak perche'
 * conta la data: chi ha approvato un certificato a marzo doveva essere approvatore a marzo,
 * non oggi. Per questo un ruolo non si cancella, si chiude con [validTo].
 */
@Entity
@Table(name = "metrology_role")
class MetrologyRole(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@Column(name = "user_sub", nullable = false, length = 64) var userSub: String,
	@Column(name = "display_name", length = 200) var displayName: String? = null,
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) var role: MetrologyRoleType,
	@Column(name = "valid_from", nullable = false) var validFrom: LocalDate,
	@Column(name = "valid_to") var validTo: LocalDate? = null,
	@Column(name = "lab_company_id") var labCompanyId: Long? = null,
	@Column(name = "granted_by", nullable = false, length = 64) var grantedBy: String,
	@Column(name = "granted_at", nullable = false) var grantedAt: OffsetDateTime = OffsetDateTime.now(),
) {
	fun validAt(date: LocalDate): Boolean = !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo))
}

interface MetrologyRoleRepository : JpaRepository<MetrologyRole, Long> {
	fun findAllByUserSub(userSub: String): List<MetrologyRole>

	fun findAllByOrderByUserSubAscValidFromDesc(): List<MetrologyRole>
}

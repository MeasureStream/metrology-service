package it.polito.measurestream.metrology.companies

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.OffsetDateTime
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Un laboratorio: il nostro (`internal`) e quelli esterni che emettono certificati.
 * `recognized` dice se un DCC firmato da loro e' considerato verificato senza il controllo a
 * quattro occhi [S §5.5].
 */
@Entity
@Table(name = "lab_company")
class LabCompany(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@Column(nullable = false, length = 200) var name: String,
	@Column(length = 200) var address: String? = null,
	@Column(length = 100) var city: String? = null,
	@Column(name = "postal_code", length = 16) var postalCode: String? = null,
	@Column(length = 2) var country: String? = null,
	@Column(name = "accreditation_body", length = 80) var accreditationBody: String? = null,
	@Column(name = "accreditation_number", length = 80) var accreditationNumber: String? = null,
	@Column(name = "accreditation_valid_until") var accreditationValidUntil: LocalDate? = null,
	@Column(nullable = false) var internal: Boolean = false,
	@Column(nullable = false) var recognized: Boolean = false,
	@Column(name = "created_at", nullable = false) var createdAt: OffsetDateTime = OffsetDateTime.now(),
) {
	/** «ACCREDIA LAT n. 123», o null se il laboratorio non e' accreditato. */
	fun accreditation(): String? =
		listOfNotNull(accreditationBody, accreditationNumber).joinToString(" ").ifBlank { null }
}

/** Un'azienda cliente: il «cliente» dell'Hub e l'intestatario dei DCC (decisione D-C). */
@Entity
@Table(name = "client_company")
class ClientCompany(
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
	@Column(nullable = false, length = 200, unique = true) var name: String,
	@Column(length = 200) var address: String? = null,
	@Column(length = 100) var city: String? = null,
	@Column(name = "postal_code", length = 16) var postalCode: String? = null,
	@Column(length = 2) var country: String? = null,
	@Column(name = "vat_number", length = 32) var vatNumber: String? = null,
	@Column(name = "created_at", nullable = false) var createdAt: OffsetDateTime = OffsetDateTime.now(),
	@Column(name = "archived_at") var archivedAt: OffsetDateTime? = null,
)

/** Un utente Keycloak dentro un'azienda: uno solo per utente, la chiave e' il suo `sub`. */
@Entity
@Table(name = "client_company_member")
class ClientCompanyMember(
	@Id @Column(name = "user_sub", length = 64) var userSub: String,
	@Column(name = "company_id", nullable = false) var companyId: Long,
	@Column(name = "display_name", length = 200) var displayName: String? = null,
	@Column(length = 200) var email: String? = null,
	@Column(name = "added_by", nullable = false, length = 64) var addedBy: String,
	@Column(name = "added_at", nullable = false) var addedAt: OffsetDateTime = OffsetDateTime.now(),
)

interface LabCompanyRepository : JpaRepository<LabCompany, Long> {
	fun findFirstByInternalTrue(): LabCompany?
}

interface ClientCompanyRepository : JpaRepository<ClientCompany, Long> {
	fun findAllByArchivedAtIsNullOrderByName(): List<ClientCompany>
}

interface ClientCompanyMemberRepository : JpaRepository<ClientCompanyMember, String> {
	fun findAllByCompanyId(companyId: Long): List<ClientCompanyMember>
}

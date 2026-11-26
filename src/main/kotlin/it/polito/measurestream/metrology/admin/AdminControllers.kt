package it.polito.measurestream.metrology.admin

import it.polito.measurestream.metrology.common.AuditService
import it.polito.measurestream.metrology.common.BadRequest
import it.polito.measurestream.metrology.common.Conflict
import it.polito.measurestream.metrology.common.NotFound
import it.polito.measurestream.metrology.companies.ClientCompany
import it.polito.measurestream.metrology.companies.ClientCompanyMember
import it.polito.measurestream.metrology.companies.ClientCompanyMemberRepository
import it.polito.measurestream.metrology.companies.ClientCompanyRepository
import it.polito.measurestream.metrology.companies.LabCompany
import it.polito.measurestream.metrology.companies.LabCompanyRepository
import it.polito.measurestream.metrology.security.AccessPolicy
import it.polito.measurestream.metrology.security.MetrologyRole
import it.polito.measurestream.metrology.security.MetrologyRoleRepository
import it.polito.measurestream.metrology.security.MetrologyRoleType
import it.polito.measurestream.metrology.signing.SigningService
import java.time.LocalDate
import java.time.OffsetDateTime
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class CompanyRequest(
	val name: String,
	val address: String? = null,
	val city: String? = null,
	val postalCode: String? = null,
	val country: String? = null,
	val vatNumber: String? = null,
)

data class CompanyDTO(
	val id: Long,
	val name: String,
	val address: String?,
	val city: String?,
	val postalCode: String?,
	val country: String?,
	val vatNumber: String?,
	val members: List<MemberDTO>,
)

data class MemberRequest(val userSub: String, val displayName: String? = null, val email: String? = null)

data class MemberDTO(val userSub: String, val displayName: String?, val email: String?, val addedAt: OffsetDateTime)

data class LabRequest(
	val name: String,
	val address: String? = null,
	val city: String? = null,
	val postalCode: String? = null,
	val country: String? = null,
	val accreditationBody: String? = null,
	val accreditationNumber: String? = null,
	val accreditationValidUntil: LocalDate? = null,
	val recognized: Boolean = false,
)

data class RoleRequest(
	val userSub: String,
	val displayName: String? = null,
	val role: MetrologyRoleType,
	val validFrom: LocalDate? = null,
	val validTo: LocalDate? = null,
	val labCompanyId: Long? = null,
)

data class RoleDTO(
	val id: Long,
	val userSub: String,
	val displayName: String?,
	val role: MetrologyRoleType,
	val validFrom: LocalDate,
	val validTo: LocalDate?,
	val labCompanyId: Long?,
	val grantedBy: String,
	val activeToday: Boolean,
)

/**
 * Chi sono per il sistema qualita': cosa posso fare oggi. L'interfaccia lo chiede una volta e
 * decide quali pulsanti mostrare; il server ricontrolla comunque a ogni azione.
 */
data class MetrologyProfileDTO(
	val sub: String,
	val username: String?,
	val displayName: String?,
	val admin: Boolean,
	val lab: Boolean,
	val roles: List<MetrologyRoleType>,
	val labCompanyId: Long?,
	val companyId: Long?,
	val companyName: String?,
	val canEnterCertificates: Boolean,
	val canApprove: Boolean,
	val canOpenSessions: Boolean,
	val signingAvailable: Boolean,
	/** Vero se la chiave di firma e' quella di sviluppo: l'interfaccia lo deve dire. */
	val signingDevOnly: Boolean,
	/**
	 * Modalita' rodaggio accesa: l'amministratore vede i pulsanti per correggere e cancellare
	 * cio' che in esercizio resta immutabile, e l'interfaccia ricorda che va spenta.
	 */
	val runIn: Boolean = false,
)

fun MetrologyRole.toDTO() = RoleDTO(id, userSub, displayName, role, validFrom, validTo, labCompanyId, grantedBy, validAt(LocalDate.now()))

/**
 * Anagrafiche e ruoli (PIANO_METROLOGIA 1.3, 1.8): aziende cliente con i loro membri,
 * laboratori, ruoli metrologici con la loro validita'. Le gestisce l'amministratore; ogni
 * modifica lascia una riga nel registro delle azioni.
 */
@RestController
@RequestMapping("/API/metrology")
class AdminController(
	private val companies: ClientCompanyRepository,
	private val members: ClientCompanyMemberRepository,
	private val labs: LabCompanyRepository,
	private val roles: MetrologyRoleRepository,
	private val policy: AccessPolicy,
	private val audit: AuditService,
	private val signing: SigningService,
) {
	@GetMapping("/me")
	fun me(): MetrologyProfileDTO {
		val caller = policy.caller()
		val held = policy.metrologyRoles(caller.sub).toList().sorted()
		val company = policy.companyOf(caller.sub)
		return MetrologyProfileDTO(
			sub = caller.sub,
			username = caller.username,
			displayName = caller.displayName,
			admin = caller.isAdmin,
			lab = caller.isLab,
			roles = held,
			labCompanyId = policy.labOf(caller.sub),
			companyId = company,
			companyName = company?.let { companies.findById(it).orElse(null)?.name },
			canEnterCertificates = policy.has(caller, MetrologyRoleType.TECHNICIAN, MetrologyRoleType.APPROVER) || caller.isLab,
			canApprove = policy.has(caller, MetrologyRoleType.APPROVER),
			canOpenSessions = policy.has(caller, MetrologyRoleType.TECHNICIAN),
			signingAvailable = signing.available(),
			signingDevOnly = signing.devOnly(),
			runIn = policy.runIn,
		)
	}

	/* ---------------------------------------------------------------- aziende */

	@GetMapping("/companies")
	@Transactional(readOnly = true)
	fun companies(): List<CompanyDTO> {
		policy.requireAdmin()
		val byCompany = members.findAll().groupBy { it.companyId }
		return companies.findAllByArchivedAtIsNullOrderByName().map { it.toDTO(byCompany[it.id].orEmpty()) }
	}

	@PostMapping("/companies")
	@Transactional
	fun createCompany(@RequestBody request: CompanyRequest): CompanyDTO {
		policy.requireAdmin()
		if (request.name.isBlank()) throw BadRequest("Il nome dell'azienda e' obbligatorio")
		val company = companies.save(ClientCompany(name = request.name.trim()).apply { apply(request) })
		audit.record("COMPANY_CREATED", "client_company", company.id, after = request)
		return company.toDTO(emptyList())
	}

	@PutMapping("/companies/{id}")
	@Transactional
	fun updateCompany(@PathVariable id: Long, @RequestBody request: CompanyRequest): CompanyDTO {
		policy.requireAdmin()
		val company = companies.findById(id).orElseThrow { NotFound("Azienda $id non trovata") }
		company.apply(request)
		audit.record("COMPANY_UPDATED", "client_company", id, after = request)
		return companies.save(company).toDTO(members.findAllByCompanyId(id))
	}

	@PostMapping("/companies/{id}/members")
	@Transactional
	fun addMember(@PathVariable id: Long, @RequestBody request: MemberRequest): CompanyDTO {
		val caller = policy.caller()
		policy.requireAdmin(caller)
		val company = companies.findById(id).orElseThrow { NotFound("Azienda $id non trovata") }
		if (request.userSub.isBlank()) throw BadRequest("Serve l'identificativo Keycloak dell'utente")
		// Un utente sta in una sola azienda: aggiungerlo qui lo sposta, e il registro lo dice.
		val previous = members.findById(request.userSub).orElse(null)?.companyId
		members.save(ClientCompanyMember(request.userSub.trim(), id, request.displayName, request.email, caller.sub))
		audit.record("COMPANY_MEMBER_ADDED", "client_company", id, detail = mapOf("userSub" to request.userSub, "from" to previous))
		return company.toDTO(members.findAllByCompanyId(id))
	}

	/**
	 * Un'azienda inserita per errore si cancella finche' nessuno la usa: niente membri, niente
	 * punti di controllo. Il vincolo del database ferma comunque ogni altro riferimento.
	 */
	@DeleteMapping("/companies/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Transactional
	fun deleteCompany(@PathVariable id: Long) {
		policy.requireAdmin()
		val company = companies.findById(id).orElseThrow { NotFound("Azienda $id non trovata") }
		if (members.findAllByCompanyId(id).isNotEmpty()) throw Conflict("L'azienda ${company.name} ha dei membri: vanno tolti prima di cancellarla")
		audit.record("COMPANY_DELETED", "client_company", id, before = company.toDTO(emptyList()))
		deleteOrExplain("L'azienda ${company.name} e' gia' usata da un punto di controllo: non si cancella") {
			companies.delete(company)
			companies.flush()
		}
	}

	@DeleteMapping("/companies/{id}/members/{sub}")
	@Transactional
	fun removeMember(@PathVariable id: Long, @PathVariable sub: String): CompanyDTO {
		policy.requireAdmin()
		val company = companies.findById(id).orElseThrow { NotFound("Azienda $id non trovata") }
		members.findById(sub).filter { it.companyId == id }.ifPresent { members.delete(it) }
		audit.record("COMPANY_MEMBER_REMOVED", "client_company", id, detail = mapOf("userSub" to sub))
		return company.toDTO(members.findAllByCompanyId(id))
	}

	/* ------------------------------------------------------------ laboratori */

	@GetMapping("/labs")
	fun labs(): List<LabCompany> = labs.findAll().sortedWith(compareByDescending<LabCompany> { it.internal }.thenBy { it.name })

	@PostMapping("/labs")
	@Transactional
	fun createLab(@RequestBody request: LabRequest): LabCompany {
		policy.requireAdmin()
		if (request.name.isBlank()) throw BadRequest("Il nome del laboratorio e' obbligatorio")
		val lab = labs.save(LabCompany(name = request.name.trim()).apply { apply(request) })
		audit.record("LAB_CREATED", "lab_company", lab.id, after = request)
		return lab
	}

	@PutMapping("/labs/{id}")
	@Transactional
	fun updateLab(@PathVariable id: Long, @RequestBody request: LabRequest): LabCompany {
		policy.requireAdmin()
		val lab = labs.findById(id).orElseThrow { NotFound("Laboratorio $id non trovato") }
		lab.apply(request)
		audit.record("LAB_UPDATED", "lab_company", id, after = request)
		return labs.save(lab)
	}

	/**
	 * Un laboratorio inserito per errore si cancella finche' nessuno lo usa: niente ruoli, niente
	 * esemplari affidati, niente certificati. Il laboratorio interno non si cancella mai.
	 */
	@DeleteMapping("/labs/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Transactional
	fun deleteLab(@PathVariable id: Long) {
		policy.requireAdmin()
		val lab = labs.findById(id).orElseThrow { NotFound("Laboratorio $id non trovato") }
		if (lab.internal) throw Conflict("Il laboratorio interno non si cancella")
		audit.record("LAB_DELETED", "lab_company", id, detail = mapOf("name" to lab.name, "accreditationNumber" to lab.accreditationNumber))
		deleteOrExplain("Il laboratorio ${lab.name} e' gia' usato da ruoli, esemplari affidati o certificati: non si cancella") {
			labs.delete(lab)
			labs.flush()
		}
	}

	/* ------------------------------------------------------- ruoli metrologici */

	@GetMapping("/roles")
	fun roles(): List<RoleDTO> {
		val caller = policy.caller()
		return if (caller.isAdmin) roles.findAllByOrderByUserSubAscValidFromDesc().map { it.toDTO() }
		else roles.findAllByUserSub(caller.sub).map { it.toDTO() }
	}

	@PostMapping("/roles")
	@Transactional
	fun grant(@RequestBody request: RoleRequest): RoleDTO {
		val caller = policy.caller()
		policy.requireAdmin(caller)
		if (request.userSub.isBlank()) throw BadRequest("Serve l'identificativo Keycloak dell'utente")
		val from = request.validFrom ?: LocalDate.now()
		if (request.validTo != null && request.validTo.isBefore(from)) throw BadRequest("La fine della validita' precede l'inizio")
		val role =
			roles.save(
				MetrologyRole(
					userSub = request.userSub.trim(),
					displayName = request.displayName,
					role = request.role,
					validFrom = from,
					validTo = request.validTo,
					labCompanyId = request.labCompanyId,
					grantedBy = caller.sub,
				)
			)
		audit.record("ROLE_GRANTED", "metrology_role", role.id, after = request)
		return role.toDTO()
	}

	/** Un ruolo non si cancella: si chiude, perche' chi ha approvato ieri lo era davvero. */
	@PostMapping("/roles/{id}/end")
	@Transactional
	fun end(@PathVariable id: Long): RoleDTO {
		policy.requireAdmin()
		val role = roles.findById(id).orElseThrow { NotFound("Ruolo $id non trovato") }
		val yesterday = LocalDate.now().minusDays(1)
		role.validTo = if (yesterday.isBefore(role.validFrom)) role.validFrom else yesterday
		audit.record("ROLE_ENDED", "metrology_role", id, detail = mapOf("validTo" to role.validTo.toString()))
		return roles.save(role).toDTO()
	}

	/**
	 * In rodaggio un ruolo si corregge: l'utente sbagliato, la data sbagliata, il ruolo sbagliato.
	 * In esercizio no, si chiude e se ne concede uno nuovo; il registro tiene il prima e il dopo.
	 */
	@PutMapping("/roles/{id}")
	@Transactional
	fun correctRole(@PathVariable id: Long, @RequestBody request: RoleRequest): RoleDTO {
		policy.requireRunInAdmin("Correggere un ruolo")
		val role = roles.findById(id).orElseThrow { NotFound("Ruolo $id non trovato") }
		if (request.userSub.isBlank()) throw BadRequest("Serve l'identificativo Keycloak dell'utente")
		val from = request.validFrom ?: role.validFrom
		if (request.validTo != null && request.validTo.isBefore(from)) throw BadRequest("La fine della validita' precede l'inizio")
		val before = role.toDTO()
		role.userSub = request.userSub.trim()
		role.displayName = request.displayName
		role.role = request.role
		role.validFrom = from
		role.validTo = request.validTo
		role.labCompanyId = request.labCompanyId
		audit.record("ROLE_CORRECTED", "metrology_role", id, before = before, after = request)
		return roles.save(role).toDTO()
	}

	/** In rodaggio un ruolo dato per errore si cancella; in esercizio si chiude con [end]. */
	@DeleteMapping("/roles/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Transactional
	fun deleteRole(@PathVariable id: Long) {
		policy.requireRunInAdmin("Cancellare un ruolo")
		val role = roles.findById(id).orElseThrow { NotFound("Ruolo $id non trovato") }
		audit.record("ROLE_DELETED", "metrology_role", id, before = role.toDTO())
		roles.delete(role)
	}

	/**
	 * Cancella, e se il database rifiuta per un riferimento ancora vivo lo dice in chiaro invece
	 * del messaggio del vincolo. La transazione si annulla, riga del registro compresa.
	 */
	private fun deleteOrExplain(message: String, work: () -> Unit) {
		try {
			work()
		} catch (e: DataIntegrityViolationException) {
			throw Conflict(message)
		}
	}

	private fun ClientCompany.apply(r: CompanyRequest) {
		name = r.name.trim().ifBlank { name }
		address = r.address
		city = r.city
		postalCode = r.postalCode
		country = r.country?.uppercase()?.take(2)
		vatNumber = r.vatNumber
	}

	private fun LabCompany.apply(r: LabRequest) {
		name = r.name.trim().ifBlank { name }
		address = r.address
		city = r.city
		postalCode = r.postalCode
		country = r.country?.uppercase()?.take(2)
		accreditationBody = r.accreditationBody
		accreditationNumber = r.accreditationNumber
		accreditationValidUntil = r.accreditationValidUntil
		recognized = r.recognized
	}

	private fun ClientCompany.toDTO(list: List<ClientCompanyMember>) =
		CompanyDTO(id, name, address, city, postalCode, country, vatNumber, list.map { MemberDTO(it.userSub, it.displayName, it.email, it.addedAt) })
}

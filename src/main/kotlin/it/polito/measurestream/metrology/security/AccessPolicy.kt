package it.polito.measurestream.metrology.security

import it.polito.measurestream.metrology.common.Caller
import it.polito.measurestream.metrology.common.CurrentUser
import it.polito.measurestream.metrology.common.Forbidden
import it.polito.measurestream.metrology.companies.ClientCompanyMemberRepository
import java.time.LocalDate
import org.springframework.stereotype.Component

/**
 * Le due domande che ogni azione fa prima di procedere [S §9]: chi chiama ha il ruolo, valido
 * oggi, per farla? E l'oggetto e' suo, o della sua azienda, o del laboratorio a cui e' affidato?
 *
 * Un componente solo, cosi' la regola si legge in un posto e non si ricopia in ogni servizio
 * con una variante diversa ogni volta (M5).
 */
@Component
class AccessPolicy(
	private val currentUser: CurrentUser,
	private val roles: MetrologyRoleRepository,
	private val members: ClientCompanyMemberRepository,
) {
	fun caller(): Caller = currentUser.get()

	/** I ruoli metrologici di una persona validi alla data. */
	fun metrologyRoles(sub: String, at: LocalDate = LocalDate.now()): Set<MetrologyRoleType> =
		roles.findAllByUserSub(sub).filter { it.validAt(at) }.map { it.role }.toSet()

	/** Il laboratorio esterno di un utente app-lab, dal suo ruolo metrologico. */
	fun labOf(sub: String, at: LocalDate = LocalDate.now()): Long? =
		roles.findAllByUserSub(sub).firstOrNull { it.validAt(at) && it.labCompanyId != null }?.labCompanyId

	fun has(caller: Caller, vararg wanted: MetrologyRoleType, at: LocalDate = LocalDate.now()): Boolean {
		if (caller.isAdmin) return true
		val held = metrologyRoles(caller.sub, at)
		if (MetrologyRoleType.TECHNICAL_MANAGER in held) return true
		return wanted.any { it in held }
	}

	/** Personale del laboratorio: chi ha un ruolo metrologico valido, o l'amministratore. */
	fun isStaff(caller: Caller = caller()): Boolean = caller.isAdmin || metrologyRoles(caller.sub).isNotEmpty()

	fun requireAdmin(caller: Caller = caller()) {
		if (!caller.isAdmin) throw Forbidden("Operazione riservata agli amministratori")
	}

	fun requireRole(caller: Caller, vararg wanted: MetrologyRoleType, what: String) {
		if (!has(caller, *wanted)) {
			throw Forbidden("$what richiede il ruolo ${wanted.joinToString(" o ") { label(it) }} valido oggi")
		}
	}

	/** L'azienda cliente di un utente, o null se non e' assegnato a nessuna. */
	fun companyOf(sub: String?): Long? = sub?.let { members.findById(it).orElse(null)?.companyId }

	/**
	 * Gli utenti di cui chi chiama puo' vedere gli oggetti: se stesso e i colleghi della sua
	 * azienda. Null vuol dire «tutti» (amministratore, personale, motore).
	 */
	fun visibleOwners(caller: Caller = caller()): Set<String>? {
		if (caller.isAdmin || caller.isEngine || isStaff(caller)) return null
		val company = companyOf(caller.sub) ?: return setOf(caller.sub)
		return members.findAllByCompanyId(company).map { it.userSub }.toSet() + caller.sub
	}

	/**
	 * Puo' vedere un oggetto di [ownerSub], affidato eventualmente al laboratorio [assignedLabId]?
	 * Un utente di laboratorio vede solo cio' che e' affidato al suo laboratorio.
	 */
	fun canSee(ownerSub: String?, assignedLabId: Long? = null, caller: Caller = caller()): Boolean {
		val owners = visibleOwners(caller)
		if (owners == null) return true
		if (caller.isLab && assignedLabId != null && labOf(caller.sub) == assignedLabId) return true
		return ownerSub != null && ownerSub in owners
	}

	fun requireSee(ownerSub: String?, assignedLabId: Long? = null, what: String = "L'oggetto") {
		if (!canSee(ownerSub, assignedLabId)) throw Forbidden("$what appartiene a un altro utente")
	}

	private fun label(role: MetrologyRoleType) =
		when (role) {
			MetrologyRoleType.TECHNICIAN -> "tecnico"
			MetrologyRoleType.APPROVER -> "approvatore"
			MetrologyRoleType.TECHNICAL_MANAGER -> "responsabile tecnico"
		}
}

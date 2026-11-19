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

	/**
	 * I ruoli metrologici **interni** di una persona validi alla data: quelli che ne fanno personale
	 * del laboratorio. Un ruolo legato a un laboratorio esterno (`labCompanyId`) non ne fa parte:
	 * serve solo a [labOf], cioe' a dire a quale laboratorio appartiene un utente `app-lab`. Contarlo
	 * qui ne farebbe personale interno: vedrebbe gli oggetti di tutti i clienti e, da approvatore,
	 * approverebbe [S §9].
	 */
	fun metrologyRoles(sub: String, at: LocalDate = LocalDate.now()): Set<MetrologyRoleType> =
		roles.findAllByUserSub(sub).filter { it.validAt(at) && it.labCompanyId == null }.map { it.role }.toSet()

	/** Il laboratorio esterno di un utente app-lab, dal suo ruolo metrologico. */
	fun labOf(sub: String, at: LocalDate = LocalDate.now()): Long? =
		roles.findAllByUserSub(sub).firstOrNull { it.validAt(at) && it.labCompanyId != null }?.labCompanyId

	fun has(caller: Caller, vararg wanted: MetrologyRoleType, at: LocalDate = LocalDate.now()): Boolean {
		if (caller.isAdmin) return true
		// Un utente di un laboratorio esterno non e' mai personale interno, nemmeno se per errore gli
		// fosse stato dato un ruolo senza laboratorio: puo' solo cio' che gli concede [labOf].
		if (caller.isLab) return false
		val held = metrologyRoles(caller.sub, at)
		if (MetrologyRoleType.TECHNICAL_MANAGER in held) return true
		return wanted.any { it in held }
	}

	/**
	 * Personale del laboratorio: l'amministratore, o chi ha un ruolo metrologico interno valido e
	 * non e' un utente di un laboratorio esterno.
	 */
	fun isStaff(caller: Caller = caller()): Boolean = caller.isAdmin || (!caller.isLab && metrologyRoles(caller.sub).isNotEmpty())

	/**
	 * Il messaggio per una MU che sensor-manager non restituisce: non esiste, o chi chiama non la
	 * vede. sensor-manager mostra tutte le MU solo all'amministratore e al personale con il ruolo
	 * di accesso `app-staff`; agli altri solo le proprie. Al personale senza quel ruolo lo si
	 * dice, perche' altrimenti il caso si scambia per un EUID sbagliato.
	 */
	fun invisibleMu(euid: Long, caller: Caller = caller()): String {
		val base = "MU 0x%08X non trovata fra quelle visibili a questo utente".format(euid)
		val staffWithoutAccess = !caller.isAdmin && !caller.hasStaffAccess && isStaff(caller)
		return if (staffWithoutAccess) "$base: per vedere le MU dei clienti serve il ruolo Keycloak ${Roles.STAFF}" else base
	}

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

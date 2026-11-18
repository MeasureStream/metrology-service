package it.polito.measurestream.metrology.security

/**
 * Ruoli di realm di Keycloak, come arrivano nel token.
 *
 * Sono i ruoli di **accesso**: chi entra e da quale porta. I ruoli **metrologici** (tecnico,
 * approvatore, responsabile tecnico) non stanno qui ma nella tabella `metrology_role`, perche'
 * devono avere una validita' nel tempo che Keycloak non sa gestire.
 */
object Roles {
	/** Amministratore della piattaforma. */
	const val ADMIN = "app-admin"

	/** Utente di un'azienda cliente. */
	const val USER = "app-user"

	/** Utente di un laboratorio esterno: inserisce i dati dei certificati degli esemplari assegnati. */
	const val LAB = "app-lab"

	/** L'account di servizio del motore di taratura (client credentials). */
	const val CALIBRATION_ENGINE = "CALIBRATION_ENGINE"

	/** Prefisso che Spring Security si aspetta davanti a un ruolo. */
	const val PREFIX = "ROLE_"
}

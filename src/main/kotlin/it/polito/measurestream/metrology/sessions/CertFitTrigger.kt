package it.polito.measurestream.metrology.sessions

import it.polito.measurestream.metrology.certificates.CertFitRequested
import it.polito.measurestream.metrology.jobs.contract.JobKind
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * La trascrizione di un certificato esterno senza funzione chiede al motore di ricavarla dai
 * punti (passo 5.3). L'evento passa per Spring per non legare il modulo dei certificati a quello
 * dei job; il listener gira nella transazione della trascrizione, quindi il job esiste se e solo
 * se la trascrizione e' stata salvata.
 */
@Component
class CertFitTrigger(private val jobs: JobService) {
	@EventListener
	fun on(event: CertFitRequested) {
		jobs.enqueueForCertificate(JobKind.CERT_FIT, event.certificateId, event.requestedBy)
	}
}

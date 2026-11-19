package it.polito.measurestream.metrology.common

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

/** Un'azione che chi chiama non puo' fare: ruolo mancante, oggetto altrui. */
class Forbidden(message: String) : RuntimeException(message)

/** L'oggetto non esiste, o per chi chiama e' come se non esistesse. */
class NotFound(message: String) : RuntimeException(message)

/** L'azione non e' ammessa nello stato attuale: transizione illecita, quattro occhi violati. */
class Conflict(message: String) : RuntimeException(message)

/** La richiesta e' malformata o incompleta. */
class BadRequest(message: String) : RuntimeException(message)

/**
 * Errori come ProblemDetail (RFC 9457), con un messaggio in italiano che l'interfaccia puo'
 * mostrare cosi' com'e': chi riceve un 409 deve sapere quale regola ha urtato.
 */
@RestControllerAdvice
class ApiErrors {
	private val log = LoggerFactory.getLogger(ApiErrors::class.java)

	@ExceptionHandler(Forbidden::class)
	fun forbidden(e: Forbidden) = problem(HttpStatus.FORBIDDEN, e.message)

	@ExceptionHandler(NotFound::class)
	fun notFound(e: NotFound) = problem(HttpStatus.NOT_FOUND, e.message)

	@ExceptionHandler(Conflict::class)
	fun conflict(e: Conflict) = problem(HttpStatus.CONFLICT, e.message)

	@ExceptionHandler(BadRequest::class, IllegalArgumentException::class)
	fun badRequest(e: RuntimeException) = problem(HttpStatus.BAD_REQUEST, e.message)

	@ExceptionHandler(MaxUploadSizeExceededException::class)
	fun tooLarge(e: MaxUploadSizeExceededException) =
		problem(HttpStatus.PAYLOAD_TOO_LARGE, "Il file supera il limite di 20 MB")

	/** Un vincolo del database: spesso e' un trigger di immutabilita' che ha fatto il suo lavoro. */
	@ExceptionHandler(DataIntegrityViolationException::class)
	fun integrity(e: DataIntegrityViolationException): ProblemDetail {
		log.warn("Vincolo del database: {}", e.mostSpecificCause.message)
		return problem(HttpStatus.CONFLICT, e.mostSpecificCause.message?.lineSequence()?.firstOrNull())
	}

	private fun problem(status: HttpStatus, message: String?): ProblemDetail =
		ProblemDetail.forStatusAndDetail(status, message ?: status.reasonPhrase)
}

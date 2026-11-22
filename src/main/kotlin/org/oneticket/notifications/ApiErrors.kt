package org.oneticket.notifications

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.ErrorResponse
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** RFC 9457 problem document, the single error format of the platform ([API-04]). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Problem(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String?,
    val instance: String,
    val code: String
)

/**
 * Base class of the errors the API raises on purpose. Each route adds its own subclasses
 * (e.g. `EmailNotFoundException`, `EmailAlreadyQueuedException`) with a stable `code` ([STY-79]).
 */
open class ApiException(
    val status: HttpStatus,
    val slug: String,
    val title: String,
    val code: String,
    detail: String
) : RuntimeException(detail)

/** Single place that turns every failure into `application/problem+json` ([API-04], [OPS-17]). */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    private fun build(
        status: HttpStatus,
        slug: String,
        title: String,
        code: String,
        detail: String?,
        req: HttpServletRequest,
        retryAfter: Int? = null,
        extraHeaders: HttpHeaders? = null,
    ): ResponseEntity<Problem> {
        val builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
        if (extraHeaders != null)
            builder.headers(extraHeaders)
        if (retryAfter != null)
            builder.header(HttpHeaders.RETRY_AFTER, retryAfter.toString())
        return builder.body(
            Problem(
                "/problems/$slug",
                title, status.value(),
                detail,
                req.requestURI,
                code
            )
        )
    }

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException, req: HttpServletRequest) =
        build(e.status, e.slug, e.title, e.code, e.message, req)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun malformedBody(e: HttpMessageNotReadableException, req: HttpServletRequest) =
        build(
            HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request", "MALFORMED_REQUEST",
            "The request body is not valid JSON, has unknown fields or fields of the wrong type.", req
        )

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun unsupported(e: HttpMediaTypeNotSupportedException, req: HttpServletRequest) =
        build(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type", "Unsupported media type",
            "UNSUPPORTED_MEDIA_TYPE", "This operation consumes application/json.", req
        )

    @ExceptionHandler(HttpMediaTypeNotAcceptableException::class)
    fun notAcceptable(e: HttpMediaTypeNotAcceptableException, req: HttpServletRequest) =
        build(
            HttpStatus.NOT_ACCEPTABLE, "not-acceptable", "Not acceptable",
            "NOT_ACCEPTABLE", "This operation produces application/json.", req
        )

    // Database down or pool exhausted: JPA surfaces it as CannotCreateTransactionException, plain JDBC
    // as CannotGetJdbcConnectionException ([API-19]: 503 carries Retry-After).
    @ExceptionHandler(
        DataAccessResourceFailureException::class,
        CannotGetJdbcConnectionException::class,
        CannotCreateTransactionException::class
    )
    fun dbDown(e: Exception, req: HttpServletRequest): ResponseEntity<Problem> {
        log.error("Database unavailable", e)
        return build(
            HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable", "Service unavailable",
            "SERVICE_UNAVAILABLE", "The service is unavailable; retry later.", req, retryAfter = 5
        )
    }

    @ExceptionHandler(Exception::class)
    fun other(e: Exception, req: HttpServletRequest): ResponseEntity<Problem> {
        if (e is ErrorResponse) {
            val s = HttpStatus.valueOf(e.statusCode.value())
            // Wrong query parameter type, missing parameter or header...: the contract's BadRequest.
            if (s == HttpStatus.BAD_REQUEST)
                return build(
                    s, "malformed-request", "Malformed request", "MALFORMED_REQUEST",
                    e.body.detail, req, extraHeaders = e.headers
                )
            // e.headers carries what HTTP requires, e.g. `Allow` on a 405 ([API-11]).
            return build(
                s, "http-error", s.reasonPhrase, "HTTP_${s.value()}", e.body.detail, req,
                extraHeaders = e.headers
            )
        }
        // Details go to the log with the correlation id, never to the client ([OPS-17]).
        log.error("Unexpected error", e)
        return build(
            HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal server error",
            "INTERNAL_ERROR", "An unexpected error occurred.", req
        )
    }
}

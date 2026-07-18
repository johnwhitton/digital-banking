package io.github.johnwhitton.digitalbanking.controlplane.api;

import java.net.URI;

import io.github.johnwhitton.digitalbanking.application.BankPreEffectFailureException;
import io.github.johnwhitton.digitalbanking.application.IdempotencyConflictException;
import io.github.johnwhitton.digitalbanking.application.InvalidRequestException;
import io.github.johnwhitton.digitalbanking.application.MockBankNotFoundException;
import io.github.johnwhitton.digitalbanking.application.OperationNotFoundException;
import io.github.johnwhitton.digitalbanking.application.TransferNotFoundException;
import io.github.johnwhitton.digitalbanking.application.UnknownAssetUnitException;
import io.github.johnwhitton.digitalbanking.application.UnsupportedRequestContractException;
import io.github.johnwhitton.digitalbanking.application.UnsupportedTransferConfigurationException;
import io.github.johnwhitton.digitalbanking.application.UsdzelleWorkflowNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class,
            HttpMessageNotReadableException.class,
            InvalidRequestException.class
    })
    ResponseEntity<ProblemDetail> badRequest(Exception ignored) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "invalid-request",
                "Invalid request",
                "The request did not satisfy the API contract.");
    }

    @ExceptionHandler(UnauthenticatedPrincipalException.class)
    ResponseEntity<ProblemDetail> unauthenticated(UnauthenticatedPrincipalException ignored) {
        return problem(
                HttpStatus.UNAUTHORIZED,
                "unauthenticated",
                "Authentication required",
                "An authenticated participant identity is required.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> unsupportedMedia(HttpMediaTypeNotSupportedException ignored) {
        return problem(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "unsupported-media-type",
                "Unsupported media type",
                "Use application/json for this request.");
    }

    @ExceptionHandler({
            UnknownAssetUnitException.class,
            UnsupportedRequestContractException.class,
            UnsupportedTransferConfigurationException.class
    })
    ResponseEntity<ProblemDetail> unprocessable(RuntimeException ignored) {
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "unprocessable-request",
                "Unprocessable request",
                "The request uses an unsupported contract, asset unit, or transfer route.");
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> conflict(IdempotencyConflictException ignored) {
        return problem(
                HttpStatus.CONFLICT,
                "idempotency-conflict",
                "Idempotency conflict",
                "The idempotency key is already bound to a different request.");
    }

    @ExceptionHandler(OperationNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(OperationNotFoundException ignored) {
        return problem(
                HttpStatus.NOT_FOUND,
                "operation-not-found",
                "Operation not found",
                "The operation was not found.");
    }

    @ExceptionHandler(TransferNotFoundException.class)
    ResponseEntity<ProblemDetail> transferNotFound(TransferNotFoundException ignored) {
        return problem(
                HttpStatus.NOT_FOUND,
                "transfer-not-found",
                "Transfer not found",
                "The transfer was not found.");
    }

    @ExceptionHandler(UsdzelleWorkflowNotFoundException.class)
    ResponseEntity<ProblemDetail> workflowNotFound(UsdzelleWorkflowNotFoundException ignored) {
        return problem(
                HttpStatus.NOT_FOUND,
                "usdzelle-workflow-not-found",
                "USDZELLE workflow not found",
                "The USDZELLE workflow was not found.");
    }

    @ExceptionHandler(MockBankNotFoundException.class)
    ResponseEntity<ProblemDetail> mockBankNotFound(MockBankNotFoundException ignored) {
        return problem(
                HttpStatus.NOT_FOUND,
                "local-resource-not-found",
                "Local resource not found",
                "The synthetic local resource was not found.");
    }

    @ExceptionHandler({
            DataAccessException.class,
            TransactionException.class,
            BankPreEffectFailureException.class
    })
    ResponseEntity<ProblemDetail> serviceUnavailable(RuntimeException ignored) {
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "service-unavailable",
                "Service unavailable",
                "Durable acceptance is temporarily unavailable.");
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<ProblemDetail> internalError(RuntimeException failure) {
        LOGGER.error("Unexpected internal invariant failure", failure);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error",
                "Internal server error",
                "An unexpected internal error occurred.");
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:digital-banking:problem:" + type));
        problem.setTitle(title);
        problem.setInstance(URI.create(
                "urn:digital-banking:problem-instance:undisclosed"));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}

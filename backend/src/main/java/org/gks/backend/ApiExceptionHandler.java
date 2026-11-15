package org.gks.backend;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/** Denetleyici hatalarını §4.1 {@code ProblemDetail} biçimine çevirir. */
@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> handle(ApiException ex, HttpServletRequest request) {
		ProblemDetail problem = ApiProblems.problem(request, ex.code());
		if (ex.status() != null) {
			problem.setStatus(ex.status());
		}
		ex.extensions().forEach(problem::setProperty);
		if (!ex.errors().isEmpty()) {
			problem.setProperty("errors", ex.errors());
		}
		ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.getStatus());
		ex.headers().forEach(response::header);
		return response.body(problem);
	}

	/** Bozuk JSON, bilinmeyen alan (§3: reddedilir), yanlış tip veya geçersiz enum değeri. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ProblemDetail> handle(HttpMessageNotReadableException ex, HttpServletRequest request) {
		List<ApiException.FieldError> errors = switch (ex.getCause()) {
			case UnrecognizedPropertyException e -> List.of(new ApiException.FieldError(
					path(e), "UNKNOWN_FIELD", "Bu alan tanınmıyor"));
			case InvalidFormatException e when e.getTargetType() != null && e.getTargetType().isEnum() ->
				List.of(new ApiException.FieldError(path(e), "NOT_ALLOWED_VALUE", "Değer izin verilenler arasında değil"));
			case MismatchedInputException e when !e.getPath().isEmpty() -> List.of(new ApiException.FieldError(
					path(e), "INVALID_FORMAT", "Değerin biçimi geçersiz"));
			case null, default -> List.of();
		};
		return handle(ApiException.validation(errors), request);
	}

	/** Yol parametresi UUID değilse böyle bir kaynak olamaz. */
	@ExceptionHandler({ MethodArgumentTypeMismatchException.class, NoResourceFoundException.class })
	ResponseEntity<ProblemDetail> notFound(Exception ex, HttpServletRequest request) {
		return handle(ApiException.of(ApiProblems.Code.NOT_FOUND), request);
	}

	private static String path(JacksonException ex) {
		return ex.getPath().stream()
				.map(ref -> ref.getPropertyName() != null ? ref.getPropertyName() : "[" + ref.getIndex() + "]")
				.collect(Collectors.joining("."))
				.replace(".[", "[");
	}

}

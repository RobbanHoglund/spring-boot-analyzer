package com.robbanhoglund.springbootanalyzer.error;

import com.robbanhoglund.springbootanalyzer.application.InvalidRuleConfigException;
import com.robbanhoglund.springbootanalyzer.application.InvalidSourceSnippetRequestException;
import com.robbanhoglund.springbootanalyzer.application.SourceSnippetNotFoundException;
import com.robbanhoglund.springbootanalyzer.git.GitCloneException;
import com.robbanhoglund.springbootanalyzer.git.InvalidRepositoryReferenceException;
import com.robbanhoglund.springbootanalyzer.git.UnsupportedRepositoryProtocolException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException exception) {
        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problemDetail.setTitle("Validation failed");
        problemDetail.setDetail("Request validation failed.");

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        problemDetail.setProperty("errors", fieldErrors);
        return problemDetail;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleNotReadable(HttpMessageNotReadableException exception) {
        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problemDetail.setTitle("Malformed request");
        problemDetail.setDetail(
                "The request body could not be read. Check that all field values are valid (e.g."
                        + " analysisMode must be STATIC_ONLY or EXTENDED).");
        return problemDetail;
    }

    @ExceptionHandler({
        InvalidRepositoryReferenceException.class,
        UnsupportedRepositoryProtocolException.class
    })
    public ProblemDetail handleBadRequest(RuntimeException exception) {
        LOGGER.warn("Invalid repository request: {}", exception.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problemDetail.setTitle("Invalid repository request");
        problemDetail.setDetail(
                "Repository URL could not be accepted. Use an HTTPS or SSH repository URL without"
                        + " embedded credentials.");
        return problemDetail;
    }

    @ExceptionHandler(GitCloneException.class)
    public ProblemDetail handleCloneFailure(GitCloneException exception) {
        LOGGER.warn("Repository clone failed: {}", exception.getMessage(), exception);
        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        problemDetail.setTitle("Repository clone failed");
        problemDetail.setDetail(
                "Repository could not be cloned. Check that the URL and branch exist, and that the"
                        + " selected token profile can read the repository contents. For private"
                        + " GitHub repositories, use a token with repository read access or"
                        + " fine-grained Contents: Read permission.");
        return problemDetail;
    }

    @ExceptionHandler(InvalidRuleConfigException.class)
    public ProblemDetail handleInvalidRuleConfig(InvalidRuleConfigException exception) {
        LOGGER.warn("Invalid rule configuration request: {}", exception.getMessage());
        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problemDetail.setTitle("Invalid rule configuration");
        problemDetail.setDetail("Rule configuration request is invalid.");
        return problemDetail;
    }

    @ExceptionHandler(InvalidSourceSnippetRequestException.class)
    public ProblemDetail handleInvalidSourceSnippetRequest(
            InvalidSourceSnippetRequestException exception) {
        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problemDetail.setTitle("Invalid source snippet request");
        problemDetail.setDetail("Source snippet request is invalid.");
        return problemDetail;
    }

    @ExceptionHandler(SourceSnippetNotFoundException.class)
    public ProblemDetail handleSourceSnippetNotFound(SourceSnippetNotFoundException exception) {
        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problemDetail.setTitle("Source snippet unavailable");
        problemDetail.setDetail(
                "Source snippet is no longer available or the requested file could not be found.");
        return problemDetail;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException exception) {
        LOGGER.error("Analysis failed", exception);
        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problemDetail.setTitle("Analysis failed");
        problemDetail.setDetail("Analysis failed unexpectedly. Check server logs for details.");
        return problemDetail;
    }
}

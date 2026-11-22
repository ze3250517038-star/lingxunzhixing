package cn.sgu.lingxun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> status(ResponseStatusException error) {
        HttpStatus status = HttpStatus.resolve(error.getStatusCode().value());
        String message = error.getReason();
        if (message == null || message.isBlank()) {
            message = status == null ? "请求未完成" : status.getReasonPhrase();
        }
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", message));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException error,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return new ResponseEntity<>(Map.of("message", "请求内容格式有误，请检查后重试。"), headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception error,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String message = switch (status.value()) {
            case 400 -> "请求内容格式有误，请检查后重试。";
            case 404 -> "请求的资源不存在。";
            case 405 -> "请求方法不受支持。";
            case 415 -> "请求的内容类型不受支持。";
            default -> {
                HttpStatus resolved = HttpStatus.resolve(status.value());
                yield resolved == null ? "请求未完成" : resolved.getReasonPhrase();
            }
        };
        return new ResponseEntity<>(Map.of("message", message), headers, status);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception error) {
        log.error("未处理的服务端异常", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "服务器暂时无法完成请求，请稍后重试。"));
    }
}

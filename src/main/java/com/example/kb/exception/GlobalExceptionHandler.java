package com.example.kb.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理：统一把 BusinessException / 上传超限 / 参数校验错误转成结构化 JSON。
 */
@RestControllerAdvice
@Slf4j // 启用日志框架，默认输出到控制台和 logback-spring.xml 配置的 kb-error.log 中
public class GlobalExceptionHandler {

    /**
     * 1. 捕获自定义的业务异常 (如: 文件过大、空文档、格式不支持)
     * 使用 WARN 级别，因为是用户传参导致的，不算系统崩溃
     */
    @ExceptionHandler(BusinessException.class)
    public Map<String, String> handleBusinessException(BusinessException e, HttpServletRequest request) {
        // 警告日志会进入 kb-info.log 还是 kb-error.log 取决于你的 logback 配置
        // 这里我们用 warn 打印到控制台，并附带请求上下文
        log.warn("业务异常 [{}], IP: {}, 路径: {}, 错误信息: {}",
                request.getMethod() + " " + request.getRequestURI(),
                getIpAddress(request),
                request.getRequestURI(),
                e.getMessage());

        return Map.of("code", e.getCode(), "message", e.getMessage());
    }

    /**
     * 2. 捕获系统未知的 RuntimeException (系统兜底逻辑)
     * 使用 ERROR 级别，专门适配 kb-error.log 的格式
     */
    @ExceptionHandler(RuntimeException.class)
    public Map<String, String> handleRuntimeException(RuntimeException e, HttpServletRequest request) {
        // 【关键适配】打印完整的上下文和异常堆栈，方便排查
        log.error("系统运行时异常 | IP: {} | 请求路径: {} | 参数: {} | 异常堆栈如下:",
                getIpAddress(request),
                request.getMethod() + " " + request.getRequestURI(),
                // 安全地获取部分请求参数作为排查线索
                String.valueOf(request.getParameterMap()),
                e); // 把 Exception 对象放在最后一个参数，Logback 会自动打印完整堆栈到 kb-error.log

        return Map.of("code", "SYSTEM_ERROR", "message", "服务器内部错误，请稍后重试");
    }

    /**
     * 3. 捕获 Spring 自身的参数绑定异常 (如: 必填参数缺失、类型不对)
     */
    @ExceptionHandler(BindException.class)
    public Map<String, String> handleBindException(BindException e, HttpServletRequest request) {
        log.warn("参数绑定异常 [{}], IP: {}, 路径: {}, 错误信息: {}",
                request.getMethod() + " " + request.getRequestURI(),
                getIpAddress(request),
                request.getRequestURI(),
                e.getBindingResult().getFieldErrors().get(0).getDefaultMessage());

        return Map.of("code", "BAD_REQUEST", "message", e.getBindingResult().getFieldErrors().get(0).getDefaultMessage());
    }

    /**
     * 辅助方法：获取客户端真实 IP
     */
    private String getIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
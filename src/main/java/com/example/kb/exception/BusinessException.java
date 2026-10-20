package com.example.kb.exception;

import lombok.Getter;

/**
 * 统一业务异常：携带错误码，便于全局异常处理返回结构化响应。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

}
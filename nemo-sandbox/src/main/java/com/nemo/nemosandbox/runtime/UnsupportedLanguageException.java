package com.nemo.nemosandbox.runtime;

/**
 * 不支持的语言异常 —— Sandbox 层终局拒绝，不应触发 MQ 重试。
 */
public class UnsupportedLanguageException extends RuntimeException {

    private final String language;

    public UnsupportedLanguageException(String language) {
        super("Unsupported language: " + language);
        this.language = language;
    }

    public String getLanguage() {
        return language;
    }
}

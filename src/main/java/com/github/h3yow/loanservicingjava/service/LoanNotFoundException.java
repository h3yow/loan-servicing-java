package com.github.h3yow.loanservicingjava.service;

/**
 * 贷款不存在。
 */
public class LoanNotFoundException extends RuntimeException {

    public LoanNotFoundException(String loanId) {
        super("贷款不存在: " + loanId);
    }
}

package com.tossinvest.tossinvestbackend.account;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 계좌 목록 조회 응답.
 * accountSeq는 다른 사용자 컨텍스트 API의 X-Tossinvest-Account 헤더 값으로 사용된다.
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccountResponse {

    private List<Account> result;

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Account {
        private String accountNo;
        private Long accountSeq;
        private String accountType;
    }
}

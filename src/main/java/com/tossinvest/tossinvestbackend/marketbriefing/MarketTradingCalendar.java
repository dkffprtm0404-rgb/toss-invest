package com.tossinvest.tossinvestbackend.marketbriefing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** KRX's public cash-market holiday list, cached by year. Unknown years fail closed. */
@Component
public class MarketTradingCalendar {
    public static final String SOURCE="https://open.krx.co.kr/contents/MKD/01/0110/01100305/MKD01100305.jsp";
    private final PublicMarketHttp http;
    private final ObjectMapper mapper;
    private record Year(Set<LocalDate> holidays,Instant loadedAt){ }
    private final Map<Integer,Year> years=new ConcurrentHashMap<>();
    public MarketTradingCalendar(PublicMarketHttp http,ObjectMapper mapper){this.http=http;this.mapper=mapper;}
    public boolean isTradingDay(LocalDate date){
        if(date.getDayOfWeek()==DayOfWeek.SATURDAY||date.getDayOfWeek()==DayOfWeek.SUNDAY)return false;
        return !holidays(date.getYear()).contains(date);
    }
    private synchronized Set<LocalDate> holidays(int year){
        Year cached=years.get(year);
        if(cached!=null&&cached.loadedAt().isAfter(Instant.now().minus(Duration.ofHours(24))))return cached.holidays();
        try {
            String code=http.postForm(URI.create("https://open.krx.co.kr/contents/COM/GenerateOTP.jspx"),
                    Map.of("name","form","bld","MKD/01/0110/01100305/mkd01100305_01")).trim();
            if(code.isEmpty()||code.length()>2048||code.contains("<"))throw new IllegalStateException("휴장일 조회 실패");
            var dates=parseHolidays(http.postForm(URI.create("https://open.krx.co.kr/contents/OPN/99/OPN99000001.jspx"),
                    Map.of("code",code,"search_bas_yy",Integer.toString(year))),year,mapper);
            years.put(year,new Year(dates,Instant.now()));return dates;
        }catch(RuntimeException e){
            // The bundled list is the official response captured on 2026-11-05, not a guessed calendar.
            if(cached!=null)return cached.holidays();
            try(var input=getClass().getResourceAsStream("/marketbriefing/krx-holidays-"+year+".json")){
                if(input!=null){var dates=parseHolidays(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),year,mapper);
                    years.put(year,new Year(dates,Instant.now()));return dates;}
            }catch(java.io.IOException ignored){ }
            throw new IllegalStateException("한국거래소 휴장일 자료를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
    static Set<LocalDate> parseHolidays(String value,int year,ObjectMapper mapper){
        try {
            var rows=mapper.readTree(value).path("block1");var result=new HashSet<LocalDate>();
            if(!rows.isArray())throw new IllegalArgumentException("휴장일 응답 형식 오류");
            for(var row:rows){var day=LocalDate.parse(row.path("calnd_dd").asText());
                if(day.getYear()!=year)throw new IllegalArgumentException("휴장일 연도 불일치");result.add(day);}
            if(result.isEmpty())throw new IllegalArgumentException("휴장일 자료 없음");
            return Set.copyOf(result);
        }catch(java.io.IOException|java.time.DateTimeException e){throw new IllegalArgumentException("휴장일 응답 형식 오류");}
    }
}

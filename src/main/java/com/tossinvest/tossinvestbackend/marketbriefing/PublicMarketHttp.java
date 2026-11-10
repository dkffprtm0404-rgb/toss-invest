package com.tossinvest.tossinvestbackend.marketbriefing;

import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** Public, fixed-provider requests only; article links are never fetched. */
@Component
public class PublicMarketHttp {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(7))
            .followRedirects(HttpClient.Redirect.NORMAL).cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build();
    public String get(URI uri){return read(HttpRequest.newBuilder(uri).GET());}
    public String postForm(URI uri,Map<String,String> values){
        String body=values.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(Collectors.joining("&"));
        return read(HttpRequest.newBuilder(uri).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)));
    }
    private String read(HttpRequest.Builder request){
        try {
            var response=client.send(request.timeout(Duration.ofSeconds(12)).header("User-Agent","TossInvest/1.0 market briefing")
                    .header("Accept","application/json,application/rss+xml,application/xml,text/plain").build(),HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream stream=response.body()){
                if(response.statusCode()!=200)throw new IllegalStateException("공개 자료 응답 오류");
                byte[] bytes=stream.readNBytes(1_048_577);
                if(bytes.length>1_048_576)throw new IllegalStateException("공개 자료 크기 초과");
                return new String(bytes,StandardCharsets.UTF_8);
            }
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("자료 수집 중단");}
        catch(java.io.IOException e){throw new IllegalStateException("공개 자료 연결 실패");}
    }
    static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}

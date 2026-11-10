package com.tossinvest.tossinvestbackend.marketbriefing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.*;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.tossinvest.tossinvestbackend.marketbriefing.MarketBriefing.*;

@Component
public class MarketBriefingSources {
    private final PublicMarketHttp http;private final ObjectMapper mapper;
    public MarketBriefingSources(PublicMarketHttp http,ObjectMapper mapper){this.http=http;this.mapper=mapper;}
    public Input collect(Instant cutoff){
        var sources=new ArrayList<Source>();var warnings=new ArrayList<String>();
        String[][] quotes={{"KOSPI","코스피","^KS11","pt"},{"KOSDAQ","코스닥","^KQ11","pt"},
                {"SP500","S&P 500","^GSPC","pt"},{"NASDAQ","나스닥","^IXIC","pt"},{"USDKRW","원/달러","KRW=X","KRW"}};
        for(var quote:quotes){try{
            String body=http.get(URI.create("https://query1.finance.yahoo.com/v8/finance/chart/"+PublicMarketHttp.encode(quote[2])+"?range=10d&interval=1d"));
            var source=parseQuote(body,quote[0],quote[1],quote[2],quote[3],cutoff);sources.add(source);
            if(source.observedAt().isBefore(cutoff.minus(Duration.ofDays(5))))warnings.add(quote[1]+" 자료가 5일 이상 지났습니다. 기준일을 확인해 주세요.");
        }catch(RuntimeException e){warnings.add(quote[1]+" 시세를 수집하지 못했습니다.");}}
        String[][] feeds={{"yna","연합뉴스","https://www.yna.co.kr/rss/economy.xml"},
                {"cnbc","CNBC","https://www.cnbc.com/id/100003114/device/rss/rss.html"}};
        Set<String> links=new HashSet<>();
        for(var feed:feeds){try{
            var articles=parseRss(http.get(URI.create(feed[2])),feed[0],feed[1],cutoff);
            if(articles.isEmpty())warnings.add(feed[1]+"의 기준 시각 이전 최근 뉴스가 없습니다.");
            for(var article:articles)if(links.add(article.url()))sources.add(article);
        }catch(RuntimeException e){warnings.add(feed[1]+" 뉴스를 수집하지 못했습니다.");}}
        return new Input(Instant.now(),List.copyOf(sources),List.copyOf(warnings));
    }
    Source parseQuote(String body,String id,String label,String symbol,String unit,Instant cutoff){
        try {
            var result=mapper.readTree(body).path("chart").path("result").path(0);
            ZoneId zone=ZoneId.of(result.path("meta").path("exchangeTimezoneName").asText());
            var times=result.path("timestamp");var closes=result.path("indicators").path("quote").path(0).path("close");
            BigDecimal previous=null,last=null;Instant observed=null;
            for(int i=0;i<times.size();i++){
                if(!closes.path(i).isNumber())continue;
                Instant time=Instant.ofEpochSecond(times.get(i).asLong());
                Instant end=symbol.equals("KRW=X")?time.plus(Duration.ofDays(1)):
                        time.atZone(zone).toLocalDate().atTime(zone.getId().equals("Asia/Seoul")?LocalTime.of(15,30):LocalTime.of(16,0)).atZone(zone).toInstant();
                BigDecimal price=closes.get(i).decimalValue();
                if(end.isAfter(cutoff)||price.signum()<=0)continue;
                previous=last;last=price;observed=end;
            }
            if(last==null||previous==null)throw new IllegalArgumentException("확정 시세 부족");
            BigDecimal change=last.divide(previous,10,RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
            String title=label+" 종가 "+last.stripTrailingZeros().toPlainString()+", 전거래일 대비 "+change.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()+"%";
            return new Source(id,"QUOTE",label,title,"https://finance.yahoo.com/quote/"+PublicMarketHttp.encode(symbol)+"/",observed,last,change,unit);
        }catch(java.io.IOException|DateTimeException e){throw new IllegalArgumentException("시세 응답 형식 오류");}
    }
    List<Source> parseRss(String body,String id,String label,Instant cutoff){
        try {
            var factory=DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            var builder=factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){@Override public void fatalError(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}});
            var items=builder.parse(new InputSource(new StringReader(body))).getElementsByTagName("item");var sources=new ArrayList<Source>();
            for(int i=0;i<items.getLength()&&sources.size()<6;i++){
                var item=(Element)items.item(i);String title=text(item,"title"),link=text(item,"link"),published=text(item,"pubDate");
                if(title.isBlank()||title.length()>500||!safeUrl(link))continue;
                Instant time;try{time=ZonedDateTime.parse(published,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();}
                catch(DateTimeException e){continue;}
                if(time.isAfter(cutoff)||time.isBefore(cutoff.minus(Duration.ofHours(36))))continue;
                sources.add(new Source(id+"-"+i,"NEWS",label,title,link,time,null,null,null));
            }
            return List.copyOf(sources);
        }catch(Exception e){throw new IllegalArgumentException("뉴스 응답 형식 오류");}
    }
    static boolean safeUrl(String value){
        if(value==null||value.length()>2048)return false;
        try{URI uri=URI.create(value);return "https".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null;}
        catch(IllegalArgumentException e){return false;}
    }
    private static String text(Element element,String name){var nodes=element.getElementsByTagName(name);return nodes.getLength()==0?"":nodes.item(0).getTextContent().strip();}
}

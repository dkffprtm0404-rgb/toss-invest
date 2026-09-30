package com.tossinvest.tossinvestbackend.strategypaper;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class PaperConflict extends RuntimeException {
    public PaperConflict(String message){super(message);}
}

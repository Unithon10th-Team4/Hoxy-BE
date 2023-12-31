package unitjon.th10.team4.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import unitjon.th10.team4.config.SseEmitters;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
@Log4j2
@RequestMapping("/sse")
public class SseController {
    private final SseEmitters sseEmitters;

    @GetMapping(value ="/connect",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> connectionSse(@RequestParam("memberName")String memberName){
        SseEmitter emitter = new SseEmitter(60*1000000L);
        sseEmitters.add(memberName,emitter);
        try {
            emitter.send(SseEmitter.event()
                    .name("connect")
                    .data("connected!"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return ResponseEntity.ok(emitter);
    }
}

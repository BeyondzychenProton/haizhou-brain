package com.haizhuo.brain.api.meeting;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/meetings")
public class MeetingController {
    @GetMapping("/status")
    public Mono<String> status() {
        return Mono.just("meeting-api-ready");
    }
}

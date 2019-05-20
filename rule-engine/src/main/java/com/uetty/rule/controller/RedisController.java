package com.uetty.rule.controller;

import com.uetty.cloud.feign.api.api.engine.RedisLuaApi;
import com.uetty.rule.entity.User;
import com.uetty.rule.service.RedisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/redis")
public class RedisController implements RedisLuaApi {

    private final RedisService redisService;

    public RedisController(RedisService redisService) {
        this.redisService = redisService;
    }

    @GetMapping("/classPut")
    public Mono classPut(Integer userId,String userName) {
        User user = new User();
        user.setUserId(userId);
        user.setUserName(userName);
        return redisService.classPut("user:detail", user);
    }

    @GetMapping("/classGet")
    public Mono classGet(Integer userId){
        return redisService.classGet("user:detail", userId);
    };

    @GetMapping("/getHashFromZset")
    public Mono getHashFromZset() {
        return redisService.getHashFromZset("user:sroce", "user:detail", "0", "-1");
    }
}

package com.uetty.rule.controller;

import com.google.common.collect.Lists;
import com.uetty.rule.config.redis.template.RedisTemplateRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/redis")
public class RedisController {

    private final RedisTemplateRule redisTemplateRule;


    private DefaultRedisScript<Object> hget;

    @Autowired
    public RedisController(RedisTemplateRule redisTemplateRule) {
        this.redisTemplateRule = redisTemplateRule;
    }

    @RequestMapping("/script")
    public Mono script() {
        return redisTemplateRule.execute(hget, Lists.newArrayList("aaa"), Lists.newArrayList("1")).collectList();
    }
}
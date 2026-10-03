package com.shopagent.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.shopagent.mapper")
public class MybatisPlusConfig {
}

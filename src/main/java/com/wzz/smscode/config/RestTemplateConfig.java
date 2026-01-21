package com.wzz.smscode.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

/**
 * RestTemplate 配置类
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 连接超时（毫秒）：与第三方握手的时间。
        // 如果对方挂了或者网络不通，3秒内就会抛出异常，不会傻等。
        factory.setConnectTimeout(10000);
        // 读取超时（毫秒）：对方接口处理逻辑的时间。
        // 这里设置为 5000ms (5秒)。
        factory.setReadTimeout(15000);
        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.getMessageConverters().stream()
                .filter(converter -> converter instanceof StringHttpMessageConverter)
                .forEach(converter -> ((StringHttpMessageConverter) converter).setDefaultCharset(StandardCharsets.UTF_8));

        return restTemplate;
    }
}
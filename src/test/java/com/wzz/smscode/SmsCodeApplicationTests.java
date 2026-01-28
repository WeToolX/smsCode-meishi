package com.wzz.smscode;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.ParseContext;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Slf4j
@SpringBootTest
class SmsCodeApplicationTests {

    private static final Configuration JSON_PATH_CONFIG =
            Configuration.defaultConfiguration()
                    .addOptions(Option.SUPPRESS_EXCEPTIONS);

    private static final ParseContext JSON_PARSER =
            JsonPath.using(JSON_PATH_CONFIG);

    @Test
    void testJsonPathMsg() {
        String json = "{\"msg\":\"18378876855\",\"code\":\"1001\"}";

        Object val = JSON_PARSER
                .parse(json)
                .read("$.msg");

        System.out.println("JSONPath 提取结果 = " + val);
        log.info("JSONPath 提取结果 = {}", val);
    }
}

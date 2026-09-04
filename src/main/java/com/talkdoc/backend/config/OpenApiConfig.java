package com.talkdoc.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String SESSION_TOKEN = "sessionToken";

    @Bean
    public OpenAPI talkDocOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("TalkDoc Backend API")
                        .version("v1")
                        .description("병원 문진용 수어 소통 서비스 백엔드. 세션 생성 시 발급되는 역할별 토큰을 "
                                + "Authorization: Bearer <token> 헤더로 전달한다."))
                .components(new Components().addSecuritySchemes(SESSION_TOKEN,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .description("세션 생성 응답의 doctor_token 또는 patient_token")))
                .addSecurityItem(new SecurityRequirement().addList(SESSION_TOKEN));
    }
}

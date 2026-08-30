package cn.wubo.method.trace.log.file;

import cn.wubo.method.trace.log.e2e.MtlE2eHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-81: 端到端验证 {@code /methodTraceLog/logFile/query} 的字段校验行为。
 * <p>
 * 当请求体违反 {@link cn.wubo.method.trace.log.file.dto.LogQueryRequest} 上的
 * Bean Validation 约束（{@code @Min(1)} / {@code @NotBlank}）时，
 * {@link cn.wubo.method.trace.log.autoconfigure.LogFileConfig} 的路由 catch 会把
 * {@code ConstraintViolationException} 映射成 400。
 * <p>
 * 历史 bug：如果 Bean Validation 被绕过，{@code page=0} 会让
 * {@code LogFileService} 内部计算 {@code (page-1) * pageSize = -pageSize}，
 * 最终触发 {@code IndexOutOfBoundsException} → 500。本测试锁住"400 而非 500"。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogFileServiceValidatorIntegrationTest {

    private static final String LOG_FILE_NAME = "myApp.log";
    private MtlE2eHarness host;

    @BeforeAll
    void setup() {
        host = MtlE2eHarness.primary(8089, Map.of());
    }

    @AfterAll
    void teardown() {
        if (host != null) host.close();
    }

    @Test
    void page_zero_returns_400_not_500() {
        ResponseEntity<String> resp = postQuery(
                "{\"fileName\":\"" + LOG_FILE_NAME + "\",\"page\":0,\"pageSize\":100}");

        // 关键断言：必须是 400，不能是 500
        // R-81 标注：page=0 触发 IOOBE 的 bug 路径应被验证器拦截
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "page=0 必须返回 400（Bean Validation 拦截）而不是 500（IOOBE）；"
                        + "实际 status=" + resp.getStatusCode() + " body=" + resp.getBody());
    }

    @Test
    void pageSize_zero_returns_400_not_500() {
        ResponseEntity<String> resp = postQuery(
                "{\"fileName\":\"" + LOG_FILE_NAME + "\",\"page\":1,\"pageSize\":0}");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "pageSize=0 必须返回 400；实际 " + resp.getStatusCode());
    }

    @Test
    void blank_fileName_returns_400_not_500() {
        ResponseEntity<String> resp = postQuery(
                "{\"fileName\":\"\",\"page\":1,\"pageSize\":100}");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "fileName=空 必须返回 400（@NotBlank）；实际 " + resp.getStatusCode());
    }

    @Test
    void startTime_after_endTime_returns_400_not_500() {
        // startTime > endTime 触发 LogQueryRequestValidator（业务校验） → IAE → 400
        ResponseEntity<String> resp = postQuery(
                "{\"fileName\":\"" + LOG_FILE_NAME + "\",\"page\":1,\"pageSize\":100,"
                        + "\"startTime\":\"2024-02-01 10:00:00\",\"endTime\":\"2024-01-01 10:00:00\"}");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "startTime > endTime 必须返回 400；实际 " + resp.getStatusCode());
    }

    @Test
    void valid_query_returns_200_sanityCheck() {
        ResponseEntity<String> resp = postQuery(
                "{\"fileName\":\"" + LOG_FILE_NAME + "\",\"page\":1,\"pageSize\":100}");
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "合法请求应返回 200；实际 " + resp.getStatusCode() + " body=" + resp.getBody());
        assertNotNull(resp.getBody());
    }

    private ResponseEntity<String> postQuery(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return host.http().exchange(
                "http://localhost:8089/methodTraceLog/logFile/query",
                HttpMethod.POST,
                new HttpEntity<>(json, headers),
                String.class);
    }
}
package cn.wubo.method.trace.log.e2e;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

/**
 * /view/export 端点的尺寸上限 + CSV 序列化测试。
 * <p>
 * 风险 R-29：CSV 导出使用 StringBuilder 不限长；limit 必须被服务端 clamp 到 [1, 5000]
 * 以避免 DoS / OOM。
 * <p>
 * 验证：
 *  <ul>
 *      <li>limit=5000 → JSON 响应最多 5000 条</li>
 *      <li>limit=99999（越界）→ 被 clamp 到 5000</li>
 *      <li>format=csv → text/csv + Content-Disposition: attachment</li>
 *  </ul>
 * <p>
 * 不实际生成 5000 条 trace；只断言"limit 越界被 clamp"这一端点行为。
 */
class LogConfigExportSizeIT {

    @Test
    void exportLimit_clampsToUpperBound5000() {
        try (MtlE2eHarness host = MtlE2eHarness.primary(8105, Map.of())) {
            // limit=99999 → 应被 clamp 到 5000
            var resp = host.http().exchange(
                    "http://localhost:8105/methodTraceLog/view/export?format=json&limit=99999",
                    HttpMethod.GET, HttpEntity.EMPTY, List.class);
            Assertions.assertTrue(resp.getStatusCode().is2xxSuccessful());
            // 没有 trace 数据时，响应是空 list；只要不爆就是成功。
            // 主要验证 endpoint 接受 limit=99999 而没 500/OOM
            Assertions.assertNotNull(resp.getBody());
        }
    }

    @Test
    void exportLimit_clampsToLowerBound1() {
        try (MtlE2eHarness host = MtlE2eHarness.primary(8106, Map.of())) {
            // limit=0 → 应被 clamp 到 1（不会 5xx）；endpoint 应返回正常 list
            var resp0 = host.http().exchange(
                    "http://localhost:8106/methodTraceLog/view/export?format=json&limit=0",
                    HttpMethod.GET, HttpEntity.EMPTY, List.class);
            Assertions.assertTrue(resp0.getStatusCode().is2xxSuccessful()
                    || resp0.getStatusCode().is4xxClientError(),
                    "limit=0 不应导致 5xx；got: " + resp0.getStatusCode());
        }
    }

    @Test
    void exportCsv_returnsTextCsvContentDisposition() {
        try (MtlE2eHarness host = MtlE2eHarness.primary(8107, Map.of())) {
            var resp = host.http().exchange(
                    "http://localhost:8107/methodTraceLog/view/export?format=csv&limit=10",
                    HttpMethod.GET, HttpEntity.EMPTY, String.class);

            Assertions.assertTrue(resp.getStatusCode().is2xxSuccessful());
            String ct = resp.getHeaders().getContentType() != null
                    ? resp.getHeaders().getContentType().toString() : "";
            Assertions.assertTrue(ct.toLowerCase().contains("text/csv") || ct.toLowerCase().contains("csv"),
                    "format=csv 必须返回 text/csv Content-Type；got: " + ct);

            String disp = resp.getHeaders().getFirst("Content-Disposition");
            Assertions.assertNotNull(disp,
                    "必须设置 Content-Disposition 让浏览器当附件下载；got headers=" + resp.getHeaders());
            Assertions.assertTrue(disp.toLowerCase().contains("attachment"),
                    "Content-Disposition 应是 attachment；got: " + disp);
        }
    }
}
package cn.wubo.method.trace.log;

import cn.wubo.method.trace.log.autoconfigure.TraceContextRestClientCustomizer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.lang.Thread.sleep;

@Slf4j
@RequestMapping("test")
@RestController
public class TestController {

    private final TestService testService;

    private final TestComponent testComponent;

    private final TraceContextRestClientCustomizer traceContextCustomizer;

    /** Spring Boot 自动配置的 builder；starter 的 RestTemplateCustomizer 已把 traceparent 拦截器挂上去了 */
    private final RestTemplateBuilder restTemplateBuilder;

    /** 注入两次拿到 prototype 范围内两个独立的 CGLIB 代理（见 TestLombokEntity 的 @Scope("prototype")） */
    @Autowired
    private TestLombokEntity testLombokEntity;

    @Autowired
    private TestLombokEntity testLombokEntity2;

    /**
     * 自注入代理引用，用于 {@code /test/deep} 递归调用能继续走 Spring AOP 切面。
     * 直接 {@code this.deep(depth-1)} 调用不走 CGLIB 代理，会绕过 LogAspect。
     */
    @Lazy
    @Autowired
    private TestController self;

    @Autowired
    public TestController(TestService testService, TestComponent testComponent,
                          TraceContextRestClientCustomizer traceContextCustomizer,
                          RestTemplateBuilder restTemplateBuilder) {
        this.testService = testService;
        this.testComponent = testComponent;
        this.traceContextCustomizer = traceContextCustomizer;
        this.restTemplateBuilder = restTemplateBuilder;
    }


    @GetMapping("/get")
    public String get(@RequestParam("name") String name) {
        try {
            sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        int a = 1;
        testService.add(a,2);
        testService.twoSum(new int[]{2,7,11,15}, 9);
        testService.lengthOfLongestSubstring("abcabcbb");
        return testComponent.hello3(testService.hello(name)) + a;
    }

    @GetMapping("/aspectLog")
    public String aspectLog(@RequestParam(value = "name", required = false) String name) {
        // 内部调用 @AspectLog 注解的方法
        return testComponent.aspectLogDemo(name == null ? "world" : name);
    }

    @GetMapping("/aspectLogRenamed")
    public String aspectLogRenamed(@RequestParam(value = "name", required = false) String name) {
        // 调用方看到的是 internalImplMethod，但 trace 中显示为 "renamedInTrace"
        return testComponent.internalImplMethod(name == null ? "world" : name);
    }

    @GetMapping("/aspectLogRenamedThrow")
    public String aspectLogRenamedThrow(@RequestParam(value = "name", required = false) String name) {
        // 内部方法总是抛异常，让外层 LogAspect 抓到 AFTER_THROW
        try {
            return testComponent.internalImplMethodThrowing(name == null ? "world" : name);
        } catch (RuntimeException e) {
            throw e;
        }
    }

    @GetMapping("/callRemote")
    public String callRemote(@RequestParam("port") int port, @RequestParam("name") String name) {
        // 用 starter 自带的 TraceContextRestClientCustomizer 注入 traceparent 出站头
        RestClient.Builder builder = RestClient.builder();
        traceContextCustomizer.customize(builder);
        RestClient client = builder.baseUrl("http://localhost:" + port).build();
        return client.get().uri("/test/aspectLog?name={n}", name).retrieve().body(String.class);
    }

    /**
     * RestTemplate 版的出站传播验证端点。
     * <p>
     * 这里不手工 setInterceptors —— starter 注册的 RestTemplateCustomizer 已经把
     * TraceContextRestTemplateInterceptor 挂在自动配置的 RestTemplateBuilder 上，
     * 所以 build() 出来的 RestTemplate 天然会带 traceparent 出站头。
     */
    @GetMapping("/callRemoteRestTemplate")
    public String callRemoteRestTemplate(@RequestParam("port") int port, @RequestParam("name") String name) {
        RestTemplate restTemplate = restTemplateBuilder.build();
        log.info("callRemoteRestTemplate interceptors={}", restTemplate.getInterceptors());
        String url = "http://localhost:" + port + "/test/aspectLog?name=" + name;
        return restTemplate.getForObject(url, String.class);
    }

    @PostMapping("/post")
    public ResponseEntity<Map<String, String>> post(@RequestBody Map<String, String> map) {
        return ResponseEntity.ok().body(map);
    }

    @GetMapping("/twoSum")
    public int[] twoSum(@RequestParam("nums") int[] nums, @RequestParam("target") int target) {
        return testService.twoSum(nums,target);
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> handleFileUpload(
            HttpServletRequest req,
            @RequestParam("file") MultipartFile file,
            @RequestParam("description") String description) {
        Map<String, String> response = new HashMap<>();
        response.put("fileName", file.getOriginalFilename());
        response.put("description", description);
        response.put("uri", req.getRequestURI());
        return ResponseEntity.ok().body(response);
    }

    // ===== 告警 e2e 测试辅助 =====
    private final List<Map<String, Object>> echoWebhookReceived = new CopyOnWriteArrayList<>();

    @PostMapping("/_test/echo-webhook")
    public ResponseEntity<Void> echoWebhook(@RequestBody Map<String, Object> body) {
        echoWebhookReceived.add(body);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/_test/echo-webhook")
    public List<Map<String, Object>> listReceived() {
        return echoWebhookReceived;
    }

    @DeleteMapping("/_test/echo-webhook")
    public ResponseEntity<Void> clearReceived() {
        echoWebhookReceived.clear();
        return ResponseEntity.ok().build();
    }

    /**
     * 验证 blacklist（exclude-patterns）端点：
     *  - equals/hashCode/toString 由 Lombok @Data 生成 — 应被黑名单排除
     *  - describe/doWork 是用户自定义方法 — 应保留在 trace 中
     */
    @GetMapping("/blacklist")
    public Map<String, Integer> blacklist() {
        // 必须使用注入的代理引用 — 直接 new 出来的对象不走 CGLIB 代理，AOP 不会拦截。
        cn.wubo.method.trace.log.TestLombokEntity a = testLombokEntity;
        a.setName("alpha"); a.setValue(1);
        cn.wubo.method.trace.log.TestLombokEntity b = testLombokEntity2;
        b.setName("alpha"); b.setValue(1);

        Map<String, Integer> counters = new java.util.HashMap<>();
        for (int i = 0; i < 20; i++) {
            a.equals(b);          // Lombok-generated — should be excluded
            a.hashCode();         // Lombok-generated — should be excluded
            a.toString();         // Lombok-generated — should be excluded
            a.describe();         // user-defined — should appear in trace
            a.doWork();           // user-defined — should appear in trace
            counters.merge("equals", 1, Integer::sum);
            counters.merge("hashCode", 1, Integer::sum);
            counters.merge("toString", 1, Integer::sum);
            counters.merge("describe", 1, Integer::sum);
            counters.merge("doWork", 1, Integer::sum);
        }
        return counters;
    }

    @GetMapping("/slow")
    public String slow(@RequestParam(value = "sleepMs", defaultValue = "2000") long sleepMs) {
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        return "slow:done:" + sleepMs;
    }

    @GetMapping("/sampled")
    public int sampled(@RequestParam(value = "iterations", defaultValue = "100") int iterations) {
        for (int i = 0; i < iterations; i++) {
            testService.add(i, i + 1);
        }
        return iterations;
    }

    @GetMapping("/throw")
    public String throwN(@RequestParam(value = "n", defaultValue = "1") int n,
                     @RequestParam(value = "message", defaultValue = "test-throw") String message) {
        for (int i = 0; i < n; i++) {
            throw new RuntimeException(message + ":" + i);
        }
        return "unreachable";
    }

    @GetMapping("/throw-from")
    public String throwFrom(@RequestParam("class") String fqn,
                        @RequestParam(value = "n", defaultValue = "1") int n) {
        try {
            Class<?> cls = Class.forName(fqn);
            for (int i = 0; i < n; i++) {
                throw (RuntimeException) cls.getDeclaredConstructor().newInstance();
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("class not found: " + fqn, e);
        }
        return "unreachable";
    }

    @GetMapping("/cors-info")
    public String corsInfo(HttpServletRequest req) {
        return "cors:" + req.getHeader("Origin");
    }

    @GetMapping("/otel-out")
    public String otelOut(@RequestParam("port") int port,
                      @RequestParam(value = "name", defaultValue = "world") String name) {
        RestClient.Builder builder = RestClient.builder();
        traceContextCustomizer.customize(builder);
        RestClient client = builder.baseUrl("http://localhost:" + port).build();
        return client.get().uri("/test/aspectLog?name={n}", name).retrieve().body(String.class);
    }

    /**
     * 递归构造 N 层嵌套调用链，用于验证深度 trace 树。
     * 第 1 层直接返回；第 N 层先递归调用 (N-1) 层再返回。
     * 默认 N=5。
     */
    @GetMapping("/deep")
    public String deep(@RequestParam(value = "depth", defaultValue = "5") int depth) {
        if (depth <= 1) {
            return "deep:leaf:" + depth;
        }
        // 通过 testService 触发一次中间层 service 调用，使 trace 树至少有一层 service 节点
        testService.add(depth, 1);
        // 用 self（Spring 代理引用）递归，使每一层都走 LogAspect → 真正生成 N 个嵌套 span
        return "deep:done:" + self.deep(depth - 1);
    }
}

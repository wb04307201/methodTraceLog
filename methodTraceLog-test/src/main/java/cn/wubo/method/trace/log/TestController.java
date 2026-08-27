package cn.wubo.method.trace.log;

import cn.wubo.method.trace.log.autoconfigure.TraceContextRestClientCustomizer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
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

    @Autowired
    public TestController(TestService testService, TestComponent testComponent,
                          TraceContextRestClientCustomizer traceContextCustomizer) {
        this.testService = testService;
        this.testComponent = testComponent;
        this.traceContextCustomizer = traceContextCustomizer;
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
}

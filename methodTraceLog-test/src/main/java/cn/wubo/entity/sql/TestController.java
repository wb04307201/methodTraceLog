package cn.wubo.entity.sql;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

import static java.lang.Thread.sleep;

@Slf4j
@RequestMapping("test")
@RestController
public class TestController {

    private final TestService testService;

    @Autowired
    public TestController(TestService testService) {
        this.testService = testService;
    }


    @GetMapping("/get")
    public String get(@RequestParam("name") String name) {
        try {
            sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return testService.hello(name);
    }

    @PostMapping("/post")
    public ResponseEntity<Map<String, String>> post(@RequestBody Map<String, String> map) {
        return ResponseEntity.ok().body(map);
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
}

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RequestMapping("test")
@RestController
public class TestController {

    @Autowired
    private TestService testService;

    @GetMapping("/get")
    public String get(@RequestParam("name") String name) {
        return testService.hello(name);
    }

    @PostMapping("/upload")
    public String handleFileUpload(
            HttpServletRequest req,
            @RequestParam("file") MultipartFile file,
            @RequestParam("description") String description) {
        return req.getRequestURI();
    }
}

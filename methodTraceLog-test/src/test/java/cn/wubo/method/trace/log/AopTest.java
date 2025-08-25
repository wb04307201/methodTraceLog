package cn.wubo.method.trace.log;

import cn.wubo.method.trace.log.autoconfigure.LogConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = {TestConfig.class, WebMvcAutoConfiguration.class, LogConfig.class})
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:application.yml")
class AopTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testGet() throws Exception {
        // 调用控制器触发整个调用链
        mockMvc.perform(get("/test/get")
                        .queryParam("name", "java")
                        .contentType(MediaType.APPLICATION_JSON)
                )
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("JAVA say:'hello world!'")));
    }

    @Test
    void testPost() throws Exception {
        // 调用控制器触发整个调用链
        mockMvc.perform(post("/test/post")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"John\", \"age\": 30}")
                        .accept(MediaType.APPLICATION_JSON)
                )
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("John"))
                .andExpect(jsonPath("$.age").value(30)); // 验证响应JSON
    }

    @Test
    void upload() throws Exception {
        // 1. 创建模拟文件（文件名、原始文件名、内容类型、文件内容）
        MockMultipartFile file = new MockMultipartFile("file",                     // 参数名（与控制器@RequestParam一致）
                "test.txt",                 // 原始文件名
                MediaType.TEXT_PLAIN_VALUE, // 文件类型
                "Hello, World!".getBytes()  // 文件内容
        );

        // 2. 执行文件上传请求
        mockMvc.perform(multipart("/test/upload") // 请求路径
                        .file(file)                  // 添加文件
                        .queryParam("description", "Test file") // 其他表单参数
                        .contentType(MediaType.MULTIPART_FORM_DATA) // 必须设置
                )     // 期望的响应类型
                .andDo(print())
                .andExpect(status().isOk())      // 验证状态码
                .andExpect(jsonPath("$.fileName").value("test.txt")); // 验证响应JSON
    }

}

import cn.wubo.log.config.LogConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {
        LogConfig.class, // 你的主配置
        TestConfig.class     // 测试专用配置
})
public class AopTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    public void testGet() throws Exception {
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
    public void upload() throws Exception {
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
                .andDo(print()).andExpect(status().isOk())      // 验证状态码
                .andExpect(content().string(containsString("/test/upload"))); // 验证响应JSON
    }

}

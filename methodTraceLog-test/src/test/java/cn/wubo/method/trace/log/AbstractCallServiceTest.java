package cn.wubo.method.trace.log;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AbstractCallServiceTest {

    private final AbstractCallService abstractCallService = new AbstractCallService() {
        @Override
        public void consumer(ServiceCallInfo serviceCallInfo) {
            // 测试实现，无需实际操作
        }

        @Override
        public String getCallServiceName() {
            return "TestService";
        }

        @Override
        public String getCallServiceDesc() {
            return "Test Service";
        }
    };

    @Test
    void enableFlag_shouldWorkCorrectly() {
        // 初始状态
        assertTrue(abstractCallService.getEnable());

        // 设置为false
        abstractCallService.setEnable(false);
        assertFalse(abstractCallService.getEnable());

        // 设置为true
        abstractCallService.setEnable(true);
        assertTrue(abstractCallService.getEnable());
    }

    @Test
    void transContext_withNull_shouldReturnNull() {
        // When
        Object result = AbstractCallService.transContext(null);

        // Then
        assertNull(result);
    }

    @Test
    void transContext_withArray_shouldConvertToList() {
        // Given
        String[] array = {"item1", "item2", "item3"};

        // When
        Object result = AbstractCallService.transContext(array);

        // Then
        assertTrue(result instanceof List);
        List<?> list = (List<?>) result;
        assertEquals(3, list.size());
        assertEquals("item1", list.get(0));
        assertEquals("item2", list.get(1));
        assertEquals("item3", list.get(2));
    }

    @Test
    void transContext_withNestedArray_shouldConvertRecursively() {
        // Given
        Object[] nestedArray = {new String[]{"inner1", "inner2"}, "outer"};

        // When
        Object result = AbstractCallService.transContext(nestedArray);

        // Then
        assertTrue(result instanceof List);
        List<?> list = (List<?>) result;
        assertEquals(2, list.size());
        assertTrue(list.get(0) instanceof List);
        assertEquals("outer", list.get(1));
    }

    @Test
    void transContext_withException_shouldExtractMessageAndStackTrace() {
        // Given
        Exception exception = new RuntimeException("Test exception");

        // When
        Object result = AbstractCallService.transContext(exception);

        // Then
        assertTrue(result instanceof String);
        String resultStr = (String) result;
        assertTrue(resultStr.contains("Test exception"));
        assertTrue(resultStr.contains("AbstractCallServiceTest.transContext_withException"));
    }

    @Test
    void transContext_withHttpServletRequest_shouldReturnPlaceholder() {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();

        // When
        Object result = AbstractCallService.transContext(request);

        // Then
        assertEquals("HttpServletRequest", result);
    }

    @Test
    void transContext_withHttpServletResponse_shouldReturnPlaceholder() {
        // Given
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        Object result = AbstractCallService.transContext(response);

        // Then
        assertEquals("HttpServletResponse", result);
    }

    @Test
    void transContext_withMultipartFile_shouldReturnFileInfo() {
        // Given
        MultipartFile mockFile = mock(MultipartFile.class);
        when(mockFile.getOriginalFilename()).thenReturn("test.txt");
        when(mockFile.getSize()).thenReturn(1024L);

        // When
        Object result = AbstractCallService.transContext(mockFile);

        // Then
        assertTrue(result instanceof String);
        String resultStr = (String) result;
        assertTrue(resultStr.contains("文件名: test.txt"));
        assertTrue(resultStr.contains("大小: 1024"));
    }

    @Test
    void transContext_withResponseEntity_shouldExtractBody() {
        // Given
        ResponseEntity<String> responseEntity = ResponseEntity.ok("test body");

        // When
        Object result = AbstractCallService.transContext(responseEntity);

        // Then
        assertEquals("test body", result);
    }

    @Test
    void transContext_withRegularObject_shouldReturnAsIs() {
        // Given
        String testString = "test string";
        Integer testInteger = 123;

        // When
        Object result1 = AbstractCallService.transContext(testString);
        Object result2 = AbstractCallService.transContext(testInteger);

        // Then
        assertEquals(testString, result1);
        assertEquals(testInteger, result2);
    }

    /**
     * 复现 / 锁定 2026-06-09 的 Jackson 序列化 500 Bug：
     * 模拟 Spring MVC handler 方法签名 {@code handleFileUpload(HttpServletRequest, MultipartFile, String)}
     * 的 args 数组。transContext 必须把所有 servlet/多部分引用替换为可 JSON 序列化的占位字符串，
     * 否则 /view/list 面板查询时 Jackson 会因 RequestFacade 已 recycle 而抛 IllegalStateException。
     */
    @Test
    void transContext_withServletAndMultipartInArray_shouldReturnJsonSafeList() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MultipartFile mockFile = mock(MultipartFile.class);
        when(mockFile.getOriginalFilename()).thenReturn("data.bin");
        when(mockFile.getSize()).thenReturn(2048L);

        Object[] args = {request, mockFile, "description"};

        Object result = AbstractCallService.transContext(args);

        assertTrue(result instanceof List);
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) result;
        assertEquals(3, list.size());
        assertEquals("HttpServletRequest", list.get(0));
        assertTrue(list.get(1) instanceof String);
        assertTrue(((String) list.get(1)).contains("data.bin"));
        assertEquals("description", list.get(2));
        // 关键：原 HttpServletRequest 实例不能出现在结果里（持有它就会在序列化时炸）
        assertNotSame(request, list.get(0));
        assertNotSame(mockFile, list.get(1));
    }

    /**
     * 幂等性测试：已经净化过的 String / List 再次调 transContext 应当原样返回。
     * SimpleLogServiceImpl 防御性兜底会重复调用一次，依赖这个性质。
     */
    @Test
    void transContext_idempotent_forAlreadyConvertedValues() {
        Object once = AbstractCallService.transContext(new Object[]{"a", "b"});
        Object twice = AbstractCallService.transContext(once);
        assertEquals(once, twice);

        Object exOnce = AbstractCallService.transContext(new RuntimeException("oops"));
        Object exTwice = AbstractCallService.transContext(exOnce);
        assertEquals(exOnce, exTwice);
    }

    /**
     * ResponseEntity 体内若再嵌套 servlet 引用，递归调用应能继续净化。
     * (修复后新增保护：以前 ResponseEntity.getBody() 不递归，依赖此保护避免再次出现 500)
     */
    @Test
    void transContext_withResponseEntityWrappingRequest_shouldStripRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ResponseEntity<Object> entity = ResponseEntity.ok(request);

        Object result = AbstractCallService.transContext(entity);

        assertEquals("HttpServletRequest", result);
        assertNotSame(request, result);
    }
}

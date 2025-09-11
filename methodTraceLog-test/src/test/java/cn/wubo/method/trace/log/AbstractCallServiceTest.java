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
        Object result = abstractCallService.transContext(null);

        // Then
        assertNull(result);
    }

    @Test
    void transContext_withArray_shouldConvertToList() {
        // Given
        String[] array = {"item1", "item2", "item3"};

        // When
        Object result = abstractCallService.transContext(array);

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
        Object result = abstractCallService.transContext(nestedArray);

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
        Object result = abstractCallService.transContext(exception);

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
        Object result = abstractCallService.transContext(request);

        // Then
        assertEquals("HttpServletRequest", result);
    }

    @Test
    void transContext_withHttpServletResponse_shouldReturnPlaceholder() {
        // Given
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        Object result = abstractCallService.transContext(response);

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
        Object result = abstractCallService.transContext(mockFile);

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
        Object result = abstractCallService.transContext(responseEntity);

        // Then
        assertEquals("test body", result);
    }

    @Test
    void transContext_withRegularObject_shouldReturnAsIs() {
        // Given
        String testString = "test string";
        Integer testInteger = 123;

        // When
        Object result1 = abstractCallService.transContext(testString);
        Object result2 = abstractCallService.transContext(testInteger);

        // Then
        assertEquals(testString, result1);
        assertEquals(testInteger, result2);
    }
}

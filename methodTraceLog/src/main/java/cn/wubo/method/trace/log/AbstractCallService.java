package cn.wubo.method.trace.log;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public abstract class AbstractCallService implements ICallService {

    private Boolean enable = true;

    public Boolean getEnable() {
        return enable;
    }

    public void setEnable(Boolean enable) {
        this.enable = enable;
    }

    /**
     * 转换上下文对象为可序列化或可处理的格式
     *
     * @param context 原始上下文对象，可能为null
     * @return 转换后的对象，可能的类型包括：null、List、String、原始对象等
     */
    protected Object transContext(Object context) {
        if (context == null) {
            return null; // 明确处理 null 输入
        }

        // 处理数组类型，将其转换为List并递归处理每个元素
        if (context.getClass().isArray()) {
            int length = Array.getLength(context);
            List<Object> list = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                list.add(transContext(Array.get(context, i)));
            }
            return list;
        }

        // 处理异常类型，提取异常信息和堆栈跟踪
        if (context instanceof Exception e) {
            String message = e.getMessage();
            String stackTrace = Arrays.stream(e.getStackTrace()).map(Objects::toString).limit(10).collect(Collectors.joining("\n"));
            return message != null ? message + "\n" + stackTrace : stackTrace;
        }

        // 处理HTTP相关对象类型
        if (context instanceof HttpServletRequest) {
            return "HttpServletRequest";
        }
        if (context instanceof HttpServletResponse) {
            return "HttpServletResponse";
        }

        // 处理文件上传对象，返回文件基本信息
        if (context instanceof MultipartFile file) {
            String filename = file.getOriginalFilename();
            long size = file.getSize();
            return String.format("文件名: %s, 大小: %d", filename != null ? filename : "未知", size);
        }

        // 处理响应实体，提取响应体内容
        if (context instanceof ResponseEntity<?> entity) {
            return entity.getBody();
        }

        // 兜底返回原始对象
        return context;
    }

}

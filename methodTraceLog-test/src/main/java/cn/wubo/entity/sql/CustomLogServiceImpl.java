package cn.wubo.entity.sql;

import cn.wubo.method.trace.log.LogActionEnum;
import cn.wubo.method.trace.log.service.ILogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CustomLogServiceImpl implements ILogService {

    @Override
    public void log(String traceid, String pspanid, String spanid, String classname, String methodSignature, Object context, LogActionEnum logActionEnum) {
        if (logActionEnum == LogActionEnum.AFTER_THROW)
            log.error("custom-log traceid: {}, pspanid: {}, spanid: {}, classname: {}, methodSignature: {}, context: {}, logActionEnum: {}, time: {}", traceid, pspanid, spanid, classname, methodSignature, transContext(context), logActionEnum, System.currentTimeMillis());
        else
            log.info("custom-log traceid: {}, pspanid: {}, spanid: {}, classname: {}, methodSignature: {}, context: {}, logActionEnum: {}, time: {}", traceid, pspanid, spanid, classname, methodSignature, transContext(context), logActionEnum, System.currentTimeMillis());
    }

    /**
     * 默认方法，用于根据上下文对象的类型转换上下文
     * 此方法旨在处理多种类型的输入对象，并将其转换为一个统一的、可处理的格式
     * 它特别处理数组、异常、HTTP请求和响应、文件，以及通用的响应实体
     *
     * @param context 待转换的上下文对象，可以是任意类型
     * @return 转换后的对象，具体类型取决于输入对象的类型
     */
    private Object transContext(Object context) {
        if (context == null) {
            return null; // 明确处理 null 输入
        }

        // 处理数组
        if (context.getClass().isArray()) {
            int length = Array.getLength(context);
            List<Object> list = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                list.add(transContext(Array.get(context, i)));
            }
            return list;
        }

        // 处理异常类型
        if (context instanceof Exception e) {
            String message = e.getMessage();
            String stackTrace = Arrays.stream(e.getStackTrace()).map(Objects::toString).limit(10).collect(Collectors.joining("\n"));
            return message != null ? message + "\n" + stackTrace : stackTrace;
        }

        // 处理 HTTP 请求和响应
        if (context instanceof HttpServletRequest) {
            return "HttpServletRequest";
        }
        if (context instanceof HttpServletResponse) {
            return "HttpServletResponse";
        }

        // 处理文件上传
        if (context instanceof MultipartFile file) {
            String filename = file.getOriginalFilename();
            long size = file.getSize();
            return String.format("文件名: %s, 大小: %d", filename != null ? filename : "未知", size);
        }

        // 处理响应实体
        if (context instanceof ResponseEntity<?> entity) {
            return entity.getBody();
        }

        // 兜底返回原始对象
        return context;
    }
}

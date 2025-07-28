package cn.wubo.log.core;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;

import java.util.UUID;

@Slf4j
@Aspect
public class LogAspect {

    private ILogService logService;

    public LogAspect(ILogService logService) {
        this.logService = logService;
    }

    /**
     * 日志跟踪id。
     */
    public static final String LOG_TRACE_ID = "traceid";

    /**
     * 日志跨度id。
     */
    public static final String LOG_SPAN_ID = "spanid";

        /**
     * 环绕通知，应用于带有@Component、@Service或@RestController注解的类中的方法
     * 该方法主要用于追踪和日志记录，通过MDC（Mapped Diagnostic Context）传递跟踪ID和跨度ID，
     * 实现全链路调用日志追踪功能。
     *
     * @param jp 切入点，用于获取目标方法的签名和参数信息
     * @return 目标方法执行后的返回结果
     * @throws Throwable 目标方法可能抛出的异常
     */
    @Around("@within(org.springframework.stereotype.Component) || @within(org.springframework.stereotype.Service) || @within(org.springframework.web.bind.annotation.RestController)")
    public Object around(ProceedingJoinPoint jp) throws Throwable {
        Object returnValue;
        // 获取当前线程中已存在的跟踪ID
        String traceid = MDC.get(LOG_TRACE_ID);
        String pspanid = null;

        // 若无跟踪ID，则生成一个新的；否则获取当前跨度ID作为父跨度ID
        if (traceid == null) {
            traceid = UUID.randomUUID().toString();
        } else {
            pspanid = MDC.get(LOG_SPAN_ID);
        }
        // 为当前方法调用生成新的唯一跨度ID
        String spanid = UUID.randomUUID().toString();
        // 将跟踪ID与跨度ID存入MDC，供后续日志使用
        MDC.put(LOG_TRACE_ID, traceid);
        MDC.put(LOG_SPAN_ID, spanid);

        // 提取目标方法的签名信息
        MethodSignature methodSignature = (MethodSignature) jp.getSignature();
        // 获取目标类名
        String className = methodSignature.getMethod().getDeclaringClass().getName();
        // 获取方法完整签名字符串
        String methodSignatureString = methodSignature.toLongString();

        try {
            // 记录方法执行前的操作日志
            logService.log(traceid, pspanid, spanid, className, methodSignatureString, jp.getArgs(), LogActionEnum.BEFORE);
            // 执行目标方法
            returnValue = jp.proceed();
            // 记录方法正常返回后的操作日志
            logService.log(traceid, pspanid, spanid, className, methodSignatureString, returnValue, LogActionEnum.AFTER_RETURN);
        } catch (Exception e) {
            // 记录方法抛出异常时的操作日志
            logService.log(traceid, pspanid, spanid, className, methodSignatureString, e, LogActionEnum.AFTER_THROW);
            throw e;
        } finally {
            // 清理MDC上下文，防止线程复用造成数据污染
            MDC.remove(LOG_TRACE_ID);
            MDC.remove(LOG_SPAN_ID);
        }

        return returnValue;
    }

}
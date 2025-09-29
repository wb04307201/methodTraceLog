package cn.wubo.method.trace.log;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;

import java.util.List;
import java.util.UUID;

@Slf4j
@Aspect
public class LogAspect {

    private  final CallServiceStrategy callServiceStrategy;

    /**
     * 日志跟踪id。
     */
    public static final String LOG_TRACE_ID = "traceid";

    /**
     * 日志跨度id。
     */
    public static final String LOG_SPAN_ID = "spanid";

    public LogAspect(CallServiceStrategy callServiceStrategy) {
        this.callServiceStrategy = callServiceStrategy;
    }

    /**
     * 环绕通知，应用于带有@Component、@Service或@RestController注解的类中的方法
     * 该方法主要用于追踪和日志记录，通过MDC（Mapped Diagnostic Context）传递跟踪ID和跨度ID，
     * 实现全链路调用日志追踪功能。
     *
     * @param jp 切入点，用于获取目标方法的签名和参数信息
     * @return 目标方法执行后的返回结果
     * @throws Throwable 目标方法可能抛出的异常
     */
    @Around("(@within(org.springframework.stereotype.Component) || " +
            "@within(org.springframework.stereotype.Service) || " +
            "@within(org.springframework.web.bind.annotation.RestController)) && " +
            "!within(cn.wubo.method.trace.log.ICallService+) &&" +
            "!within(cn.wubo.method.trace.log.impl.monitor.MethodTraceLogEndPoint) &&" +
            "!within(cn.wubo.method.trace.log.file.LogFileService) &&" +
            "!within(cn.wubo.method.trace.log.file.LogFileRealTimeService)")
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

        // 构建方法调用前的服务调用信息
        ServiceCallInfo before = new ServiceCallInfo(traceid, pspanid, spanid, (MethodSignature) jp.getSignature(), jp.getArgs(), LogActionEnum.BEFORE, System.currentTimeMillis());
        ServiceCallInfo after = before.clone();

        try {
            // 执行前置处理逻辑
            callServiceStrategy.consumer(before);
            // 执行目标方法
            returnValue = jp.proceed();

            // 设置返回值并执行后置正常返回处理逻辑
            after.setContext(returnValue);
            after.setLogActionEnum(LogActionEnum.AFTER_RETURN);
            after.setTimeMillis(System.currentTimeMillis());
            callServiceStrategy.consumer(after);
        } catch (Exception e) {
            // 设置异常信息并执行后置异常处理逻辑
            after.setContext(e);
            after.setLogActionEnum(LogActionEnum.AFTER_THROW);
            after.setTimeMillis(System.currentTimeMillis());
            callServiceStrategy.consumer(after);
            throw e;
        } finally {
            // 清理MDC上下文，防止线程复用造成数据污染
            MDC.remove(LOG_TRACE_ID);
            MDC.remove(LOG_SPAN_ID);
        }

        return returnValue;
    }


}
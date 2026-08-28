package cn.wubo.method.trace.log;

import cn.wubo.method.trace.log.sampler.HeadBasedSampler;
import cn.wubo.method.trace.log.sampler.Sampler;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * AOP 切面：拦截 {@code @Component / @Service / @RestController} 注解类中的方法
 * 以及打了 {@link AspectLog} 的方法，构造 {@link ServiceCallInfo} 事件并通过
 * {@link CallServiceStrategy} 分发给全部 {@link ICallService} 实现。
 * <p>
 * 同时维护 MDC 中的 {@code traceid} / {@code spanid} / {@code pspanid} / {@code mtlSampled}，
 * 供下游日志 logback pattern 使用 ({@code %X{traceid}}) 以及子调用继承父采样决定。
 * <p>
 * pointcut 中排除了框架内部类型（{@link ICallService} / {@link cn.wubo.method.trace.log.impl.monitor.MethodTraceLogEndPoint}
 * / {@link cn.wubo.method.trace.log.file.LogFileService} / {@link cn.wubo.method.trace.log.file.LogFileRealTimeService}），
 * 避免追踪到自己。
 */
@Slf4j
@Aspect
public class LogAspect {

    private final CallServiceStrategy callServiceStrategy;
    private final Sampler sampler;

    /** MDC 中 trace 标识的 key，对应 W3C traceparent 中的 trace-id。 */
    public static final String LOG_TRACE_ID = "traceid";

    /** MDC 中父 span 标识的 key，子调用读取用于构造 {@link ServiceCallInfo#pspanid}。 */
    public static final String LOG_PSPAN_ID = "pspanid";

    /** MDC 中当前 span 标识的 key。 */
    public static final String LOG_SPAN_ID = "spanid";

    /**
     * MDC 中的采样标记。"true" = 已采样 / "false" = 未采样。
     * 子调用读取此值继承父决定，不再投骰子。
     */
    public static final String LOG_SAMPLED = "mtlSampled";

    /**
     * 便捷构造：使用 {@link HeadBasedSampler#HeadBasedSampler(double) 默认 100% 采样}。
     *
     * @param callServiceStrategy 事件分发器
     */
    public LogAspect(CallServiceStrategy callServiceStrategy) {
        this(callServiceStrategy, new HeadBasedSampler(1.0));
    }

    /**
     * 注入自定义采样器。
     *
     * @param callServiceStrategy 事件分发器
     * @param sampler             根调用采样器
     */
    public LogAspect(CallServiceStrategy callServiceStrategy, Sampler sampler) {
        this.callServiceStrategy = callServiceStrategy;
        this.sampler = sampler;
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
            "@within(org.springframework.web.bind.annotation.RestController) || " +
            "@annotation(cn.wubo.method.trace.log.AspectLog)) && " +
            "!within(cn.wubo.method.trace.log.ICallService+) && " +
            "!within(cn.wubo.method.trace.log.impl.monitor.MethodTraceLogEndPoint) &&" +
            "!within(cn.wubo.method.trace.log.file.LogFileService) && " +
            "!within(cn.wubo.method.trace.log.file.LogFileRealTimeService)")
    public Object around(ProceedingJoinPoint jp) throws Throwable {
        Object returnValue;
        // 获取当前线程中已存在的跟踪ID
        String traceid = MDC.get(LOG_TRACE_ID);
        String prepspanid = MDC.get(LOG_PSPAN_ID);
        String prespanid = MDC.get(LOG_SPAN_ID);
        String preSampled = MDC.get(LOG_SAMPLED);
        String pspanid = null;

        // 若无跟踪ID，则生成一个新的；否则获取当前跨度ID作为父跨度ID
        if (traceid == null) {
            traceid = UUID.randomUUID().toString();
        } else {
            pspanid = prespanid;
        }
        // 为当前方法调用生成新的唯一跨度ID
        String spanid = UUID.randomUUID().toString();
        // 将跟踪ID与跨度ID存入MDC，供后续日志使用
        MDC.put(LOG_TRACE_ID, traceid);
        MDC.put(LOG_SPAN_ID, spanid);

        // 决定本调用是否采样。子调用继承父决定，避免每层都投骰子。
        boolean sampled;
        if (preSampled != null) {
            sampled = Boolean.parseBoolean(preSampled);
        } else {
            sampled = sampler.shouldStartRoot();
        }
        MDC.put(LOG_SAMPLED, Boolean.toString(sampled));

        // 构建方法调用前/后的服务调用信息（仅在 sampled 时才有意义）
        ServiceCallInfo before = null;
        ServiceCallInfo after = null;
        if (sampled) {
            // 关键：在写入 ServiceCallInfo.context 之前先做 transContext 净化。
            // 直接持有 jp.getArgs() 会在 JSON 序列化阶段（/view/list 面板查询时），
            // 因 Tomcat 已回收 RequestFacade 而抛 IllegalStateException。
            // 净化后 args/returnValue/exception 都变成可 JSON 序列化的值（List<String> / String / 基本类型）。
            Object safeArgs = AbstractCallService.transContext(jp.getArgs());
            before = new ServiceCallInfo(traceid, pspanid, spanid, (MethodSignature) jp.getSignature(), safeArgs, LogActionEnum.BEFORE, System.currentTimeMillis());
            // @AspectLog 注解覆盖 methodName / methodSignatureShortString
            applyAspectLogOverride(jp, before);
            after = ServiceCallInfo.copyOf(before);
        }

        try {
            if (sampled) {
                // 执行前置处理逻辑
                callServiceStrategy.consumer(before);
            }
            // 执行目标方法
            returnValue = jp.proceed();

            if (sampled) {
                // 设置返回值并执行后置正常返回处理逻辑
                after.setContext(AbstractCallService.transContext(returnValue));
                after.setLogActionEnum(LogActionEnum.AFTER_RETURN);
                after.setTimeMillis(System.currentTimeMillis());
                callServiceStrategy.consumer(after);
            }
        } catch (Exception e) {
            if (sampled) {
                // 设置异常信息并执行后置异常处理逻辑
                after.setContext(AbstractCallService.transContext(e));
                after.setRawException(e);  // 保留原始异常对象，供 OTel 等需要 Throwable 的下游使用
                after.setLogActionEnum(LogActionEnum.AFTER_THROW);
                after.setTimeMillis(System.currentTimeMillis());
                callServiceStrategy.consumer(after);
            }
            throw e;
        } finally {
            if (pspanid == null) {
                MDC.remove(LOG_TRACE_ID);
                MDC.remove(LOG_SPAN_ID);
            } else {
                MDC.put(LOG_PSPAN_ID, prepspanid);
                MDC.put(LOG_SPAN_ID, prespanid);
            }
            if (preSampled == null) {
                MDC.remove(LOG_SAMPLED);
            } else {
                MDC.put(LOG_SAMPLED, preSampled);
            }
        }

        return returnValue;
    }

    /**
     * 如果方法上有 @AspectLog 注解，把显示名替换为注解 value()。
     * tags 暂时不展开（若需要可以让 ICallService 自行从 MDC 读 mtl.tag.*）。
     */
    private void applyAspectLogOverride(ProceedingJoinPoint jp, ServiceCallInfo info) {
        try {
            MethodSignature sig = (MethodSignature) jp.getSignature();
            Method method = sig.getMethod();
            AspectLog ann = method.getAnnotation(AspectLog.class);
            if (ann == null) {
                return;
            }
            if (ann.value() != null && !ann.value().isEmpty()) {
                info.setMethodName(ann.value());
                info.setMethodSignatureShortString(ann.value() + "(..)");
            }
        } catch (Exception ignore) {
            // 注解读取失败不应影响主流程
        }
    }


}

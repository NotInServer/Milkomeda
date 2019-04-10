package com.github.yizzuide.milkomeda.pulsar;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Function;

/**
 * Pulsar
 *
 * @author yizzuide
 * Create at 2019/03/29 10:36
 */
@Slf4j
@Aspect
public class Pulsar {
    /**
     * DeferredResult容器
     */
    private Map<String, DeferredResult<Object>> deferredResultMap;

    /**
     *  Error 错误回调<可抛出接口，自定义响应数据>
     */
    private Function<Throwable, Object> errorCallback;

    /**
     * 超时回调，返回参数为自定义响应数据
     */
    private Callable<Object> timeoutCallback;

    public Pulsar() {
        deferredResultMap = new ConcurrentHashMap<>();
    }

    /**
     * 通过标识符拿走对应的DeferredResult
     * @param id 标识符
     * @return DeferredResult
     */
    public DeferredResult<Object> takeDeferredResult(String id) {
        return deferredResultMap.remove(id);
    }

    /**
     * 对使用了 @PulsarAsync 注解实现环绕切面
     * @param joinPoint 切面连接点
     * @return 响应数据对象
     * @throws Throwable 可抛出异常
     */
    @Around("@annotation(com.github.yizzuide.milkomeda.pulsar.PulsarAsync)")
    public Object handlePulse(ProceedingJoinPoint joinPoint) throws Throwable {
        PulsarAsync pulsarAsync = resolveAnnotation(joinPoint);
        // 如果没有设置DeferredResult，则使用WebAsyncTask
        if (!pulsarAsync.useDeferredResult()) {
            // 返回异步任务
            WebAsyncTask<Object> webAsyncTask = new WebAsyncTask<>(new WebAsyncTaskCallable(joinPoint));
            if (null != timeoutCallback) {
                webAsyncTask.onTimeout(timeoutCallback);
            }
            return webAsyncTask;
        }

        // 使用DeferredResult方式
        DeferredResult<Object> deferredResult = new DeferredResult<>();
        if (null != timeoutCallback) {
            // 适配超时处理
            deferredResult.onTimeout(() -> {
                try {
                    log.warn("deferredResult handle timeout：{}", deferredResult);
                    deferredResult.setErrorResult(timeoutCallback.call());
                } catch (Exception e) {
                    log.error("error callback error", e);
                }
            });
        }
        if (null != errorCallback) {
            // 适配错误处理
            deferredResult.onError((throwable) -> deferredResult.setErrorResult(errorCallback.apply(throwable)));
        }
        PulsarDeferredResult pulsarDeferredResult = new PulsarDeferredResult();
        // 调用方法实现
        joinPoint.proceed(injectDeferredResult(joinPoint, pulsarDeferredResult));
        // 检测deferredResultID
        if (null == pulsarDeferredResult.getDeferredResultID() ||
                "".equals(pulsarDeferredResult.getDeferredResultID())) {
            throw new IllegalArgumentException("you must set deferredResultID use setDeferredResultID() of PulsarDeferredResult");
        }
        // 添加到容器
        deferredResultMap.put(pulsarDeferredResult.getDeferredResultID(), deferredResult);
        return deferredResult;
    }

    /**
     * 基于WebAsyncTask实现的Callable
     */
    private class WebAsyncTaskCallable implements Callable<Object> {
        /**
         * 切面连接点
         */
        private ProceedingJoinPoint joinPoint;

        WebAsyncTaskCallable(ProceedingJoinPoint joinPoint) {
            this.joinPoint = joinPoint;
        }

        @Override
        public Object call() throws Exception {
            log.debug("pulsar invoke: {}", joinPoint.getSignature());
            try {
                return joinPoint.proceed();
            } catch (Throwable throwable) {
                log.error("pulsar invoke error", throwable);
                // 如果有Exception异常向外抛，交由开发者处理
                if (throwable instanceof Exception) {
                    throw (Exception) throwable;
                } else { // Error类型
                    if (null != errorCallback) {
                        return errorCallback.apply(throwable);
                    }
                    return ResponseEntity.status(500).body(throwable.getMessage());
                }
            }
        }
    }

    /**
     * 配置默认的异步支持
     * @param configurer 配置对象
     * @param timeout 超时时间，ms
     */
    public static void configureAsyncSupport(AsyncSupportConfigurer configurer, long timeout) {
        // 默认超时时间
        configurer.setDefaultTimeout(timeout);
        // 自定义线程池
        ThreadPoolTaskExecutor poolTaskExecutor = new ThreadPoolTaskExecutor();
        // 线程池维护线程的最少数量
        poolTaskExecutor.setCorePoolSize(5);
        // 线程池维护线程的最大数量
        poolTaskExecutor.setMaxPoolSize(200);
        // 线程池所使用的缓冲队列
        poolTaskExecutor.setQueueCapacity(50);
        // 线程池维护线程所允许的空闲时间
        poolTaskExecutor.setKeepAliveSeconds(200);
        poolTaskExecutor.setThreadNamePrefix("pulsar-");
        // 线程池对拒绝任务（无线程可用）的处理策略，目前只支持AbortPolicy、CallerRunsPolicy，默认为后者
        poolTaskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        poolTaskExecutor.initialize();
        configurer.setTaskExecutor(poolTaskExecutor);
    }

    /**
     * 用于处理 Error 类型（Exception类型还是使用 @ExceptionHandler 捕获）
     * @param errorCallback 失败回调
     */
    public void setErrorCallback(Function<Throwable, Object> errorCallback) {
        this.errorCallback = errorCallback;
    }

    /**
     * 处理超时
     * @param timeoutCallback 需要返回响应的超时数据
     */
    public void setTimeoutCallback(Callable<Object> timeoutCallback) {
        this.timeoutCallback = timeoutCallback;
    }

    /**
     * 注入DeferredResult
     * @param joinPoint 切面连接点
     * @param deferredResult DeferredResult标识装配类
     * @return 注入完成的参数
     */
    private Object[] injectDeferredResult(JoinPoint joinPoint, PulsarDeferredResult deferredResult) {
        Object[] args = joinPoint.getArgs();
        int len = args.length;
        for (int i = 0; i < len; i++) {
            if (args[i] instanceof PulsarDeferredResult) {
                args[i] = deferredResult;
                return args;
            }
        }
        return args;
    }

    /**
     * 解析方法注解
     * @param joinPoint 切面连接点
     * @return PulsarAsync
     * @throws Exception 获取方法异常
     */
    @SuppressWarnings("unchecked")
    private PulsarAsync resolveAnnotation(JoinPoint joinPoint) throws Exception {
        // 目标类名
        String targetName = joinPoint.getTarget().getClass().getName();
        // 方法签名
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        // 方法名
        String methodName = methodSignature.getName();
        // 参数类型
        Class[] parameterTypes = methodSignature.getParameterTypes();
        // 目标字节类
        Class targetClass = Class.forName(targetName);
        // 反射方法
        Method method = targetClass.getDeclaredMethod(methodName, parameterTypes);
        // 获取方法上的注解
        return method.getAnnotation(PulsarAsync.class);
    }
}

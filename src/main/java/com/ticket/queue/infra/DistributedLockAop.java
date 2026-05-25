package com.ticket.queue.infra;

import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
public class DistributedLockAop {

    private static final String LOCK_PREFIX = "lock:";

    private final RedissonClient redissonClient;

    @Around("@annotation(distributedLock)")
    public Object lock(
            final ProceedingJoinPoint joinPoint,
            final DistributedLock distributedLock
    ) throws Throwable {
        String lockKey = buildLockKey(joinPoint, distributedLock);
        RLock lock = redissonClient.getLock(lockKey);

        boolean acquired = tryLock(lock, distributedLock);
        if (!acquired) {
            throw new CoreException(distributedLock.errorType(), distributedLock.message());
        }

        try {
            return joinPoint.proceed();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private boolean tryLock(final RLock lock, final DistributedLock distributedLock) {
        try {
            return lock.tryLock(
                    distributedLock.waitTime(),
                    distributedLock.leaseTime(),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CoreException(
                    ErrorType.LOCK_ACQUISITION_FAILED,
                    "distributed lock acquisition interrupted",
                    exception
            );
        }
    }

    private String buildLockKey(
            final ProceedingJoinPoint joinPoint,
            final DistributedLock distributedLock
    ) {
        String dynamicKey = resolveDynamicKey(joinPoint, distributedLock);
        if (dynamicKey.isBlank()) {
            return LOCK_PREFIX + distributedLock.prefix();
        }
        return LOCK_PREFIX + distributedLock.prefix() + ":" + dynamicKey;
    }

    private String resolveDynamicKey(
            final ProceedingJoinPoint joinPoint,
            final DistributedLock distributedLock
    ) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return CustomSpringELParser.parse(
                signature.getParameterNames(),
                joinPoint.getArgs(),
                distributedLock.dynamicKey()
        );
    }
}

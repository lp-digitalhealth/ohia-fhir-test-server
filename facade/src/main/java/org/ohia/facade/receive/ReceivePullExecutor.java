package org.ohia.facade.receive;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Runs the callback to a sender (the ARex pull) after the sender has already been answered. Two threads, up to four, and a
 * queue of 50. If every thread and the whole queue are busy the pull runs on the request thread instead, so a flood slows the
 * answer down (as it always did) but never drops a pull.
 */
@Component
public class ReceivePullExecutor {

    private final ThreadPoolExecutor pool;

    public ReceivePullExecutor() {
        AtomicInteger n = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(50), r -> {
            Thread t = new Thread(r, "receive-pull-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public void run(Runnable pull) {
        pool.execute(pull);
    }

    @PreDestroy
    void stop() {
        pool.shutdown();
    }
}

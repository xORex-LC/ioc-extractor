import com.iocextractor.platform.concurrent.BoundedKeyedSerialExecutor;
import com.iocextractor.platform.concurrent.KeyedExecutionGuardSnapshot;
import com.iocextractor.platform.concurrent.SynchronousKeyedExecutionGuard;
import com.iocextractor.platform.concurrent.WorkKey;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Consumer assertions compiled solely against the resolved public library JAR. */
public final class LibraryConsumer {
    public static void main(String[] args) throws Exception {
        var guard = new SynchronousKeyedExecutionGuard();
        var key = WorkKey.of("consumer");
        require("result".equals(guard.execute(key, () -> guard.execute(key, () -> "result"))));
        require(guard.snapshot().equals(KeyedExecutionGuardSnapshot.empty()));
        var failure = new IllegalStateException("consumer failure");
        try {
            guard.execute(key, () -> { throw failure; });
            throw new AssertionError("work failure must propagate");
        } catch (IllegalStateException actual) {
            require(actual == failure);
        }
        var workers = Executors.newFixedThreadPool(2);
        var executor = new BoundedKeyedSerialExecutor(workers, 1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var other = new CountDownLatch(1);
        List<Integer> order = new CopyOnWriteArrayList<>();
        try {
            require(executor.submit(key, () -> {
                entered.countDown();
                try {
                    require(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                order.add(1);
            }).accepted());
            require(entered.await(5, TimeUnit.SECONDS));
            require(executor.submit(key, () -> order.add(2)).accepted());
            require(!executor.submit(key, () -> order.add(3)).accepted());
            require(executor.submit(WorkKey.of("other"), other::countDown).accepted());
            require(other.await(5, TimeUnit.SECONDS));
            executor.shutdown();
            require(!executor.submit(key, () -> order.add(4)).accepted());
            release.countDown();
            require(executor.awaitTermination(Duration.ofSeconds(5)));
            require(order.equals(List.of(1, 2)));
        } finally {
            release.countDown();
            executor.shutdown();
            workers.shutdownNow();
            require(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
        System.out.println("Standalone public library contracts passed");
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new AssertionError("public library contract failed");
        }
    }
}

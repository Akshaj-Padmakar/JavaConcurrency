package Problems.S00_General.P06_ThreadPoolWithShutdown;

/** Thrown by MyThreadPool.execute() when the pool has already been (or is being) shut down. */
public class RejectedExecutionException extends RuntimeException {
    public RejectedExecutionException(String message) {
        super(message);
    }

    public RejectedExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}

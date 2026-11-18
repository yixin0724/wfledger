package org.workflowsim.failure;

public final class RetryLimitExceededException extends IllegalStateException {

    private final int failedJobId;
    private final int createdRetryJobs;
    private final int requestedRetryJobs;
    private final int maxTotalRetryJobs;
    private final long rootSeed;

    public RetryLimitExceededException(
            int failedJobId, int createdRetryJobs, int requestedRetryJobs, int maxTotalRetryJobs, long rootSeed) {
        super("Retry budget exceeded for failed Job " + failedJobId
                + ": created=" + createdRetryJobs
                + ", requested=" + requestedRetryJobs
                + ", maxTotalRetryJobs=" + maxTotalRetryJobs
                + ", rootSeed=" + rootSeed);
        this.failedJobId = failedJobId;
        this.createdRetryJobs = createdRetryJobs;
        this.requestedRetryJobs = requestedRetryJobs;
        this.maxTotalRetryJobs = maxTotalRetryJobs;
        this.rootSeed = rootSeed;
    }

    public int getFailedJobId() {
        return failedJobId;
    }

    public int getCreatedRetryJobs() {
        return createdRetryJobs;
    }

    public int getRequestedRetryJobs() {
        return requestedRetryJobs;
    }

    public int getMaxTotalRetryJobs() {
        return maxTotalRetryJobs;
    }

    public long getRootSeed() {
        return rootSeed;
    }
}

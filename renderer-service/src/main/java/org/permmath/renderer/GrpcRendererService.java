package org.permmath.renderer;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.permmath.contract.v1.*;
import org.permmath.rendering.*;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public final class GrpcRendererService extends RendererGrpc.RendererImplBase {

    private final RenderJobExecutor jobs;

    private final Duration maxDuration;

    private final Consumer<RenderException> failedJob;

    public GrpcRendererService(RenderJobExecutor jobs, Duration maxDuration, Consumer<RenderException> failedJob) {
        this.jobs = jobs;
        this.maxDuration = maxDuration;
        this.failedJob = failedJob;
    }

    @Override
    public void render(RenderRequest request, StreamObserver<RenderResponse> observer) {
        var context = Context.current();
        var thread = Thread.currentThread();
        Context.CancellationListener cancelled = _ -> thread.interrupt();
        context.addListener(cancelled, Runnable::run);
        try {
            long budget = maxDuration.toNanos();
            if (context.getDeadline() != null) {
                budget = Math.min(budget, context.getDeadline().timeRemaining(TimeUnit.NANOSECONDS));
            }
            if (context.isCancelled() || budget <= 0) {
                throw new RenderException(RenderException.Kind.QUEUE_CANCELLED, "Rendering cancelled before admission");
            }
            var response = jobs.execute(request, Duration.ofNanos(budget));
            observer.onNext(response);
            observer.onCompleted();
        } catch (RenderException failure) {
            var status = switch(failure.kind()) {
                case INVALID ->
                    Status.INVALID_ARGUMENT;
                case OVERLOADED ->
                    Status.RESOURCE_EXHAUSTED;
                case DEADLINE, QUEUE_DEADLINE ->
                    Status.DEADLINE_EXCEEDED;
                case CANCELLED, QUEUE_CANCELLED ->
                    Status.CANCELLED;
                case FAILED ->
                    Status.INTERNAL;
            };
            observer.onError(status.withDescription(failure.getMessage()).asRuntimeException());
            if (failure.kind() == RenderException.Kind.DEADLINE || failure.kind() == RenderException.Kind.CANCELLED || failure.kind() == RenderException.Kind.FAILED) {
                failedJob.accept(failure);
            }
        } catch (RuntimeException failure) {
            observer.onError(Status.INTERNAL.withDescription("Renderer failed").asRuntimeException());
            failedJob.accept(new RenderException(RenderException.Kind.FAILED, "Renderer failed", failure));
        } finally {
            context.removeListener(cancelled);
        }
    }
}

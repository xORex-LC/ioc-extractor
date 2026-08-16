package com.iocextractor.application.port.in.artifact.lifecycle;

/** P5 extension point used by admission when persisted activation is incomplete. */
@FunctionalInterface
public interface ResumeLifecycleActivationUseCase {

    /** Resumes the named, durable activation workflow to its next safe boundary. */
    void resume();
}

package badapodo.sugang.service.globalinmemory;

import java.util.concurrent.CompletableFuture;

final class GlobalInMemoryCommandExecution {

    private final GlobalInMemoryEnrollmentCommand command;
    private final CompletableFuture<GlobalInMemoryEnrollmentCommandResult> future;
    private final boolean acceptedForProcessing;
    private boolean completed;

    private GlobalInMemoryCommandExecution(GlobalInMemoryEnrollmentCommand command, boolean acceptedForProcessing) {
        this.command = command;
        this.future = command.getCommandResultFuture();
        this.acceptedForProcessing = acceptedForProcessing;
    }

    static GlobalInMemoryCommandExecution of(GlobalInMemoryEnrollmentCommand command) {
        return new GlobalInMemoryCommandExecution(command, true);
    }

    static GlobalInMemoryCommandExecution rejected(GlobalInMemoryEnrollmentCommand command) {
        return new GlobalInMemoryCommandExecution(command, false);
    }

    GlobalInMemoryEnrollmentCommand getCommand() {
        return command;
    }

    CompletableFuture<GlobalInMemoryEnrollmentCommandResult> getFuture() {
        return future;
    }

    boolean matches(Long studentId, Long courseId) {
        return command.getStudentId().equals(studentId) && command.getCourseId().equals(courseId);
    }

    boolean isAcceptedForProcessing() {
        return acceptedForProcessing;
    }

    boolean isCompleted() {
        return completed;
    }

    boolean markCompleted() {
        if (completed) {
            return false;
        }
        completed = true;
        return true;
    }
}

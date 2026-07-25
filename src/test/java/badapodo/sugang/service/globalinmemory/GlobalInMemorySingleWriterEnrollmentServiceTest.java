package badapodo.sugang.service.globalinmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GlobalInMemorySingleWriterEnrollmentServiceTest {

    private GlobalInMemoryStateStore stateStore;
    private GlobalInMemoryWriteBehindService writeBehindService;
    private GlobalInMemorySingleWriterEnrollmentService service;

    @BeforeEach
    void setUp() {
        stateStore = mock(GlobalInMemoryStateStore.class);
        writeBehindService = mock(GlobalInMemoryWriteBehindService.class);
        service = new GlobalInMemorySingleWriterEnrollmentService(
                stateStore,
                writeBehindService,
                new SimpleMeterRegistry(),
                100,
                1_000,
                100
        );
        service.startWriter();
    }

    @AfterEach
    void tearDown() {
        service.stopWriter();
    }

    @Test
    void acceptsEnrollment() {
        UUID commandId = UUID.randomUUID();

        GlobalInMemoryCommandResponse response = service.enroll(commandId, 1001L, 20L, null);

        assertThat(response.getResult()).isEqualTo(GlobalInMemoryEnrollmentAccepted.of(commandId, 1001L, 20L));
        verify(writeBehindService, timeout(500)).enqueue(any(GlobalInMemoryWriteBehindEvent.class));
    }

    @Test
    void rejectsStudentNotFound() {
        assertRejected(GlobalInMemoryEnrollmentRejectionReason.STUDENT_NOT_FOUND);
    }

    @Test
    void rejectsCourseNotFound() {
        assertRejected(GlobalInMemoryEnrollmentRejectionReason.COURSE_NOT_FOUND);
    }

    @Test
    void rejectsCapacityExceeded() {
        assertRejected(GlobalInMemoryEnrollmentRejectionReason.CAPACITY_EXCEEDED);
    }

    @Test
    void rejectsDuplicateEnrollment() {
        assertRejected(GlobalInMemoryEnrollmentRejectionReason.DUPLICATE_ENROLLMENT);
    }

    @Test
    void rejectsPrerequisiteNotMet() {
        assertRejected(GlobalInMemoryEnrollmentRejectionReason.PREREQUISITE_NOT_MET);
    }

    @Test
    void rejectsScheduleConflict() {
        assertRejected(GlobalInMemoryEnrollmentRejectionReason.SCHEDULE_CONFLICT);
    }

    @Test
    void returnsSameResultWhenCompletedCommandIdIsRepeated() {
        UUID commandId = UUID.randomUUID();

        GlobalInMemoryCommandResponse first = service.enroll(commandId, 1001L, 20L, null);
        GlobalInMemoryCommandResponse second = service.enroll(commandId, 1001L, 20L, null);

        assertThat(second.getResult()).isEqualTo(first.getResult());
        assertThat(service.commandExecutionCountForDebug()).isEqualTo(1);
        assertThat(service.completedCommandOrderSizeForDebug()).isEqualTo(1);
        verify(stateStore, timeout(500).times(1)).evaluateAndEnroll(1001L, 20L);
    }

    @Test
    void completedSuccessIsAddedToFifoQueueOnlyOnce() {
        UUID commandId = UUID.randomUUID();

        service.enroll(commandId, 1001L, 20L, null);
        service.enroll(commandId, 1001L, 20L, null);

        assertThat(service.commandExecutionCountForDebug()).isEqualTo(1);
        assertThat(service.completedCommandOrderSizeForDebug()).isEqualTo(1);
        assertThat(service.completedCommandEvictionCountForDebug()).isZero();
    }

    @Test
    void returnsConflictWhenSameCommandIdHasDifferentPayload() {
        UUID commandId = UUID.randomUUID();

        service.enroll(commandId, 1001L, 20L, null);
        GlobalInMemoryCommandResponse response = service.enroll(commandId, 1002L, 20L, null);

        assertThat(response.isCommandIdConflict()).isTrue();
        verify(stateStore, timeout(500).times(1)).evaluateAndEnroll(1001L, 20L);
    }

    @Test
    void inFlightDuplicateCommandWaitsForSameExecution() throws Exception {
        UUID commandId = UUID.randomUUID();
        CountDownLatch processingStarted = new CountDownLatch(1);
        CountDownLatch releaseProcessing = new CountDownLatch(1);
        when(stateStore.evaluateAndEnroll(1001L, 20L)).thenAnswer(invocation -> {
            processingStarted.countDown();
            releaseProcessing.await(1, TimeUnit.SECONDS);
            return null;
        });

        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<GlobalInMemoryCommandResponse> first = executor.submit(
                    () -> service.enroll(commandId, 1001L, 20L, null)
            );
            assertThat(processingStarted.await(1, TimeUnit.SECONDS)).isTrue();
            Future<GlobalInMemoryCommandResponse> second = executor.submit(
                    () -> service.enroll(commandId, 1001L, 20L, null)
            );

            releaseProcessing.countDown();

            assertThat(second.get(1, TimeUnit.SECONDS).getResult()).isEqualTo(first.get(1, TimeUnit.SECONDS).getResult());
            verify(stateStore, timeout(500).times(1)).evaluateAndEnroll(1001L, 20L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void completedResultCacheEvictsOldestCompletedEntryWhenMaxSizeIsExceeded() {
        restartService(1, 1_000, 100);
        UUID firstCommandId = UUID.randomUUID();
        UUID secondCommandId = UUID.randomUUID();

        service.enroll(firstCommandId, 1001L, 20L, null);
        service.enroll(secondCommandId, 1002L, 21L, null);
        service.enroll(firstCommandId, 1001L, 20L, null);

        verify(stateStore, timeout(500).times(2)).evaluateAndEnroll(1001L, 20L);
        verify(stateStore, timeout(500).times(1)).evaluateAndEnroll(1002L, 21L);
        assertThat(service.commandExecutionCountForDebug()).isEqualTo(1);
        assertThat(service.completedCommandOrderSizeForDebug()).isEqualTo(1);
        assertThat(service.completedCommandEvictionCountForDebug()).isEqualTo(2);
    }

    @Test
    void completedResultEvictionFollowsFifoOrder() {
        restartService(2, 1_000, 100);
        UUID firstCommandId = UUID.randomUUID();
        UUID secondCommandId = UUID.randomUUID();
        UUID thirdCommandId = UUID.randomUUID();

        service.enroll(firstCommandId, 1001L, 20L, null);
        service.enroll(secondCommandId, 1002L, 21L, null);
        service.enroll(thirdCommandId, 1003L, 22L, null);

        service.enroll(secondCommandId, 1002L, 21L, null);
        service.enroll(thirdCommandId, 1003L, 22L, null);
        service.enroll(firstCommandId, 1001L, 20L, null);

        verify(stateStore, timeout(500).times(2)).evaluateAndEnroll(1001L, 20L);
        verify(stateStore, timeout(500).times(1)).evaluateAndEnroll(1002L, 21L);
        verify(stateStore, timeout(500).times(1)).evaluateAndEnroll(1003L, 22L);
        assertThat(service.commandExecutionCountForDebug()).isEqualTo(2);
        assertThat(service.completedCommandOrderSizeForDebug()).isEqualTo(2);
    }

    @Test
    void processingEntryIsNotEvicted() throws Exception {
        restartService(0, 1_000, 100);
        UUID commandId = UUID.randomUUID();
        CountDownLatch processingStarted = new CountDownLatch(1);
        CountDownLatch releaseProcessing = new CountDownLatch(1);
        when(stateStore.evaluateAndEnroll(1001L, 20L)).thenAnswer(invocation -> {
            processingStarted.countDown();
            releaseProcessing.await(1, TimeUnit.SECONDS);
            return null;
        });

        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<GlobalInMemoryCommandResponse> first = executor.submit(
                    () -> service.enroll(commandId, 1001L, 20L, null)
            );
            assertThat(processingStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(service.commandExecutionCountForDebug()).isEqualTo(1);
            assertThat(service.completedCommandOrderSizeForDebug()).isZero();
            Future<GlobalInMemoryCommandResponse> second = executor.submit(
                    () -> service.enroll(commandId, 1001L, 20L, null)
            );

            releaseProcessing.countDown();

            assertThat(second.get(1, TimeUnit.SECONDS).getResult()).isEqualTo(first.get(1, TimeUnit.SECONDS).getResult());
            verify(stateStore, timeout(500).times(1)).evaluateAndEnroll(1001L, 20L);
            assertThat(service.commandExecutionCountForDebug()).isZero();
            assertThat(service.completedCommandOrderSizeForDebug()).isZero();
            assertThat(service.completedCommandEvictionCountForDebug()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void domainRejectionIsCachedForDuplicateCommand() {
        UUID commandId = UUID.randomUUID();
        when(stateStore.evaluateAndEnroll(1001L, 20L))
                .thenReturn(GlobalInMemoryEnrollmentRejectionReason.CAPACITY_EXCEEDED);

        GlobalInMemoryCommandResponse first = service.enroll(commandId, 1001L, 20L, null);
        GlobalInMemoryCommandResponse second = service.enroll(commandId, 1001L, 20L, null);

        assertThat(second.getResult()).isEqualTo(first.getResult());
        assertThat(second.getResult().accepted()).isFalse();
        assertThat(service.commandExecutionCountForDebug()).isEqualTo(1);
        assertThat(service.completedCommandOrderSizeForDebug()).isEqualTo(1);
        verify(stateStore, timeout(500).times(1)).evaluateAndEnroll(1001L, 20L);
    }

    @Test
    void domainRejectionIsAddedToFifoQueue() {
        UUID commandId = UUID.randomUUID();
        when(stateStore.evaluateAndEnroll(1001L, 20L))
                .thenReturn(GlobalInMemoryEnrollmentRejectionReason.CAPACITY_EXCEEDED);

        service.enroll(commandId, 1001L, 20L, null);

        assertThat(service.commandExecutionCountForDebug()).isEqualTo(1);
        assertThat(service.completedCommandOrderSizeForDebug()).isEqualTo(1);
    }

    @Test
    void domainRejectionIsEvictedAsCompletedResult() {
        restartService(1, 1_000, 100);
        UUID firstCommandId = UUID.randomUUID();
        UUID secondCommandId = UUID.randomUUID();
        when(stateStore.evaluateAndEnroll(1001L, 20L))
                .thenReturn(GlobalInMemoryEnrollmentRejectionReason.CAPACITY_EXCEEDED);

        service.enroll(firstCommandId, 1001L, 20L, null);
        service.enroll(secondCommandId, 1002L, 21L, null);
        GlobalInMemoryCommandResponse retried = service.enroll(firstCommandId, 1001L, 20L, null);

        assertThat(retried.getResult()).isEqualTo(GlobalInMemoryEnrollmentRejected.of(
                firstCommandId,
                1001L,
                20L,
                GlobalInMemoryEnrollmentRejectionReason.CAPACITY_EXCEEDED
        ));
        verify(stateStore, timeout(500).times(2)).evaluateAndEnroll(1001L, 20L);
        assertThat(service.commandExecutionCountForDebug()).isEqualTo(1);
        assertThat(service.completedCommandOrderSizeForDebug()).isEqualTo(1);
    }

    @Test
    void systemExceptionIsNotConvertedToDomainRejection() {
        UUID commandId = UUID.randomUUID();
        when(stateStore.evaluateAndEnroll(1001L, 20L)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.enroll(commandId, 1001L, 20L, null))
                .isInstanceOf(CommandProcessingException.class);
        assertThat(service.commandExecutionCountForDebug()).isZero();
        assertThat(service.completedCommandOrderSizeForDebug()).isZero();
    }

    @Test
    void systemExceptionIsNotCachedAndCanBeRetriedWithSameCommandId() {
        UUID commandId = UUID.randomUUID();
        when(stateStore.evaluateAndEnroll(1001L, 20L))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(null);

        assertThatThrownBy(() -> service.enroll(commandId, 1001L, 20L, null))
                .isInstanceOf(CommandProcessingException.class);

        GlobalInMemoryCommandResponse retryResponse = service.enroll(commandId, 1001L, 20L, null);

        assertThat(retryResponse.getResult()).isEqualTo(GlobalInMemoryEnrollmentAccepted.of(commandId, 1001L, 20L));
    }

    @Test
    void bulkCompletedEvictionKeepsMapAndQueueWithinLimit() {
        restartService(3, 1_000, 100);

        for (long i = 1; i <= 20; i++) {
            service.enroll(UUID.randomUUID(), 1000L + i, 20L + i, null);
        }

        assertThat(service.commandExecutionCountForDebug()).isEqualTo(3);
        assertThat(service.completedCommandOrderSizeForDebug()).isEqualTo(3);
        assertThat(service.completedCommandEvictionCountForDebug()).isEqualTo(17);
    }

    @Test
    void evictionImplementationDoesNotIterateOverCommandExecutionMap() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/badapodo/sugang/service/globalinmemory/GlobalInMemorySingleWriterEnrollmentService.java"
        ));

        assertThat(source).doesNotContain("commandExecutions.values()");
        assertThat(source).doesNotContain("commandExecutions.entrySet()");
        assertThat(source).doesNotContain("stream()");
        assertThat(source).doesNotContain("Comparator");
        assertThat(source).doesNotContain("oldestCompletedExecution");
        assertThat(source).doesNotContain("completedExecutionCount");
    }

    private void assertRejected(GlobalInMemoryEnrollmentRejectionReason reason) {
        UUID commandId = UUID.randomUUID();
        when(stateStore.evaluateAndEnroll(1001L, 20L)).thenReturn(reason);

        GlobalInMemoryCommandResponse response = service.enroll(commandId, 1001L, 20L, null);

        assertThat(response.getResult()).isEqualTo(GlobalInMemoryEnrollmentRejected.of(commandId, 1001L, 20L, reason));
    }

    private void restartService(int maxCompletedCommandEntries, long responseTimeoutMs, int queueCapacity) {
        service.stopWriter();
        service = new GlobalInMemorySingleWriterEnrollmentService(
                stateStore,
                writeBehindService,
                new SimpleMeterRegistry(),
                queueCapacity,
                responseTimeoutMs,
                maxCompletedCommandEntries
        );
        service.startWriter();
    }
}

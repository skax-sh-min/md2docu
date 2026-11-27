package com.md2docu.service;

import com.md2docu.model.ConvertResult;
import com.md2docu.model.ConvertWarning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversionResultStoreTest {
    private static final Instant START = Instant.parse("2026-10-27T00:00:00Z");
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final Clock clock = mock(Clock.class);
    private ConversionResultStore store;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenAnswer(invocation -> now.get());
        store = new ConversionResultStore(10, 3, 10, clock);
    }

    @ParameterizedTest
    @CsvSource({"-1,false", "0,false", "1,false", "-1,true", "0,true", "1,true"})
    void checksExactExpiryOnEitherLookupWithoutScheduledCleanup(long offsetNanos, boolean warningsFirst) {
        ConvertResult result = store.put(result("job", 2));
        assertThat(result.getExpiresAt()).isEqualTo(START.plusSeconds(10));
        now.set(result.getExpiresAt().plusNanos(offsetNanos));
        Object first = warningsFirst ? store.getWarnings("job") : store.get("job");
        Object second = warningsFirst ? store.get("job") : store.getWarnings("job");
        if (offsetNanos < 0) {
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            assertUsage(1, 2);
        } else {
            assertThat(first).isNull();
            assertThat(second).isNull();
            assertUsage(0, 0);
        }
    }

    @Test
    void lookupsDoNotRenewExpiry() {
        store.put(result("job", 2));
        now.set(START.plusSeconds(9));
        assertThat(store.get("job").getExpiresAt()).isEqualTo(START.plusSeconds(10));
        assertThat(store.getWarnings("job")).hasSize(1);
        now.set(START.plusSeconds(10));
        assertThat(store.get("job")).isNull();
    }

    @Test
    void scheduledCleanupRemovesResultsAtTheExactBoundaryAndKeepsNewerOnes() {
        store.put(result("old", 4));
        now.set(START.plusSeconds(1));
        store.put(result("new", 6));
        now.set(START.plusSeconds(10));
        store.evictExpired();
        assertUsage(1, 6);
        assertThat(store.get("old")).isNull();
        assertThat(store.get("new")).isNotNull();
        now.set(START.plusSeconds(11));
        store.evictExpired();
        store.evictExpired();
        assertUsage(0, 0);
    }

    @Test
    void removesExpiredEntriesBeforeEvictingLiveEntries() {
        store.put(result("old", 4));
        now.set(START.plusSeconds(1));
        store.put(result("live", 4));
        now.set(START.plusSeconds(10));
        store.put(result("new", 6));
        assertThat(store.get("old")).isNull();
        assertThat(store.get("live")).isNotNull();
        assertUsage(2, 10);
    }

    @Test
    void enforcesCountLimitInInsertionOrderEvenForEqualTimesAndAfterLookups() {
        for (String id : List.of("a", "b", "c")) store.put(result(id, 1));
        store.get("a");
        store.getWarnings("a");
        store.put(result("d", 1));
        assertThat(store.get("a")).isNull();
        for (String id : List.of("b", "c", "d")) assertThat(store.get(id)).isNotNull();
        assertUsage(3, 3);
    }

    @Test
    void acceptsExactByteLimitAndEvictsOnlyEnoughOldestEntries() {
        store.put(result("a", 4));
        store.put(result("b", 6));
        assertUsage(2, 10);
        store.put(result("c", 1));
        assertThat(store.get("a")).isNull();
        assertThat(store.get("b")).isNotNull();
        assertUsage(2, 7);
        store.put(result("d", 9));
        assertThat(store.get("b")).isNull();
        assertUsage(2, 10);
        store.put(result("e", 10));
        assertThat(store.get("c")).isNull();
        assertThat(store.get("d")).isNull();
        assertUsage(1, 10);
    }

    @Test
    void rejectsOneByteOverCapacityWithoutEvictionOrExpiryChanges() {
        ConvertResult original = store.put(result("a", 10));
        assertThatThrownBy(() -> store.put(result("too-large", 11)))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(413));
        assertThat(store.get("a")).isEqualTo(original);
        assertThat(store.get("too-large")).isNull();
        assertUsage(1, 10);
    }

    @Test
    void replacingTheSameIdUpdatesAccountingAndInsertionOrder() {
        store.put(result("a", 6));
        store.put(result("b", 4));
        store.put(result("a", 2));
        assertUsage(2, 6);
        store.put(result("c", 7));
        assertThat(store.get("b")).isNull();
        assertThat(store.get("a").getFileBytes()).hasSize(2);
        assertUsage(2, 9);
    }

    @Test
    void isolatesStoredBytesMetadataAndMutableWarningsFromCallers() {
        ConvertResult input = store.put(result("job", 3));
        input.getFileBytes()[0] = 100;
        input.setFileBytes(new byte[50]);
        input.setFileName("changed");
        input.setExpiresAt(Instant.MAX);
        input.getWarnings().get(0).setMessage("changed");
        input.getWarnings().clear();
        ConvertResult first = store.get("job");
        assertThat(first.getFileName()).isEqualTo("job.docx");
        assertThat(first.getFileBytes()).containsExactly(0, 0, 0);
        assertThat(first.getExpiresAt()).isEqualTo(START.plusSeconds(10));
        assertThat(first.getWarnings().get(0).getMessage()).isEqualTo("message");
        first.getFileBytes()[0] = 99;
        first.setFileBytes(new byte[30]);
        first.getWarnings().get(0).setType("changed");
        store.getWarnings("job").get(0).setTarget("changed");
        assertThat(store.get("job").getFileBytes()).containsExactly(0, 0, 0);
        assertThat(store.getWarnings("job")).containsExactly(new ConvertWarning("TYPE", "target", "message"));
        assertUsage(1, 3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acquiredDownloadSurvivesExpiryOrCapacityEviction(boolean expire) {
        store.put(result("job", 10));
        ConvertResult download = store.get("job");
        if (expire) {
            now.set(START.plusSeconds(10));
            store.evictExpired();
        } else {
            store.put(result("replacement", 10));
        }
        assertThat(store.get("job")).isNull();
        assertThat(store.getWarnings("job")).isNull();
        assertThat(download.getFileBytes()).hasSize(10);
        assertThat(download.getFileName()).isEqualTo("job.docx");
    }

    @Test
    void checksAndAccountsForTheSameByteArrayEvenIfTheInputReferenceChanges() {
        ConvertResult input = spy(result("job", 1));
        when(input.getFileBytes()).thenReturn(new byte[1], new byte[11]);
        store.put(input);
        verify(input, times(1)).getFileBytes();
        assertThat(store.get("job").getFileBytes()).hasSize(1);
        assertUsage(1, 1);
    }

    @Test
    void replacesTheSnapshotIdConsistentlyIfInputMetadataChangesDuringCapture() {
        store.put(result("a", 4));
        store.put(result("b", 6));
        ConvertResult input = spy(result("b", 1));
        when(input.getJobId()).thenReturn("b", "a");
        store.put(input);
        assertThat(store.get("a").getFileBytes()).hasSize(4);
        assertThat(store.get("b").getFileBytes()).hasSize(1);
        assertUsage(2, 5);
        verify(input, times(1)).getJobId();
    }

    @Test
    void emptyFilesStillCountTowardTheResultLimit() {
        for (int i = 0; i < 4; i++) store.put(result("job-" + i, 0));
        assertThat(store.get("job-0")).isNull();
        assertUsage(3, 0);
    }

    @Test
    void aNewStoreDoesNotRestorePreviousResults() {
        store.put(result("job", 2));
        var restarted = new ConversionResultStore(10, 3, 10, clock);
        assertThat(restarted.get("job")).isNull();
        assertThat(restarted.getWarnings("job")).isNull();
    }

    @Test
    void concurrentStoresLookupsCleanupAndEvictionsKeepExactBoundedAccounting() throws Exception {
        store = new ConversionResultStore(2, 7, 63, clock);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(12)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 36; worker++) {
                final int id = worker;
                tasks.add(executor.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start timed out");
                    for (int i = 0; i < 40; i++) {
                        String job = id + "-" + i;
                        store.put(result(job, 1 + (id + i) % 17));
                        store.get(job);
                        store.getWarnings(job);
                        if (i % 8 == 0) now.updateAndGet(time -> time.plusSeconds(1));
                        if (i % 3 == 0) store.evictExpired();
                        assertBoundedAccounting();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(30, TimeUnit.SECONDS);
        }
        assertBoundedAccounting();
        now.updateAndGet(time -> time.plusSeconds(2));
        store.evictExpired();
        assertUsage(0, 0);
    }

    @Test
    void bindsAllLimitsFromConfiguration() {
        new ApplicationContextRunner().withUserConfiguration(ConversionResultStore.class)
            .withPropertyValues("app.convert.temp-expiry-seconds=7", "app.convert.max-stored-results=2",
                "app.convert.max-stored-bytes=5", "app.convert.cleanup-interval-ms=600000")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var configured = context.getBean(ConversionResultStore.class);
                Instant before = Instant.now();
                ConvertResult a = configured.put(result("a", 2));
                assertThat(a.getExpiresAt()).isBetween(before.plusSeconds(7), Instant.now().plusSeconds(7));
                configured.put(result("b", 3));
                configured.put(result("c", 1));
                assertThat(configured.get("a")).isNull();
                assertThat(configured.get("b")).isNotNull();
                assertThatThrownBy(() -> configured.put(result("too-large", 6)))
                    .isInstanceOf(ResponseStatusException.class);
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "app.convert.temp-expiry-seconds=0", "app.convert.temp-expiry-seconds=-1",
        "app.convert.max-stored-results=0", "app.convert.max-stored-results=-1",
        "app.convert.max-stored-bytes=0", "app.convert.max-stored-bytes=-1",
        "app.convert.temp-expiry-seconds=9223372036854775807",
        "app.convert.max-stored-results=2147483648", "app.convert.max-stored-bytes=invalid"
    })
    void rejectsInvalidConfigurationAtStartup(String property) {
        new ApplicationContextRunner().withUserConfiguration(ConversionResultStore.class)
            .withPropertyValues(property)
            .run(context -> assertThat(context).hasFailed());
    }

    private void assertUsage(int count, long bytes) {
        synchronized (store) {
            assertThat(ReflectionTestUtils.getField(store, "results"))
                .isInstanceOfSatisfying(Map.class, entries -> assertThat(entries).hasSize(count));
            assertThat(ReflectionTestUtils.getField(store, "storedBytes")).isEqualTo(bytes);
        }
    }

    private void assertBoundedAccounting() {
        synchronized (store) {
            Object value = ReflectionTestUtils.getField(store, "results");
            assertThat(value).isInstanceOf(Map.class);
            Map<?, ?> entries = (Map<?, ?>) value;
            assertThat(entries.size()).isLessThanOrEqualTo(7);
            long actualBytes = entries.values().stream().map(ConvertResult.class::cast)
                .mapToLong(result -> result.getFileBytes().length).sum();
            assertThat(actualBytes).isBetween(0L, 63L);
            assertThat(ReflectionTestUtils.getField(store, "storedBytes")).isEqualTo(actualBytes);
        }
    }

    private static ConvertResult result(String id, int bytes) {
        var result = new ConvertResult();
        result.setJobId(id);
        result.setDownloadUrl("/api/download/" + id);
        result.setFileName(id + ".docx");
        result.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        result.setFileBytes(new byte[bytes]);
        result.setWarnings(new ArrayList<>(List.of(new ConvertWarning("TYPE", "target", "message"))));
        return result;
    }
}

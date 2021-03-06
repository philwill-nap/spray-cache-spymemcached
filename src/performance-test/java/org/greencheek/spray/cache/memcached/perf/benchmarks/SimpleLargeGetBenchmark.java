package org.greencheek.spray.cache.memcached.perf.benchmarks;

import org.greencheek.spray.cache.memcached.perf.state.*;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.GenerateMicroBenchmark;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.output.results.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.VerboseMode;

import java.util.concurrent.TimeUnit;

/**
 * Created by dominictootell on 01/06/2014.
 */
public class SimpleLargeGetBenchmark {

    @GenerateMicroBenchmark
    @BenchmarkMode({Mode.SampleTime})
    public LargeCacheObject simpleGet(LargeCacheKey key, LargeCacheObject value,XXJavaHashLargeTextBenchmarkCache cache) {
        return org.greencheek.spray.cache.memcached.perftests.SimpleGetBenchmarks.testLargeGet(key, value, cache);
    }

    @GenerateMicroBenchmark
    @BenchmarkMode({Mode.SampleTime})
    public LargeCacheObject simpleGetWithFSTStaleCache(LargeCacheKey key, LargeCacheObject value,XXJavaHashLargeTextBenchmarkCacheWithFSTWithStale cache) {
        return org.greencheek.spray.cache.memcached.perftests.SimpleGetBenchmarks.testLargeGetWithFSTWithStale(key, value, cache);
    }

    @GenerateMicroBenchmark
    @BenchmarkMode({Mode.SampleTime})
    public LargeCacheObject simpleGetWithFST(LargeCacheKey key, LargeCacheObject value,XXJavaHashLargeTextBenchmarkCacheWithFST cache) {
        return org.greencheek.spray.cache.memcached.perftests.SimpleGetBenchmarks.testLargeGetWithFST(key, value, cache);
    }

    @GenerateMicroBenchmark
    @BenchmarkMode({Mode.SampleTime})
    public LargeCacheObject simpleGetWithFSTKnownClasses(LargeCacheKey key, LargeCacheObject value,XXJavaHashLargeTextBenchmarkCacheWithFSTKnownClasses cache) {
        return org.greencheek.spray.cache.memcached.perftests.SimpleGetBenchmarks.testLargeGetWithFSTKnownClasses(key, value, cache);
    }



    public static void main(String[] args) throws RunnerException {
        Options opts  = new OptionsBuilder()
                .include("org.greencheek.spray.cache.memcached.perf.benchmarks.SimpleLargeGetBenchmark.*")
                .warmupIterations(20)
                .measurementIterations(20)
                .timeUnit(TimeUnit.MILLISECONDS)
                .threads(2)
                .forks(3)
                .jvmArgs(JvmArgs.getJvmArgs())
                .resultFormat(ResultFormatType.TEXT)
                .verbosity(VerboseMode.EXTRA)
                .build();

        new Runner(opts).run();
    }
}

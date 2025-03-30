/*
 * Copyright 2015-2025 Endre Stølsvik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mats3.util;

import static io.mats3.util.Tools.ms2;

import java.io.IOException;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import io.mats3.util.DummyFinancialService.CustomerData;
import io.mats3.util.compression.InflaterInputStreamWithStats;

public class Test_DeserializationPerformance {
    private final ObjectMapper _mapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();
    private final ObjectReader _replyDtoReader = _mapper.readerFor(CustomerData.class);

    @Test
    public void runSingle() throws IOException {
        CustomerData randomCustomerData = DummyFinancialService.createRandomReplyDTO(1234L, 1000);
        byte[] serComp = new Test_SerializationPerformance().serializeDto(3, 512, randomCustomerData);
        System.out.println("Serialized and compressed size: " + serComp.length + " bytes");
        var inflaterStream = new InflaterInputStreamWithStats(serComp);
        CustomerData deserialized = _replyDtoReader.readValue(inflaterStream);
        System.out.println("Deserialized DTO with " + deserialized.customers.size() + " customers");
    }

    @Test
    public void exerciseRunner() throws Exception {
        // Default-run when testing:
        // runPerformanceTest(3, 1234L, 100, 10);

        // @Test-run just to exercise the code (running on CI server)
        // NOTE: Reference params are: level=3, seed=1234L, customers=1000, rounds=100
        runPerformanceTest(3, 1234L, 100, 10);
    }

    public void runPerformanceTest(int level, long seed, int cumstomers, int rounds) throws Exception {
        System.out.println("Running performance test with level " + level + ", seed " + seed + ", rounds " + rounds
                + ", customers " + cumstomers);
        System.out.println("Warmup..");
        for (int i = 0; i < 5; i++) {
            performanceRun(level, seed, rounds / 10, cumstomers);
        }
        System.out.println("Warmup done!\n");

        for (int i = 0; i < 10; i++) {
            performanceRun(level, seed, rounds, cumstomers);
        }

        // WITHOUT Blackbird
        // ===================

        // Excluded compression (level ignored):
        // tot: 2236.49, inflate: 0.00, deser: 2236.49 ms, 100 rounds, lvl:0, 2484728 B, custs:1000
        // tot: 2241.04, inflate: 0.00, deser: 2241.04 ms, 100 rounds, lvl:0, 2484728 B, custs:1000
        // tot: 2231.31, inflate: 0.00, deser: 2231.31 ms, 100 rounds, lvl:0, 2484728 B, custs:1000
        // tot: 2212.24, inflate: 0.00, deser: 2212.24 ms, 100 rounds, lvl:0, 2484728 B, custs:1000
        // tot: 2231.90, inflate: 0.00, deser: 2231.90 ms, 100 rounds, lvl:0, 2484728 B, custs:1000
        // tot: 2228.81, inflate: 0.00, deser: 2228.81 ms, 100 rounds, lvl:0, 2484728 B, custs:1000
        // tot: 2233.00, inflate: 0.00, deser: 2233.00 ms, 100 rounds, lvl:0, 2484728 B, custs:1000
        // tot: 2221.24, inflate: 0.00, deser: 2221.24 ms, 100 rounds, lvl:0, 2484728 B, custs:1000
        // tot: 2216.50, inflate: 0.00, deser: 2216.50 ms, 100 rounds, lvl:0, 2484728 B, custs:1000
        // tot: 2223.10, inflate: 0.00, deser: 2223.10 ms, 100 rounds, lvl:0, 2484728 B, custs:1000

        // WITHOUT Blackbird, level=0:
        // tot: 2343.17, inflate: 113.26, deser: 2229.91 ms, 100 rounds, lvl:0, 2485059 B, custs:1000
        // tot: 2348.55, inflate: 114.08, deser: 2234.48 ms, 100 rounds, lvl:0, 2485059 B, custs:1000
        // tot: 2346.64, inflate: 113.35, deser: 2233.29 ms, 100 rounds, lvl:0, 2485059 B, custs:1000
        // tot: 2332.79, inflate: 111.22, deser: 2221.57 ms, 100 rounds, lvl:0, 2485059 B, custs:1000
        // tot: 2332.08, inflate: 110.16, deser: 2221.92 ms, 100 rounds, lvl:0, 2485059 B, custs:1000
        // tot: 2332.20, inflate: 110.70, deser: 2221.51 ms, 100 rounds, lvl:0, 2485059 B, custs:1000
        // tot: 2334.30, inflate: 111.66, deser: 2222.64 ms, 100 rounds, lvl:0, 2485059 B, custs:1000
        // tot: 2340.98, inflate: 111.96, deser: 2229.02 ms, 100 rounds, lvl:0, 2485059 B, custs:1000
        // tot: 2336.70, inflate: 112.62, deser: 2224.08 ms, 100 rounds, lvl:0, 2485059 B, custs:1000
        // tot: 2341.38, inflate: 111.81, deser: 2229.57 ms, 100 rounds, lvl:0, 2485059 B, custs:1000

        // WITHOUT Blackbird, level=3:
        // tot: 3128.58, inflate: 864.99, deser: 2263.60 ms, 100 rounds, lvl:3, 687898 B, custs:1000
        // tot: 3133.77, inflate: 867.04, deser: 2266.73 ms, 100 rounds, lvl:3, 687898 B, custs:1000
        // tot: 3147.11, inflate: 869.04, deser: 2278.07 ms, 100 rounds, lvl:3, 687898 B, custs:1000
        // tot: 3085.48, inflate: 829.96, deser: 2255.52 ms, 100 rounds, lvl:3, 687898 B, custs:1000
        // tot: 3068.22, inflate: 813.42, deser: 2254.80 ms, 100 rounds, lvl:3, 687898 B, custs:1000
        // tot: 3049.01, inflate: 809.92, deser: 2239.09 ms, 100 rounds, lvl:3, 687898 B, custs:1000
        // tot: 3061.67, inflate: 812.82, deser: 2248.86 ms, 100 rounds, lvl:3, 687898 B, custs:1000
        // tot: 3058.92, inflate: 811.25, deser: 2247.67 ms, 100 rounds, lvl:3, 687898 B, custs:1000
        // tot: 3054.90, inflate: 810.13, deser: 2244.77 ms, 100 rounds, lvl:3, 687898 B, custs:1000
        // tot: 3051.52, inflate: 809.06, deser: 2242.47 ms, 100 rounds, lvl:3, 687898 B, custs:1000

        // USING Blackbird
        // ===================

        // Excluded compression (level ignored):
        // tot: 2130.59, inflate: 0.00, deser: 2130.59 ms, 100 rounds, lvl:0, 2484728 B, custs:1000
        // tot: 2126.50, inflate: 0.00, deser: 2126.50 ms, 100 rounds, lvl:0, 2484728 B, custs:1000
        // tot: 2127.38, inflate: 0.00, deser: 2127.38 ms, 100 rounds, lvl:0, 2484728 B, custs:1000
        // tot: 2110.07, inflate: 0.00, deser: 2110.07 ms, 100 rounds, lvl:0, 2484728 B, custs:1000
        // tot: 2132.23, inflate: 0.00, deser: 2132.23 ms, 100 rounds, lvl:0, 2484728 B, custs:1000
        // tot: 2116.11, inflate: 0.00, deser: 2116.11 ms, 100 rounds, lvl:0, 2484728 B, custs:1000
        // tot: 2117.14, inflate: 0.00, deser: 2117.14 ms, 100 rounds, lvl:0, 2484728 B, custs:1000
        // tot: 2113.93, inflate: 0.00, deser: 2113.93 ms, 100 rounds, lvl:0, 2484728 B, custs:1000
        // tot: 2113.61, inflate: 0.00, deser: 2113.61 ms, 100 rounds, lvl:0, 2484728 B, custs:1000
        // tot: 2120.66, inflate: 0.00, deser: 2120.66 ms, 100 rounds, lvl:0, 2484728 B, custs:1000

        // level=0:
        // tot: 2197.49, inflate: 112.68, deser: 2084.81 ms, 100 rounds, lvl:0, 2485059 B, custs:1000
        // tot: 2174.55, inflate: 107.73, deser: 2066.83 ms, 100 rounds, lvl:0, 2485059 B, custs:1000
        // tot: 2173.82, inflate: 108.17, deser: 2065.65 ms, 100 rounds, lvl:0, 2485059 B, custs:1000
        // tot: 2157.71, inflate: 107.23, deser: 2050.48 ms, 100 rounds, lvl:0, 2485059 B, custs:1000
        // tot: 2147.47, inflate: 105.17, deser: 2042.30 ms, 100 rounds, lvl:0, 2485059 B, custs:1000
        // tot: 2144.43, inflate: 104.40, deser: 2040.03 ms, 100 rounds, lvl:0, 2485059 B, custs:1000
        // tot: 2138.60, inflate: 104.22, deser: 2034.39 ms, 100 rounds, lvl:0, 2485059 B, custs:1000
        // tot: 2150.48, inflate: 104.52, deser: 2045.97 ms, 100 rounds, lvl:0, 2485059 B, custs:1000
        // tot: 2152.18, inflate: 105.10, deser: 2047.09 ms, 100 rounds, lvl:0, 2485059 B, custs:1000
        // tot: 2141.62, inflate: 104.48, deser: 2037.13 ms, 100 rounds, lvl:0, 2485059 B, custs:1000

        // level=3:
        // tot: 3026.66, inflate: 867.03, deser: 2159.63 ms, 100 rounds, lvl:3, 687898 B, custs:1000
        // tot: 3016.71, inflate: 866.34, deser: 2150.37 ms, 100 rounds, lvl:3, 687898 B, custs:1000
        // tot: 3024.25, inflate: 867.39, deser: 2156.86 ms, 100 rounds, lvl:3, 687898 B, custs:1000
        // tot: 3041.52, inflate: 868.91, deser: 2172.60 ms, 100 rounds, lvl:3, 687898 B, custs:1000
        // tot: 3075.20, inflate: 878.05, deser: 2197.15 ms, 100 rounds, lvl:3, 687898 B, custs:1000
        // tot: 3086.03, inflate: 879.10, deser: 2206.94 ms, 100 rounds, lvl:3, 687898 B, custs:1000
        // tot: 3104.22, inflate: 883.93, deser: 2220.29 ms, 100 rounds, lvl:3, 687898 B, custs:1000
        // tot: 3036.72, inflate: 859.92, deser: 2176.81 ms, 100 rounds, lvl:3, 687898 B, custs:1000
        // tot: 2948.48, inflate: 811.98, deser: 2136.50 ms, 100 rounds, lvl:3, 687898 B, custs:1000
        // tot: 2927.72, inflate: 808.84, deser: 2118.88 ms, 100 rounds, lvl:3, 687898 B, custs:1000

        // level=6:
        // tot: 3047.57, inflate: 809.67, deser: 2237.90 ms, 100 rounds, lvl:6, 612162 B, custs:1000
        // tot: 3017.13, inflate: 803.87, deser: 2213.26 ms, 100 rounds, lvl:6, 612162 B, custs:1000
        // tot: 3007.59, inflate: 802.50, deser: 2205.09 ms, 100 rounds, lvl:6, 612162 B, custs:1000
        // tot: 3014.05, inflate: 804.13, deser: 2209.92 ms, 100 rounds, lvl:6, 612162 B, custs:1000
        // tot: 2998.53, inflate: 800.63, deser: 2197.90 ms, 100 rounds, lvl:6, 612162 B, custs:1000
        // tot: 3009.35, inflate: 804.17, deser: 2205.18 ms, 100 rounds, lvl:6, 612162 B, custs:1000
        // tot: 3002.33, inflate: 802.83, deser: 2199.50 ms, 100 rounds, lvl:6, 612162 B, custs:1000
        // tot: 3005.18, inflate: 802.48, deser: 2202.69 ms, 100 rounds, lvl:6, 612162 B, custs:1000
        // tot: 3025.41, inflate: 808.28, deser: 2217.13 ms, 100 rounds, lvl:6, 612162 B, custs:1000
        // tot: 3017.67, inflate: 805.06, deser: 2212.62 ms, 100 rounds, lvl:6, 612162 B, custs:1000

    }

    public void performanceRun(int level, long seed, int rounds, int cumstomers) throws Exception {
        CustomerData randomCustomerData = DummyFinancialService.createRandomReplyDTO(seed, cumstomers);
        byte[] serComp = new Test_SerializationPerformance().serializeDto(level, 512, randomCustomerData);

        long nanos_Total = 0;
        long nanos_Inflate_Total = 0;
        long nanos_Deserialize_Total = 0;
        CustomerData deserialized = null;
        for (int i = 0; i < rounds; i++) {
            long nanosStart_Total = System.nanoTime();
            var inflaterStream = new InflaterInputStreamWithStats(serComp);
            deserialized = _replyDtoReader.readValue(inflaterStream);
            long nanosTaken_Total = System.nanoTime() - nanosStart_Total;
            nanos_Total += nanosTaken_Total;
            nanos_Inflate_Total += inflaterStream.getReadAndInflateTimeNanos();
            nanos_Deserialize_Total += nanosTaken_Total - inflaterStream.getReadAndInflateTimeNanos();
        }

        assert deserialized != null;
        System.out.println("tot: " + ms2(nanos_Total) + ", inflate: " + ms2(nanos_Inflate_Total)
                + ", deser: " + ms2(nanos_Deserialize_Total) + " ms, " + rounds + " rounds, lvl:"
                + level + ", " + serComp.length + " B, custs:" + deserialized.customers.size());
    }
}

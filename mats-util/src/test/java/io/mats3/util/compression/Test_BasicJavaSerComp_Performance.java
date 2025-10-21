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

package io.mats3.util.compression;

import static io.mats3.util.Tools.fd2;
import static io.mats3.util.Tools.ft;
import static io.mats3.util.Tools.ms2;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterInputStream;

import org.junit.Assert;
import org.junit.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;

import io.mats3.util.DummyFinancialService;
import io.mats3.util.DummyFinancialService.CustomerData;
import io.mats3.util.FieldBasedJacksonMapper;

/**
 * Getting some basic numbers on the full roundtrip of serialization, compression, uncompression and deserialization,
 * using plain java.
 */
public class Test_BasicJavaSerComp_Performance {

    private final ObjectMapper _mapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();
    private final ObjectWriter _replyDtoWriter = _mapper.writerFor(CustomerData.class);
    private final ObjectReader _replyDtoReader = _mapper.readerFor(CustomerData.class);

    // For reference, sent:
    // #MATSLOG# STAGE/ENDPOINT completed with result REPLY_SUBSCRIPTION, single outgoing REPLY_SUBSCRIPTION message,
    // total:[28960.0 ms] || breakdown: totPreprocAndDeserial:[0.259 ms], userLambda (excl. produceEnvelopes):[14980.0
    // ms], msgsOut:[13960.0 ms], dbCommit:[3.513 ms], msgSysCommit:[11.83 ms] - sum pieces:[28960.0 ms], diff:[0.779
    // ms]
    // #MATSLOG# STAGE outgoing REPLY_SUBSCRIPTION message from [mServiceMatsFactory|MfexService.getNavHistory] ->
    // [FundService.handleMfexNavCacheReload], total:[13960.0 ms] || breakdown: produce:[9400.0
    // ms]->(envelope)->serial:[1240.0 ms]->serialSize:[353208362 B]->comp:[2829.0 ms]->envelopeWireSize:[50445981
    // B]->msgSysConstruct&Send:[490.5 ms]
    // Mottak:
    // totPreprocAndDeserial:[10090.0] || breakdown: msgSysDeconstruct:[27.61 ms]->envelopeWireSize:[50445981
    // B]->decomp:[1088.0 ms]->serialSize:[353208362 B]->deserial:[5210.0 ms]->(envelope)->dto&stoDeserial:[3763.0 ms] -
    // sum pieces:[10090.0 ms], diff:[0.408 ms]
    //
    // For a comparable "load":
    // 84_000 customers, on level 3, gives:
    // Ser:1498.95 ms, Comp:50,542,430B (5421.76 ms), Uncomp:205,466,445B (825.34 ms), 24.60%, Deser:2007.79 ms
    // Ser:1282.62 ms, Comp:50,542,430B (5485.67 ms), Uncomp:205,466,445B (800.32 ms), 24.60%, Deser:2021.45 ms
    // Ser:1173.83 ms, Comp:50,542,430B (5504.05 ms), Uncomp:205,466,445B (809.31 ms), 24.60%, Deser:1773.86 ms
    // Ser:1325.88 ms, Comp:50,542,430B (5360.03 ms), Uncomp:205,466,445B (807.38 ms), 24.60%, Deser:1942.27 ms
    // Ser:1470.53 ms, Comp:50,542,430B (5714.54 ms), Uncomp:205,466,445B (769.13 ms), 24.60%, Deser:1956.29 ms
    // Ser:1218.30 ms, Comp:50,542,430B (5324.65 ms), Uncomp:205,466,445B (756.01 ms), 24.60%, Deser:1910.62 ms
    // Ser:1248.61 ms, Comp:50,542,430B (5447.15 ms), Uncomp:205,466,445B (809.30 ms), 24.60%, Deser:1879.20 ms
    // Ser:1088.11 ms, Comp:50,542,430B (5229.93 ms), Uncomp:205,466,445B (812.54 ms), 24.60%, Deser:1768.10 ms
    // Ser:1255.69 ms, Comp:50,542,430B (5390.74 ms), Uncomp:205,466,445B (764.10 ms), 24.60%, Deser:1812.31 ms
    // Ser:1733.97 ms, Comp:50,542,430B (5433.16 ms), Uncomp:205,466,445B (794.08 ms), 24.60%, Deser:1938.39 ms
    // 84_000 customers, on level 1, gives:
    // Ser:1523.69 ms, Comp:61,865,914B (2331.10 ms), Uncomp:205,466,445B (887.46 ms), 30.11%, Deser:2614.74 ms
    // Ser:1377.75 ms, Comp:61,865,914B (2194.40 ms), Uncomp:205,466,445B (860.67 ms), 30.11%, Deser:2014.44 ms
    // Ser:1309.28 ms, Comp:61,865,914B (2158.00 ms), Uncomp:205,466,445B (776.89 ms), 30.11%, Deser:2094.33 ms
    // Ser:1352.95 ms, Comp:61,865,914B (2163.22 ms), Uncomp:205,466,445B (780.82 ms), 30.11%, Deser:1793.82 ms
    // Ser:1212.00 ms, Comp:61,865,914B (2272.70 ms), Uncomp:205,466,445B (776.23 ms), 30.11%, Deser:1667.46 ms
    // Ser:1546.03 ms, Comp:61,865,914B (2284.20 ms), Uncomp:205,466,445B (798.51 ms), 30.11%, Deser:1676.93 ms
    // Ser:1370.07 ms, Comp:61,865,914B (2196.56 ms), Uncomp:205,466,445B (839.00 ms), 30.11%, Deser:1741.99 ms
    // Ser:1449.11 ms, Comp:61,865,914B (2771.38 ms), Uncomp:205,466,445B (802.10 ms), 30.11%, Deser:1769.36 ms
    // Ser:1453.00 ms, Comp:61,865,914B (2278.09 ms), Uncomp:205,466,445B (790.26 ms), 30.11%, Deser:1684.85 ms
    // Ser:1288.86 ms, Comp:61,865,914B (2168.30 ms), Uncomp:205,466,445B (793.43 ms), 30.11%, Deser:1844.78 ms

    @Test
    public void basic() throws IOException {
        for (int i = 0; i < 5; i++) {
            // Se above: 84_000 gives about 50MB compressed with level 3, and 60MB with level 1.
            runTest(5);
        }
    }

    private void runTest(int customers) {
        CustomerData randomCustomerData = DummyFinancialService.createRandomReplyDTO(1234L, customers);
        try {

            long nanosAtStart_Total = System.nanoTime();

            long nanosAtStart_Serializing = System.nanoTime();
            byte[] serialized = _replyDtoWriter.writeValueAsBytes(randomCustomerData);
            long nanosTaken_Serializing = System.nanoTime() - nanosAtStart_Serializing;

            System.out.print("Ser:" + ms2(nanosTaken_Serializing) + " ms");
            System.out.flush();

            long nanosAtStart_Compressing = System.nanoTime();
            var bais = new ByteArrayInputStream(serialized);
            Deflater deflater = new Deflater(1);
            var deflaterInputStream = new DeflaterInputStream(bais, deflater);
            byte[] compressed = deflaterInputStream.readAllBytes();
            deflaterInputStream.close();
            deflater.end();
            long nanosTaken_Compressing = System.nanoTime() - nanosAtStart_Compressing;

            System.out.print(", Comp:" + ft(compressed.length) + "B (" + ms2(nanosTaken_Compressing) + " ms)");
            System.out.flush();

            long nanosAtStart_Uncompressing = System.nanoTime();
            var inflaterStream = new InflaterInputStreamWithStats(compressed);
            byte[] uncompressed = inflaterStream.readAllBytes();
            inflaterStream.close();
            long nanosTaken_Uncompressing = System.nanoTime() - nanosAtStart_Uncompressing;

            Assert.assertArrayEquals(serialized, uncompressed);

            _replyDtoReader.readValue(uncompressed);

            System.out.print(", Uncomp:" + ft(uncompressed.length) + "B (" + ms2(nanosTaken_Uncompressing)
                    + " ms), " + fd2(100.0 * compressed.length / uncompressed.length) + "%");
            System.out.flush();

            long nanosAtStart_Deserializing = System.nanoTime();
            _replyDtoReader.readValue(uncompressed);
            long nanosTaken_Deserializing = System.nanoTime() - nanosAtStart_Deserializing;

            long nanosTaken_Total = System.nanoTime() - nanosAtStart_Total;

            System.out.println(", Deser:" + ms2(nanosTaken_Deserializing) + " ms, Tot:" + ms2(nanosTaken_Total) + " ms");
    }
        catch (IOException e) {
            throw new RuntimeException("Ain't happening!", e);
        }
    }
}

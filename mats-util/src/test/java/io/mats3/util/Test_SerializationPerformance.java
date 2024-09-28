package io.mats3.util;

import static io.mats3.util.Tools.ms2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import io.mats3.util.DummyFinancialService.CustomerData;
import io.mats3.util.compression.DeflaterOutputStreamWithStats;

/**
 * Small home-grown performance test harness to evaluate the compression levels and buffer sizes. Seems like level 3 is
 * a good choice, as values above increases the time substantially, while the compression is not that much better. For
 * the buffer size, it seems like 512 is a good choice, as values above does not improve time much at all.
 */
public class Test_SerializationPerformance {

    private final ObjectMapper _mapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();
    private final ObjectWriter _replyDtoWriter = _mapper.writerFor(CustomerData.class);

    @Test
    public void runSingle() throws IOException {
        CustomerData randomCustomerData = DummyFinancialService.createRandomReplyDTO(1234L, 1000);
        byte[] serialized = serializeDto(3, 1024, randomCustomerData);
        System.out.println("Serialized size: " + serialized.length + " bytes");
    }

    @Test
    public void exerciseRunner() throws Exception {
        // Default-run when testing:
        // runPerformanceTest(3, 512, 1234L, 1000, 100);

        // @Test-run just to exercise the code (running on CI server)
        // NOTE: Reference params are level:3, buffer:512, seed:1234L, customers:1000, rounds:100
        runPerformanceTest(4, 512, 1234L, 100, 10);
    }

    public void runPerformanceTest(int level, int bufferSize, long seed, int numCustomers, int roundsPer)
            throws Exception {
        Test_SerializationPerformance test = new Test_SerializationPerformance();

        CustomerData randomCustomerData = DummyFinancialService.createRandomReplyDTO(seed, numCustomers);

        // Warmup
        System.out.println("Warmup..");
        for (int i = 0; i < 5; i++) {
            test.performanceRun(level, bufferSize, roundsPer / 10, randomCustomerData);
        }
        System.out.println("Warmup done!\n");

        // Resulting array is 612162 bytes, with 1000 DTOs of seed 1234L

        // Run the test 10 times
        for (int i = 0; i < 10; i++) {
            test.performanceRun(level, bufferSize, roundsPer, randomCustomerData);
        }

        // WITHOUT Blackbird:
        // ====================
        // tot: 1531.47 ms, ser: 1361.20 ms, deflate: 170.27 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B
        // tot: 1589.85 ms, ser: 1418.12 ms, deflate: 171.72 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B
        // tot: 1481.42 ms, ser: 1312.59 ms, deflate: 168.83 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B
        // tot: 1461.33 ms, ser: 1292.11 ms, deflate: 169.22 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B
        // tot: 1454.09 ms, ser: 1284.64 ms, deflate: 169.44 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B
        // tot: 1444.50 ms, ser: 1275.45 ms, deflate: 169.05 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B
        // tot: 1440.75 ms, ser: 1270.40 ms, deflate: 170.35 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B
        // tot: 1440.17 ms, ser: 1270.03 ms, deflate: 170.14 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B
        // tot: 1443.82 ms, ser: 1273.47 ms, deflate: 170.34 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B
        // tot: 1442.33 ms, ser: 1273.36 ms, deflate: 168.97 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B

        // tot: 3736.66 ms, ser: 1264.45 ms, deflate: 2472.21 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B
        // tot: 3665.94 ms, ser: 1210.62 ms, deflate: 2455.32 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B
        // tot: 3727.64 ms, ser: 1245.93 ms, deflate: 2481.71 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B
        // tot: 3666.97 ms, ser: 1204.48 ms, deflate: 2462.49 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B
        // tot: 3647.36 ms, ser: 1175.93 ms, deflate: 2471.43 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B
        // tot: 3666.80 ms, ser: 1186.61 ms, deflate: 2480.19 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B
        // tot: 3652.73 ms, ser: 1176.92 ms, deflate: 2475.81 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B
        // tot: 3643.02 ms, ser: 1172.68 ms, deflate: 2470.34 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B
        // tot: 3645.41 ms, ser: 1178.49 ms, deflate: 2466.92 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B
        // tot: 3700.83 ms, ser: 1197.79 ms, deflate: 2503.05 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B

        // tot: 3986.17 ms, ser: 1374.68 ms, deflate: 2611.50 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B
        // tot: 3926.57 ms, ser: 1315.23 ms, deflate: 2611.34 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B
        // tot: 3911.21 ms, ser: 1296.73 ms, deflate: 2614.48 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B
        // tot: 3893.83 ms, ser: 1284.52 ms, deflate: 2609.31 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B
        // tot: 3904.06 ms, ser: 1278.67 ms, deflate: 2625.40 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B
        // tot: 3922.91 ms, ser: 1288.05 ms, deflate: 2634.85 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B
        // tot: 3920.99 ms, ser: 1287.21 ms, deflate: 2633.78 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B
        // tot: 3915.89 ms, ser: 1280.73 ms, deflate: 2635.16 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B
        // tot: 3916.48 ms, ser: 1279.46 ms, deflate: 2637.02 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B
        // tot: 3887.47 ms, ser: 1268.06 ms, deflate: 2619.41 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B

        // --- The jump in time from level 3 (here) to the next level is substantial, and not worth it.
        // tot: 4208.89 ms, ser: 1323.46 ms, deflate: 2885.43 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B
        // tot: 4370.32 ms, ser: 1407.99 ms, deflate: 2962.33 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B
        // tot: 4239.87 ms, ser: 1296.51 ms, deflate: 2943.36 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B
        // tot: 4208.89 ms, ser: 1279.94 ms, deflate: 2928.95 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B
        // tot: 4282.92 ms, ser: 1311.24 ms, deflate: 2971.68 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B
        // tot: 4206.66 ms, ser: 1274.75 ms, deflate: 2931.91 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B
        // tot: 4197.74 ms, ser: 1278.79 ms, deflate: 2918.94 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B
        // tot: 4134.92 ms, ser: 1252.90 ms, deflate: 2882.02 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B
        // tot: 4170.72 ms, ser: 1272.06 ms, deflate: 2898.66 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B
        // tot: 4151.89 ms, ser: 1268.49 ms, deflate: 2883.40 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B

        // tot: 5701.83 ms, ser: 1280.87 ms, deflate: 4420.96 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B
        // tot: 5676.25 ms, ser: 1270.07 ms, deflate: 4406.17 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B
        // tot: 5667.37 ms, ser: 1262.51 ms, deflate: 4404.86 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B
        // tot: 5730.43 ms, ser: 1265.52 ms, deflate: 4464.91 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B
        // tot: 5671.31 ms, ser: 1228.70 ms, deflate: 4442.61 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B
        // tot: 5724.32 ms, ser: 1250.16 ms, deflate: 4474.15 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B
        // tot: 5666.86 ms, ser: 1225.09 ms, deflate: 4441.76 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B
        // tot: 5631.56 ms, ser: 1211.65 ms, deflate: 4419.90 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B
        // tot: 5667.00 ms, ser: 1232.24 ms, deflate: 4434.76 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B
        // tot: 5739.04 ms, ser: 1252.39 ms, deflate: 4486.65 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B

        // USING Blackbird (Doesn't really seem to du much for this serialization side, more impact on deser):
        // ====================

        // tot: 1532.51 ms, ser: 1365.49 ms, deflate: 167.02 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B
        // tot: 1606.84 ms, ser: 1438.90 ms, deflate: 167.94 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B
        // tot: 1514.98 ms, ser: 1346.02 ms, deflate: 168.97 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B
        // tot: 1519.68 ms, ser: 1350.06 ms, deflate: 169.61 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B
        // tot: 1526.08 ms, ser: 1354.49 ms, deflate: 171.59 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B
        // tot: 1506.91 ms, ser: 1337.76 ms, deflate: 169.16 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B
        // tot: 1512.49 ms, ser: 1342.69 ms, deflate: 169.81 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B
        // tot: 1527.15 ms, ser: 1355.97 ms, deflate: 171.18 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B
        // tot: 1512.74 ms, ser: 1341.34 ms, deflate: 171.40 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B
        // tot: 1511.51 ms, ser: 1342.06 ms, deflate: 169.44 ms, 100 rounds, lvl:0, 512 B, custs:1000 = 2485059 B

        // tot: 3734.36 ms, ser: 1295.80 ms, deflate: 2438.57 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B
        // tot: 3702.55 ms, ser: 1265.03 ms, deflate: 2437.52 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B
        // tot: 3711.01 ms, ser: 1278.22 ms, deflate: 2432.79 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B
        // tot: 3633.63 ms, ser: 1206.34 ms, deflate: 2427.29 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B
        // tot: 3609.64 ms, ser: 1194.86 ms, deflate: 2414.77 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B
        // tot: 3641.29 ms, ser: 1209.38 ms, deflate: 2431.91 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B
        // tot: 3634.34 ms, ser: 1206.72 ms, deflate: 2427.62 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B
        // tot: 3611.04 ms, ser: 1189.83 ms, deflate: 2421.21 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B
        // tot: 3630.68 ms, ser: 1206.60 ms, deflate: 2424.08 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B
        // tot: 3654.94 ms, ser: 1215.22 ms, deflate: 2439.71 ms, 100 rounds, lvl:1, 512 B, custs:1000 = 749375 B

        // tot: 3914.47 ms, ser: 1296.64 ms, deflate: 2617.83 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B
        // tot: 3895.69 ms, ser: 1250.40 ms, deflate: 2645.29 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B
        // tot: 3983.38 ms, ser: 1301.65 ms, deflate: 2681.72 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B
        // tot: 3949.64 ms, ser: 1263.34 ms, deflate: 2686.30 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B
        // tot: 4005.70 ms, ser: 1282.59 ms, deflate: 2723.11 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B
        // tot: 3966.44 ms, ser: 1259.65 ms, deflate: 2706.80 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B
        // tot: 3829.25 ms, ser: 1208.67 ms, deflate: 2620.57 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B
        // tot: 3824.09 ms, ser: 1206.38 ms, deflate: 2617.70 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B
        // tot: 3793.81 ms, ser: 1190.22 ms, deflate: 2603.60 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B
        // tot: 3811.25 ms, ser: 1206.03 ms, deflate: 2605.22 ms, 100 rounds, lvl:2, 512 B, custs:1000 = 714996 B

        // -- Level 3 is clearly some kind of sweet spot before the time suddenly explodes.
        // tot: 4178.36 ms, ser: 1298.25 ms, deflate: 2880.11 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B
        // tot: 4145.64 ms, ser: 1270.33 ms, deflate: 2875.31 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B
        // tot: 4124.12 ms, ser: 1256.92 ms, deflate: 2867.20 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B
        // tot: 4102.98 ms, ser: 1226.68 ms, deflate: 2876.29 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B
        // tot: 4111.08 ms, ser: 1232.62 ms, deflate: 2878.46 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B
        // tot: 4103.26 ms, ser: 1222.22 ms, deflate: 2881.03 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B
        // tot: 4126.69 ms, ser: 1236.96 ms, deflate: 2889.73 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B
        // tot: 4156.77 ms, ser: 1245.57 ms, deflate: 2911.20 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B
        // tot: 4121.34 ms, ser: 1217.08 ms, deflate: 2904.25 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B
        // tot: 4245.34 ms, ser: 1264.44 ms, deflate: 2980.90 ms, 100 rounds, lvl:3, 512 B, custs:1000 = 687898 B

        // tot: 5784.82 ms, ser: 1334.14 ms, deflate: 4450.68 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B
        // tot: 5708.02 ms, ser: 1283.66 ms, deflate: 4424.36 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B
        // tot: 5720.49 ms, ser: 1305.87 ms, deflate: 4414.62 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B
        // tot: 5620.38 ms, ser: 1242.19 ms, deflate: 4378.20 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B
        // tot: 5637.49 ms, ser: 1243.83 ms, deflate: 4393.66 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B
        // tot: 5627.82 ms, ser: 1227.75 ms, deflate: 4400.07 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B
        // tot: 5673.37 ms, ser: 1251.13 ms, deflate: 4422.24 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B
        // tot: 5642.32 ms, ser: 1243.46 ms, deflate: 4398.86 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B
        // tot: 5665.56 ms, ser: 1253.82 ms, deflate: 4411.74 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B
        // tot: 5668.42 ms, ser: 1255.13 ms, deflate: 4413.29 ms, 100 rounds, lvl:4, 512 B, custs:1000 = 659132 B

        // --- Level 6 (default) is over 2x time use compared to level 3, and only saves a bit over 10%.
        // tot: 7523.13 ms, ser: 1342.73 ms, deflate: 6180.40 ms, 100 rounds, lvl:6, 512 B, custs:1000 = 612162 B
        // tot: 7422.73 ms, ser: 1311.69 ms, deflate: 6111.04 ms, 100 rounds, lvl:6, 512 B, custs:1000 = 612162 B
        // tot: 7407.04 ms, ser: 1300.63 ms, deflate: 6106.41 ms, 100 rounds, lvl:6, 512 B, custs:1000 = 612162 B
        // tot: 7303.42 ms, ser: 1219.59 ms, deflate: 6083.83 ms, 100 rounds, lvl:6, 512 B, custs:1000 = 612162 B
        // tot: 7283.75 ms, ser: 1216.85 ms, deflate: 6066.89 ms, 100 rounds, lvl:6, 512 B, custs:1000 = 612162 B
        // tot: 7297.64 ms, ser: 1226.01 ms, deflate: 6071.63 ms, 100 rounds, lvl:6, 512 B, custs:1000 = 612162 B
        // tot: 7292.12 ms, ser: 1225.60 ms, deflate: 6066.52 ms, 100 rounds, lvl:6, 512 B, custs:1000 = 612162 B
        // tot: 7300.88 ms, ser: 1228.59 ms, deflate: 6072.29 ms, 100 rounds, lvl:6, 512 B, custs:1000 = 612162 B
        // tot: 7259.97 ms, ser: 1221.45 ms, deflate: 6038.52 ms, 100 rounds, lvl:6, 512 B, custs:1000 = 612162 B
        // tot: 7263.51 ms, ser: 1220.53 ms, deflate: 6042.98 ms, 100 rounds, lvl:6, 512 B, custs:1000 = 612162 B

        // Going through a few buffer sizes:
        // ======================================

        // BufferSize 2, just since I started to wonder whether buffer size had any impact at all!
        // Total time: 9662.995537 ms for 100 rounds - level: 3, buffer: 2, result: 687898 bytes, customers:1000
        // Total time: 9920.813552 ms for 100 rounds - level: 3, buffer: 2, result: 687898 bytes, customers:1000
        // Total time: 10042.59282 ms for 100 rounds - level: 3, buffer: 2, result: 687898 bytes, customers:1000
        // Total time: 9709.977346 ms for 100 rounds - level: 3, buffer: 2, result: 687898 bytes, customers:1000

        // BufferSize 16:
        // Total time: 5256.93054 ms for 100 rounds - level: 3, buffer: 16, result: 687898 bytes, customers:1000
        // Total time: 4973.705214 ms for 100 rounds - level: 3, buffer: 16, result: 687898 bytes, customers:1000
        // Total time: 4875.01912 ms for 100 rounds - level: 3, buffer: 16, result: 687898 bytes, customers:1000
        // Total time: 4890.638872 ms for 100 rounds - level: 3, buffer: 16, result: 687898 bytes, customers:1000
        // Total time: 4912.142388 ms for 100 rounds - level: 3, buffer: 16, result: 687898 bytes, customers:1000
        // Total time: 4920.040087 ms for 100 rounds - level: 3, buffer: 16, result: 687898 bytes, customers:1000

        // BufferSize 64:
        // Total time: 4567.459694 ms for 100 rounds - level: 3, buffer: 64, result: 687898 bytes, customers:1000
        // Total time: 4612.152873 ms for 100 rounds - level: 3, buffer: 64, result: 687898 bytes, customers:1000
        // Total time: 4438.645618 ms for 100 rounds - level: 3, buffer: 64, result: 687898 bytes, customers:1000
        // Total time: 4555.732818 ms for 100 rounds - level: 3, buffer: 64, result: 687898 bytes, customers:1000
        // Total time: 4494.582959 ms for 100 rounds - level: 3, buffer: 64, result: 687898 bytes, customers:1000
        // Total time: 4442.495156 ms for 100 rounds - level: 3, buffer: 64, result: 687898 bytes, customers:1000
        // Total time: 4509.750257 ms for 100 rounds - level: 3, buffer: 64, result: 687898 bytes, customers:1000

        // BufferSize 128:
        // Total time: 4435.743685 ms for 100 rounds - level: 3, buffer: 128, result: 687898 bytes, customers:1000
        // Total time: 4479.487476 ms for 100 rounds - level: 3, buffer: 128, result: 687898 bytes, customers:1000
        // Total time: 4327.344419 ms for 100 rounds - level: 3, buffer: 128, result: 687898 bytes, customers:1000
        // Total time: 4361.385548 ms for 100 rounds - level: 3, buffer: 128, result: 687898 bytes, customers:1000
        // Total time: 4297.904289 ms for 100 rounds - level: 3, buffer: 128, result: 687898 bytes, customers:1000
        // Total time: 4366.318728 ms for 100 rounds - level: 3, buffer: 128, result: 687898 bytes, customers:1000
        // Total time: 4388.772146 ms for 100 rounds - level: 3, buffer: 128, result: 687898 bytes, customers:1000
        // Total time: 4333.329133 ms for 100 rounds - level: 3, buffer: 128, result: 687898 bytes, customers:1000
        // Total time: 4331.039569 ms for 100 rounds - level: 3, buffer: 128, result: 687898 bytes, customers:1000
        // Total time: 4350.307758 ms for 100 rounds - level: 3, buffer: 128, result: 687898 bytes, customers:1000

        // BufferSize 512:
        // Total time: 4575.330598 ms for 100 rounds - level: 3, buffer: 512, result: 687898 bytes, customers:1000
        // Total time: 4279.069993 ms for 100 rounds - level: 3, buffer: 512, result: 687898 bytes, customers:1000
        // Total time: 4310.023617 ms for 100 rounds - level: 3, buffer: 512, result: 687898 bytes, customers:1000
        // Total time: 4257.571465 ms for 100 rounds - level: 3, buffer: 512, result: 687898 bytes, customers:1000
        // Total time: 4220.090926 ms for 100 rounds - level: 3, buffer: 512, result: 687898 bytes, customers:1000
        // Total time: 4313.730688 ms for 100 rounds - level: 3, buffer: 512, result: 687898 bytes, customers:1000
        // Total time: 4298.156329 ms for 100 rounds - level: 3, buffer: 512, result: 687898 bytes, customers:1000
        // Total time: 4245.568414 ms for 100 rounds - level: 3, buffer: 512, result: 687898 bytes, customers:1000
        // Total time: 4291.005141 ms for 100 rounds - level: 3, buffer: 512, result: 687898 bytes, customers:1000
        // Total time: 4308.058008 ms for 100 rounds - level: 3, buffer: 512, result: 687898 bytes, customers:1000

        // BufferSize 1024:
        // Total time: 4522.327613 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000
        // Total time: 4430.868199 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000
        // Total time: 4353.357203 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000
        // Total time: 4321.856611 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000
        // Total time: 4292.707709 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000
        // Total time: 4286.090956 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000
        // Total time: 4268.136262 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000
        // Total time: 4399.799401 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000
        // Total time: 4307.942239 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000
        // Total time: 4348.029665 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000

        // BufferSize 2048:
        // Total time: 4395.836498 ms for 100 rounds - level: 3, buffer: 2048, result: 687898 bytes, customers:1000
        // Total time: 4265.209028 ms for 100 rounds - level: 3, buffer: 2048, result: 687898 bytes, customers:1000
        // Total time: 4271.275978 ms for 100 rounds - level: 3, buffer: 2048, result: 687898 bytes, customers:1000
        // Total time: 4290.334701 ms for 100 rounds - level: 3, buffer: 2048, result: 687898 bytes, customers:1000
        // Total time: 4241.760231 ms for 100 rounds - level: 3, buffer: 2048, result: 687898 bytes, customers:1000
        // Total time: 4295.010826 ms for 100 rounds - level: 3, buffer: 2048, result: 687898 bytes, customers:1000
        // Total time: 4254.160253 ms for 100 rounds - level: 3, buffer: 2048, result: 687898 bytes, customers:1000
        // Total time: 4226.106173 ms for 100 rounds - level: 3, buffer: 2048, result: 687898 bytes, customers:1000
        // Total time: 4264.894028 ms for 100 rounds - level: 3, buffer: 2048, result: 687898 bytes, customers:1000
        // Total time: 4313.819058 ms for 100 rounds - level: 3, buffer: 2048, result: 687898 bytes, customers:1000

        // BufferSize 4096:
        // Total time: 4458.504351 ms for 100 rounds - level: 3, buffer: 4096, result: 687898 bytes, customers:1000
        // Total time: 4456.578991 ms for 100 rounds - level: 3, buffer: 4096, result: 687898 bytes, customers:1000
        // Total time: 4513.778008 ms for 100 rounds - level: 3, buffer: 4096, result: 687898 bytes, customers:1000
        // Total time: 4404.563956 ms for 100 rounds - level: 3, buffer: 4096, result: 687898 bytes, customers:1000
        // Total time: 4362.458592 ms for 100 rounds - level: 3, buffer: 4096, result: 687898 bytes, customers:1000
        // Total time: 4307.790364 ms for 100 rounds - level: 3, buffer: 4096, result: 687898 bytes, customers:1000
        // Total time: 4235.82845 ms for 100 rounds - level: 3, buffer: 4096, result: 687898 bytes, customers:1000
        // Total time: 4249.823895 ms for 100 rounds - level: 3, buffer: 4096, result: 687898 bytes, customers:1000
        // Total time: 4358.046141 ms for 100 rounds - level: 3, buffer: 4096, result: 687898 bytes, customers:1000
        // Total time: 4345.050024 ms for 100 rounds - level: 3, buffer: 4096, result: 687898 bytes, customers:1000

        // Going through levels 0-9.
        // --------------------------------

        // Level 0, effectively only the serialization (compression is turned off)
        // Total time: 1716.816102 ms for 100 rounds - level: 0, buffer: 1024, result: 2485059 bytes, customers:1000
        // Total time: 1718.554545 ms for 100 rounds - level: 0, buffer: 1024, result: 2485059 bytes, customers:1000
        // Total time: 1622.358819 ms for 100 rounds - level: 0, buffer: 1024, result: 2485059 bytes, customers:1000
        // Total time: 1651.291828 ms for 100 rounds - level: 0, buffer: 1024, result: 2485059 bytes, customers:1000
        // Total time: 1683.609477 ms for 100 rounds - level: 0, buffer: 1024, result: 2485059 bytes, customers:1000
        // Total time: 1638.535303 ms for 100 rounds - level: 0, buffer: 1024, result: 2485059 bytes, customers:1000
        // Total time: 1622.640874 ms for 100 rounds - level: 0, buffer: 1024, result: 2485059 bytes, customers:1000
        // Total time: 1568.128189 ms for 100 rounds - level: 0, buffer: 1024, result: 2485059 bytes, customers:1000
        // Total time: 1610.871758 ms for 100 rounds - level: 0, buffer: 1024, result: 2485059 bytes, customers:1000
        // Total time: 1606.825142 ms for 100 rounds - level: 0, buffer: 1024, result: 2485059 bytes, customers:1000

        // Level 1, the first level with actual compression:
        // Total time: 4138.832036 ms for 100 rounds - level: 1, buffer: 1024, result: 749375 bytes, customers:1000
        // Total time: 3878.752826 ms for 100 rounds - level: 1, buffer: 1024, result: 749375 bytes, customers:1000
        // Total time: 3852.88005 ms for 100 rounds - level: 1, buffer: 1024, result: 749375 bytes, customers:1000
        // Total time: 3867.439689 ms for 100 rounds - level: 1, buffer: 1024, result: 749375 bytes, customers:1000
        // Total time: 3887.471913 ms for 100 rounds - level: 1, buffer: 1024, result: 749375 bytes, customers:1000
        // Total time: 3837.055004 ms for 100 rounds - level: 1, buffer: 1024, result: 749375 bytes, customers:1000
        // Total time: 3887.474753 ms for 100 rounds - level: 1, buffer: 1024, result: 749375 bytes, customers:1000
        // Total time: 3965.140787 ms for 100 rounds - level: 1, buffer: 1024, result: 749375 bytes, customers:1000
        // Total time: 3912.162943 ms for 100 rounds - level: 1, buffer: 1024, result: 749375 bytes, customers:1000
        // Total time: 3941.870273 ms for 100 rounds - level: 1, buffer: 1024, result: 749375 bytes, customers:1000

        // Level 2
        // Total time: 4389.394454 ms for 100 rounds - level: 2, buffer: 1024, result: 714996 bytes, customers:1000
        // Total time: 4150.547282 ms for 100 rounds - level: 2, buffer: 1024, result: 714996 bytes, customers:1000
        // Total time: 4108.784853 ms for 100 rounds - level: 2, buffer: 1024, result: 714996 bytes, customers:1000
        // Total time: 4046.32137 ms for 100 rounds - level: 2, buffer: 1024, result: 714996 bytes, customers:1000
        // Total time: 3942.180599 ms for 100 rounds - level: 2, buffer: 1024, result: 714996 bytes, customers:1000
        // Total time: 4068.538897 ms for 100 rounds - level: 2, buffer: 1024, result: 714996 bytes, customers:1000
        // Total time: 3995.136853 ms for 100 rounds - level: 2, buffer: 1024, result: 714996 bytes, customers:1000
        // Total time: 4026.4096 ms for 100 rounds - level: 2, buffer: 1024, result: 714996 bytes, customers:1000
        // Total time: 4270.444248 ms for 100 rounds - level: 2, buffer: 1024, result: 714996 bytes, customers:1000
        // Total time: 4132.46143 ms for 100 rounds - level: 2, buffer: 1024, result: 714996 bytes, customers:1000

        // Level 3: What I've chosen for the default!
        // Total time: 4462.51534 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000
        // Total time: 4530.647895 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000
        // Total time: 4462.212071 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000
        // Total time: 4333.106928 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000
        // Total time: 4284.681987 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000
        // Total time: 4216.117015 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000
        // Total time: 4238.206847 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000
        // Total time: 4256.928426 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000
        // Total time: 4304.750947 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000
        // Total time: 4227.792068 ms for 100 rounds - level: 3, buffer: 1024, result: 687898 bytes, customers:1000

        // Level 4
        // Total time: 6018.527958 ms for 100 rounds - level: 4, buffer: 1024, result: 659132 bytes, customers:1000
        // Total time: 5984.956531 ms for 100 rounds - level: 4, buffer: 1024, result: 659132 bytes, customers:1000
        // Total time: 6170.620138 ms for 100 rounds - level: 4, buffer: 1024, result: 659132 bytes, customers:1000
        // Total time: 6056.372299 ms for 100 rounds - level: 4, buffer: 1024, result: 659132 bytes, customers:1000
        // Total time: 5895.694926 ms for 100 rounds - level: 4, buffer: 1024, result: 659132 bytes, customers:1000
        // Total time: 5897.469762 ms for 100 rounds - level: 4, buffer: 1024, result: 659132 bytes, customers:1000
        // Total time: 5957.543975 ms for 100 rounds - level: 4, buffer: 1024, result: 659132 bytes, customers:1000
        // Total time: 5906.410388 ms for 100 rounds - level: 4, buffer: 1024, result: 659132 bytes, customers:1000
        // Total time: 5904.177308 ms for 100 rounds - level: 4, buffer: 1024, result: 659132 bytes, customers:1000
        // Total time: 5950.45121 ms for 100 rounds - level: 4, buffer: 1024, result: 659132 bytes, customers:1000

        // Level 5
        // Total time: 7028.35113 ms for 100 rounds - level: 5, buffer: 1024, result: 626518 bytes, customers:1000
        // Total time: 6712.656159 ms for 100 rounds - level: 5, buffer: 1024, result: 626518 bytes, customers:1000
        // Total time: 6850.748191 ms for 100 rounds - level: 5, buffer: 1024, result: 626518 bytes, customers:1000
        // Total time: 6765.511552 ms for 100 rounds - level: 5, buffer: 1024, result: 626518 bytes, customers:1000
        // Total time: 6724.487575 ms for 100 rounds - level: 5, buffer: 1024, result: 626518 bytes, customers:1000
        // Total time: 6776.72187 ms for 100 rounds - level: 5, buffer: 1024, result: 626518 bytes, customers:1000
        // Total time: 6743.101506 ms for 100 rounds - level: 5, buffer: 1024, result: 626518 bytes, customers:1000
        // Total time: 6741.256359 ms for 100 rounds - level: 5, buffer: 1024, result: 626518 bytes, customers:1000
        // Total time: 6730.295372 ms for 100 rounds - level: 5, buffer: 1024, result: 626518 bytes, customers:1000
        // Total time: 6850.774044 ms for 100 rounds - level: 5, buffer: 1024, result: 626518 bytes, customers:1000

        // Level 6 - this is the default level of zlib, AFAIU - probably meant to strike a balance.
        // Total time: 7604.562327 ms for 100 rounds - level: 6, buffer: 1024, result: 612162 bytes, customers:1000
        // Total time: 7717.861912 ms for 100 rounds - level: 6, buffer: 1024, result: 612162 bytes, customers:1000
        // Total time: 7521.657245 ms for 100 rounds - level: 6, buffer: 1024, result: 612162 bytes, customers:1000
        // Total time: 7560.952282 ms for 100 rounds - level: 6, buffer: 1024, result: 612162 bytes, customers:1000
        // Total time: 7597.463466 ms for 100 rounds - level: 6, buffer: 1024, result: 612162 bytes, customers:1000
        // Total time: 7546.706901 ms for 100 rounds - level: 6, buffer: 1024, result: 612162 bytes, customers:1000
        // Total time: 7562.246061 ms for 100 rounds - level: 6, buffer: 1024, result: 612162 bytes, customers:1000
        // Total time: 7517.395221 ms for 100 rounds - level: 6, buffer: 1024, result: 612162 bytes, customers:1000
        // Total time: 7534.058837 ms for 100 rounds - level: 6, buffer: 1024, result: 612162 bytes, customers:1000
        // Total time: 7521.705685 ms for 100 rounds - level: 6, buffer: 1024, result: 612162 bytes, customers:1000
        // NOTICE! When using -1, we get exact same results. This is the "DEFAULT_COMPRESSION = -1" value.
        // Total time: 7752.618194 ms for 100 rounds - level: -1, buffer: 1024, result: 612162 bytes, customers:1000
        // Total time: 7772.712459 ms for 100 rounds - level: -1, buffer: 1024, result: 612162 bytes, customers:1000
        // Total time: 7718.82963 ms for 100 rounds - level: -1, buffer: 1024, result: 612162 bytes, customers:1000
        // Total time: 7592.859771 ms for 100 rounds - level: -1, buffer: 1024, result: 612162 bytes, customers:1000
        // Total time: 7716.842988 ms for 100 rounds - level: -1, buffer: 1024, result: 612162 bytes, customers:1000
        // Total time: 7876.835907 ms for 100 rounds - level: -1, buffer: 1024, result: 612162 bytes, customers:1000
        // Total time: 7813.001309 ms for 100 rounds - level: -1, buffer: 1024, result: 612162 bytes, customers:1000
        // Total time: 7621.946851 ms for 100 rounds - level: -1, buffer: 1024, result: 612162 bytes, customers:1000
        // Total time: 7611.377681 ms for 100 rounds - level: -1, buffer: 1024, result: 612162 bytes, customers:1000
        // Total time: 7547.975683 ms for 100 rounds - level: -1, buffer: 1024, result: 612162 bytes, customers:1000

        // Level 7
        // Total time: 8438.745153 ms for 100 rounds - level: 7, buffer: 1024, result: 606784 bytes, customers:1000
        // Total time: 8289.720741 ms for 100 rounds - level: 7, buffer: 1024, result: 606784 bytes, customers:1000
        // Total time: 8354.198401 ms for 100 rounds - level: 7, buffer: 1024, result: 606784 bytes, customers:1000
        // Total time: 8358.864988 ms for 100 rounds - level: 7, buffer: 1024, result: 606784 bytes, customers:1000
        // Total time: 8315.58484 ms for 100 rounds - level: 7, buffer: 1024, result: 606784 bytes, customers:1000
        // Total time: 8321.845551 ms for 100 rounds - level: 7, buffer: 1024, result: 606784 bytes, customers:1000
        // Total time: 8278.273942 ms for 100 rounds - level: 7, buffer: 1024, result: 606784 bytes, customers:1000
        // Total time: 8269.806322 ms for 100 rounds - level: 7, buffer: 1024, result: 606784 bytes, customers:1000
        // Total time: 8270.745576 ms for 100 rounds - level: 7, buffer: 1024, result: 606784 bytes, customers:1000
        // Total time: 8356.527376 ms for 100 rounds - level: 7, buffer: 1024, result: 606784 bytes, customers:1000

        // Level 8
        // Total time: 12265.866791 ms for 100 rounds - level: 8, buffer: 1024, result: 596945 bytes, customers:1000
        // Total time: 12254.231312 ms for 100 rounds - level: 8, buffer: 1024, result: 596945 bytes, customers:1000
        // Total time: 12216.541083 ms for 100 rounds - level: 8, buffer: 1024, result: 596945 bytes, customers:1000
        // Total time: 12377.996948 ms for 100 rounds - level: 8, buffer: 1024, result: 596945 bytes, customers:1000
        // Total time: 12482.653086 ms for 100 rounds - level: 8, buffer: 1024, result: 596945 bytes, customers:1000
        // Total time: 12353.493259 ms for 100 rounds - level: 8, buffer: 1024, result: 596945 bytes, customers:1000
        // Total time: 12388.651504 ms for 100 rounds - level: 8, buffer: 1024, result: 596945 bytes, customers:1000
        // Total time: 12385.622313 ms for 100 rounds - level: 8, buffer: 1024, result: 596945 bytes, customers:1000
        // Total time: 12488.498007 ms for 100 rounds - level: 8, buffer: 1024, result: 596945 bytes, customers:1000
        // Total time: 12331.305128 ms for 100 rounds - level: 8, buffer: 1024, result: 596945 bytes, customers:1000

        // Level 9
        // Total time: 12905.396291 ms for 100 rounds - level: 9, buffer: 1024, result: 595989 bytes, customers:1000
        // Total time: 12757.038697 ms for 100 rounds - level: 9, buffer: 1024, result: 595989 bytes, customers:1000
        // Total time: 12783.29807 ms for 100 rounds - level: 9, buffer: 1024, result: 595989 bytes, customers:1000
        // Total time: 12757.13226 ms for 100 rounds - level: 9, buffer: 1024, result: 595989 bytes, customers:1000
        // Total time: 12854.29115 ms for 100 rounds - level: 9, buffer: 1024, result: 595989 bytes, customers:1000
        // Total time: 12791.283138 ms for 100 rounds - level: 9, buffer: 1024, result: 595989 bytes, customers:1000
        // Total time: 12685.242919 ms for 100 rounds - level: 9, buffer: 1024, result: 595989 bytes, customers:1000
        // Total time: 12812.3869 ms for 100 rounds - level: 9, buffer: 1024, result: 595989 bytes, customers:1000
        // Total time: 13091.152729 ms for 100 rounds - level: 9, buffer: 1024, result: 595989 bytes, customers:1000
        // Total time: 12879.436962 ms for 100 rounds - level: 9, buffer: 1024, result: 595989 bytes, customers:1000

        /*
         * My interpretation is that it takes exponentially longer time to compress the more you compress (with a
         * discontinuity when going from 8 to 9, which isn't such a big step in time - and even increases the size a
         * fraction!), while the compression ratio gains diminishes fast. That is, *much* higher and higher time used
         * for a smaller and smaller gain in compression. It is important to note that this is NOT a situation where we
         * compress once, and then it is transferred and decompressed many times (like a file download). This is a
         * situation where we compress and decompress each time we send a message. In addition, the size is not of that
         * immense importance, as the network is fast, and the time to store some few extra percents of data on the MQ
         * backend not that heavy either. I think level 3 could be a good compromise?
         */
    }

    private long performanceRun(int level, int bufferSize, int rounds, CustomerData customerData) throws Exception {
        long nanos_Total = 0;
        long nanos_Serialize_Total = 0;
        long nanos_Deflate_Total = 0;
        byte[] output = null;
        for (int i = 0; i < rounds; i++) {
            long nanosStart_Total = System.nanoTime();

            var baos = new ByteArrayOutputStream();
            var deflaterStream = new DeflaterOutputStreamWithStats(baos, bufferSize);
            deflaterStream.setCompressionLevel(level);
            _replyDtoWriter.writeValue(deflaterStream, customerData);
            output = baos.toByteArray();

            long nanosTaken_Total = System.nanoTime() - nanosStart_Total;
            nanos_Total += nanosTaken_Total;
            nanos_Deflate_Total += deflaterStream.getDeflateTimeNanos();
            nanos_Serialize_Total += nanosTaken_Total - deflaterStream.getDeflateTimeNanos();
        }

        assert output != null;
        System.out.println("tot: " + ms2(nanos_Total) + " ms, ser: " + ms2(nanos_Serialize_Total) + " ms,"
                + " deflate: " + ms2(nanos_Deflate_Total) + " ms, " + rounds + " rounds, lvl:" + level
                + ", " + bufferSize + " B, custs:" + customerData.customers.size() + " = " + output.length
                + " B");

        return nanos_Total;
    }

    public byte[] serializeDto(int level, int bufferSize, CustomerData customerData) throws IOException {
        var baos = new ByteArrayOutputStream();
        var deflaterStream = new DeflaterOutputStreamWithStats(baos, bufferSize);
        deflaterStream.setCompressionLevel(level);
        _replyDtoWriter.writeValue(deflaterStream, customerData);
        return baos.toByteArray();
    }
}

#MATS^3 and Jupiter(JUnit5)

Introduction context..

### 
* **Extension_Mats**: Extension which provides a full Mats harness for unit testing by creating MatsFactory utilizing an
in-vm Active MQ broker. Utilizes the default serializer provided by Mats.
* **Extension_MatsGeneric**: Extension similar to Extension_Mats, however this rule can be utilized if one has a MatsSerializer
which does not serialize to the type of String.
* **Extension_MatsEndpoint**: Extension which creates a "mock" endpoint, useful to when testing a Mats flow which relies on
replies from endpoints outside of your application. One can thus utilize this rule to mock the response of these 
"external" resources.

By utilizing either Extension_Mats or Extension_MatsGeneric in combination with Extension_MatsEndpoint there shouldn't 
be any testing scenarios which one would not be able to test. 

### Post test clean up
If you've read the documentation of "Mats and JUnit4", then you might have noticed the need for an explicit clean up of the
MatsFactory after each test. For Jupiter, this is **NOT** needed as this clean up is baked into all of
the extensions provided by this package.

#Examples
Following is a few simple examples of how one could utilize the aforementioned tools.

####Extension_Mats

```java
public class J_ExtensionMatsTest {
    @RegisterExtension
    public static final Extension_Mats __mats = Extension_Mats.createRule();
    private MatsFuturizer _matsFuturizer;

    @BeforeEach
    public void beforeEach() {
        _matsFuturizer = MatsFuturizer.createMatsFuturizer(__mats.getMatsFactory(), this.getClass().getSimpleName());
    }
    @AfterEach
    public void afterEach() {
        _matsFuturizer.close();
    }

    @Test
    public void verifyValidMatsFactoryCreated() throws InterruptedException, ExecutionException, TimeoutException {
        // :: Setup
        __mats.getMatsFactory().single("MyEndpoint", String.class, String.class, (ctx, msg) -> "Hello " + msg);

        // :: Act
        String reply = _matsFuturizer.futurizeInteractiveUnreliable("VerifyValidMatsFactory",
                getClass().getSimpleName(),
                "MyEndpoint",
                String.class,
                "World!")
                .thenApply(Reply::getReply)
                .get(10, TimeUnit.SECONDS);

        // :: Verify
        Assertions.assertEquals("Hello World!", reply);
    }
}
```
####Extension_MatsGeneric
Similarly to the usage of Extension_Mats, Extension_MatsGeneric can be utilized as such:
```java
public class J_ExtensionMatsGenericTest {
    @RegisterExtension // Utilizing type String and the default serializer, simply because I did not have another serializer implemented.
    public static final Extension_MatsGeneric<String> __mats =
            new Extension_MatsGeneric<>(MatsSerializerJson.create());
    private MatsFuturizer _matsFuturizer; // Futurizer for simple reply/request.

    @BeforeEach
    public void beforeEach() {
        _matsFuturizer = MatsFuturizer.createMatsFuturizer(__mats.getMatsFactory(), this.getClass().getSimpleName());
    }

    @AfterEach
    public void afterEach() {
        _matsFuturizer.close();
    }

    /**
     * Simple test to verify that we actually have a factory and a valid broker.
     */
    @Test
    public void verifyValidMatsFactoryCreated() throws InterruptedException, ExecutionException, TimeoutException {
        // :: Setup
        __mats.getMatsFactory().single("MyEndpoint", String.class, String.class, (ctx, msg) -> "Hello " + msg);

        // :: Act
        String reply = _matsFuturizer.futurizeInteractiveUnreliable("VerifyValidMatsFactory",
                getClass().getSimpleName(),
                "MyEndpoint",
                String.class,
                "World!")
                .thenApply(Reply::getReply)
                .get(10, TimeUnit.SECONDS);

        // :: Verify
        Assertions.assertEquals("Hello World!", reply);
    }
}
```

####Extension_MatsEndpoint
Extension_MatsEndpoint is a great utility when testing multi staged endpoints, both those which communicate with other
endpoints internally and those which communicate with external endpoints.

For tests where one does not want to fire up the entire application context, but rather just test the
logic of said multi stage. This can be done by utilizing the Extension_Mats harness and then utilizing Extension_MatsEndpoints
to mock the other internal endpoints which this endpoint interacts with.

For endpoints which communicate with external endpoints one can utilize Extension_MatsEndpoint to mock the response of these
external endpoints.

```java
public class J_ExtensionMatsEndpointComplexTest extends J_AbstractTestBase {
     /** Imagine this as another micro service in your system which this multistage communicates with. */
    private static final String EXTERNAL_ENDPOINT = "ExternalService.ExternalHello";
    /** Imagine this as an internal endpoint, but we don't want to bring up the class which contains it. */
    private static final String OTHER_INTERNAL_ENDPOINT = "OtherInternal.OtherHello";

    private static final String INTERNAL_RESPONSE = "InternalResponse";
    private static final String EXTERNAL_RESPONSE = "ExternalResponse";

    @RegisterExtension // Mock external endpoint
    public Extension_MatsEndpoint<String, String> _external =
            Extension_MatsEndpoint.single(EXTERNAL_ENDPOINT, String.class, String.class, (ctx, msg) -> EXTERNAL_RESPONSE)
                    .setMatsFactory(__mats.getMatsFactory());

    @RegisterExtension // Mock internal endpoint
    public Extension_MatsEndpoint<String, String> _internal = Extension_MatsEndpoint
            .single(OTHER_INTERNAL_ENDPOINT, String.class, String.class, (ctx, msg) -> INTERNAL_RESPONSE)
            .setMatsFactory(__mats.getMatsFactory());

    private static final String REQUEST_INTERNAL = "RequestInternalEndpoint";
    private static final String REQUEST_EXTERNAL = "RequestExternalEndpoint";

    @BeforeEach
    public void setupMultiStage() {
        // :: Setup up our multi stage.
        MatsEndpoint<String, MultiStageSTO> multiStage =
                __mats.getMatsFactory().staged("MultiStage", String.class, MultiStageSTO.class);
        //:: Receive the initial request, store it and call the internal mock.
        multiStage.stage(String.class, (ctx, state, msg) -> {
            // :: Store the incoming message as the initialRequest.
            state.initialRequest = msg;
            // :: Call the other internal endpoint mock
            ctx.request(OTHER_INTERNAL_ENDPOINT, REQUEST_INTERNAL);
        });
        // :: Receive the internal mock response, store it and query the external mock endpoint.
        multiStage.stage(String.class, (ctx, state, msg) -> {
            // :: Store the response of the internal endpoint.
            state.internalResponse = msg;
            // :: Query the external endpoint.
            ctx.request(EXTERNAL_ENDPOINT, REQUEST_EXTERNAL);
        });
        // :: Receive the external mock response, store it and respond to the initial request.
        multiStage.lastStage(String.class, (ctx, state, msg) -> {
            // :: Store the external response
            state.externalResponse = msg;
            // :: Reply
            return state.initialRequest + "-" + state.internalResponse + "-" + msg;
        });
    }

    @Test
    public void multiStageWithMockEndpoints() throws InterruptedException, ExecutionException, TimeoutException {
        String reply = _matsFuturizer.futurizeInteractiveUnreliable(
                getClass().getSimpleName() + "[multiStageTest]",
                getClass().getSimpleName(),
                "MultiStage",
                String.class,
                "Request")
                .thenApply(Reply::getReply)
                .get(3, TimeUnit.SECONDS);

        // :: Verify
        Assertions.assertEquals("Request-InternalResponse-ExternalResponse", reply);

        String requestInternal = _internal.waitForRequest();
        String requestExternal = _external.waitForRequest();

        Assertions.assertEquals(REQUEST_INTERNAL, requestInternal);
        Assertions.assertEquals(REQUEST_EXTERNAL, requestExternal);
    }

    /** MultiStage state class. */
    public static class MultiStageSTO {
        public String initialRequest;
        public String externalResponse;
        public String internalResponse;
    }
}
```

###With Spring
Extension_MatsEndpoint can also be utilized for tests utilizing a spring context where there is a MatsFactory created and put
into the context. This can be useful when testing the application integration internally and mocking the external 
endpoint dependencies.

mats-test-jupiter provides a Spring TestExecutionListener specifically designed to autowire extensions. To utilize this
annotate your configuration class as such:
```java
@ExtendWith(SpringExtension.class)
@TestExecutionListeners(value = SpringInjectExtensionsTestExecutionListener.class, mergeMode = MergeMode.MERGE_WITH_DEFAULTS)
```
For an example check out the test classes inside the test package: io.mats3.jupiter.spring

Note: **Spring only supports Jupiter as of version 5.0.0!**
# MATS^3 and JUnit4

To simplify testing of MATS related code one can utilize the following tools which provides a simple test harness to
execute unit tests as well as test where one would have a spring context available. 

* **Rule_Mats**: @ClassRule which provides a full Mats harness for unit testing by creating MatsFactory utilizing an
in-vm Active MQ broker.. Utilizes the default serializer provided by Mats.
* **Rule_MatsGeneric**: @ClassRule similar to Rule_Mats, however this rule can be utilized if one has a MatsSerializer
which does not serialize to the type of String.
* **Rule_MatsEndpoint**: @Rule which creates a "mock" endpoint, useful to when testing a Mats flow which relies on
replies from endpoints outside of your application. One can thus utilize this rule to mock the response of these 
"external" resources. The big point of this special type of method is twofold: You can change the ProcessLambda
dynamically, i.e. for each @Test method, it can answer with a different Reply, thus enabling to test the different
scenarios the Endpoints should handle. In addition, you can assert wait for, and assert, the incoming message.

By utilizing either Rule_Mats or Rule_MatsGeneric in combination with Rule_MatsEndpoint you should be pretty nicely
covered testing different scenarios for your service's Endpoints. Note that Rule_MatsEndpoint also works alone in
a Spring environment where you typically set up the MatsFactory in the Spring context; Read more shortly.

* **Rule_MatsWithDb**: Provides a H2 Database DataSource, and a JmsAndJdbc Mats Transaction Manager, in addition to
features from {@link Rule_MatsGeneric}. This enables testing of combined JMS and JDBC scenarios - in particularly
used for testing of the Mats library itself (check that commits and rollbacks work as expected).

### With spring
Rule_MatsEndpoint can also be utilized for tests utilizing a spring context where there is a MatsFactory created and put
into the context. This can be useful when testing the application integration internally and mocking the external 
endpoint dependencies.

mats-test-junit provides a Spring TestExecutionListener specifically designed to autowire rules. To utilize this
annotate your configuration class as such:
```java
@RunWith(SpringJUnit4ClassRunner.class)
@TestExecutionListeners(value = SpringInjectRulesTestExecutionListener.class, mergeMode = MergeMode.MERGE_WITH_DEFAULTS);
```
For an example check out the test classes inside the test package: io.mats3.junit.spring

#### Post test clean up
All tests in a particular class will share a MatsFactory when using the Rule_Mats, as it is a @ClassRule (which again
means that it is defined as a static field). MatsFactory wisely do not let you register an Endpoint under an already
existing EndpointId. Depending on how you code your tests, you could feel hampered by this: If you in each @Test method
set up the endpoints to be part of the test, and then perform the test, then you would maybe want a clean slate at  
the start of each test. The method Rule_Mats.removeAllEndpoints() can be utilized to get this effect, and you could then
use it as such:


```java
@After  // Run after each @Test method
public void cleanFactory() {
    MATS.removeAllEndpoints();
}
```

**HOWEVER, I would not really suggest this way of coding tests!** Rather, in a particular class, set up the set of
endpoints your test will exercise, possibly using a couple of Rule_MatsEndpoints to get tailored responses for
each test scenario (i.e. set their ProcessLambdas at the start of the @Test methods), and then execute the different
tests scenarios in different @Test methods. When the test class is finished, the Rule_Mats will clean up everything,
and the next test class will start afresh.

# Examples

Following is a few simple examples of how one could utilize the aforementioned tools.

#### Rule_Mats

```java
public class U_RuleMatsTest {
    @ClassRule // Setup the MATS harness.
    public static final Rule_Mats __mats = Rule_Mats.createRule();

    @Rule // Setup a target endpoint
    public final Rule_MatsEndpoint<String, String> _helloEndpoint =
            Rule_MatsEndpoint.single("HelloEndpoint", String.class, String.class, (ctx, msg) -> "Hello " + msg)
                    .setMatsFactory(__mats.getMatsFactory());

    @Rule // Stup our reply endpoint.
    public final Rule_MatsEndpoint<String, String> _replyEndpoint =
            Rule_MatsEndpoint.single("ReplyEndpoint", String.class, String.class).setMatsFactory(__mats.getMatsFactory());

    @Test
    public void getReplyFromEndpoint() throws MatsMessageSendException, MatsBackendException {
        // :: Send a message to hello endpoint with a specified reply as the specified reply endpoint.
        __mats.getMatsInitiator().initiate(msg -> msg.to("HelloEndpoint")
                .traceId(getClass().getSimpleName() + "[replyFromEndpointTest]")
                .from(getClass().getSimpleName())
                .replyTo("ReplyEndpoint", null)
                .request("World!")
        );

        // :: Wait for the message to reach our replyEndpoint
        String request = _replyEndpoint.waitForRequest();

        // :: Verify
        Assert.assertEquals("Hello World!", request);
    }
}
```

#### Rule_MatsGeneric

Similarly to the usage of Rule_Mats, Rule_MatsGeneric can be utilized as such:
```java
public class U_RuleMatsGenericTest {
    // Utilizing type String and the default serializer, simply because I did not have another serializer implemented.
    @ClassRule
    public static Rule_MatsGeneric<String> mats = new Rule_MatsGeneric<>(MatsSerializerJson.create());
    private MatsFuturizer _matsFuturizer; // Futurizer for simple reply/request.

    @Before
    public void beforeEach() {
        _matsFuturizer = MatsFuturizer.createMatsFuturizer(mats.getMatsFactory(), this.getClass().getSimpleName());
    }

    @After // Cleaning up after each test as specified in the documentation above!
    public void afterEach() {
        _matsFuturizer.close();
        mats.removeAllEndpoints();
    }

    @Test
    public void verifyValidMatsFactoryCreated() throws InterruptedException, ExecutionException, TimeoutException {
        // :: Setup
        mats.getMatsFactory().single("MyEndpoint", String.class, String.class, (ctx, msg) -> "Hello " + msg);

        // :: Act
        String reply = _matsFuturizer.futurizeInteractiveUnreliable("VerifyValidMatsFactory",
                getClass().getSimpleName(),
                "MyEndpoint",
                String.class,
                "World!")
                .thenApply(Reply::getReply)
                .get(10, TimeUnit.SECONDS);

        // :: Verify
        Assert.assertEquals("Hello World!", reply);
    }
}
```

#### Rule_MatsEndpoint

Rule_MatsEndpoint is a great utility when testing multi staged endpoints, both those which communicate with other
endpoints internally and those which communicate with external endpoints.

For tests where one does not want to fire up the entire application context, but rather just test the
logic of said multi stage. This can be done by utilizing the Rule_Mats harness and then utilizing Rule_MatsEndpoints
to mock the other internal endpoints which this endpoint interacts with.

For endpoints which communicate with external endpoints one can utilize Rule_MatsEndpoint to mock the response of these
external endpoints. 

```java
public class U_RuleMatsEndpointComplexTest extends U_AbstractTestBase {

    /** Imagine this as another micro service in your system which this multistage communicates with. */
    private static final String EXTERNAL_ENDPOINT = "ExternalService.ExternalHello";
    /** Imagine this as an internal endpoint, but we don't want to bring up the class which contains it. */
    private static final String OTHER_INTERNAL_ENDPOINT = "OtherInternal.OtherHello";

    private static final String EXTERNAL_RESPONSE = "ExternalResponse";
    private static final String INTERNAL_RESPONSE = "InternalResponse";
    private static final String REQUEST_EXTERNAL = "RequestExternalEndpoint";
    private static final String REQUEST_INTERNAL = "RequestInternalEndpoint";
    @Rule // Mock external endpoint
    public Rule_MatsEndpoint<String, String> _external =
            Rule_MatsEndpoint.single(EXTERNAL_ENDPOINT, String.class, String.class, (ctx, msg) -> EXTERNAL_RESPONSE)
                    .setMatsFactory(__mats.getMatsFactory());

    @Rule // Mock internal endpoint
    public Rule_MatsEndpoint<String, String> _internal = Rule_MatsEndpoint
            .single(OTHER_INTERNAL_ENDPOINT, String.class, String.class, (ctx, msg) -> INTERNAL_RESPONSE)
            .setMatsFactory(__mats.getMatsFactory());

    @Before
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
        Assert.assertEquals("Request-InternalResponse-ExternalResponse", reply);

        String requestInternal = _internal.waitForRequest();
        String requestExternal = _external.waitForRequest();
        
        Assert.assertEquals(REQUEST_INTERNAL, requestInternal);
        Assert.assertEquals(REQUEST_EXTERNAL, requestExternal);
    }

    /** MultiStage state class. */
    public static class MultiStageSTO {
        public String initialRequest;
        public String externalResponse;
        public String internalResponse;
    }
}
```

